import {
  Component,
  computed,
  DestroyRef,
  inject,
  input,
  model,
  output,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { TranslocoPipe } from '@jsverse/transloco';
import { catchError, debounceTime, map, of, Subject, switchMap } from 'rxjs';

import { APP_CONFIG } from '../../../../core/config/app-config.token';
import { DomainFacade } from '../../../../core/domain/facades/domain.facade';
import { LanguageService } from '../../../../core/main-shell/services/language.service';
import { Icon } from '../../../icons/icon';
import { Dialog } from '../../dialog/dialog';
import { computeInitialMapView } from '../../ol-map/map-view-defaults';
import { MapShellService } from '@niord/map/map-shell.service';
import { type OlMapView, OlMap } from '@niord/map/ol-map';
import { OlTileLayer } from '@niord/map/layers/ol-tile-layer';
import {
  emptyFeatureCollection,
  type GeometryFeatureCollection,
} from '../../../models/geometry.model';
import { DiscardConfirmService } from '../../../services/discard-confirm.service';
import { GeometryApiService } from '../../../api/geometry-api.service';
import { GeometryTextExamples } from '../geometry-text-examples/geometry-text-examples';
import { OlGeometryPreviewLayer } from '../ol-geometry-preview-layer/ol-geometry-preview-layer';

const EMPTY_FC = emptyFeatureCollection();

/**
 * The "Edit as text" dialog: the current geometry is pre-formatted to plain text by the backend,
 * edited in a textarea (re-parsed by the backend as you type), with a live read-only map preview.
 * Saving REPLACES the editor's geometry. A failed initial format shows a warning + empties the set.
 */
@Component({
  selector: 'app-edit-as-text-dialog',
  imports: [
    TranslocoPipe,
    Dialog,
    Icon,
    OlMap,
    OlTileLayer,
    OlGeometryPreviewLayer,
    GeometryTextExamples,
  ],
  // Isolate the preview map's shell from the host editor's MapShellService — without its own
  // instance, the preview's base tile + geometry layer leak into the main editor map's shell.
  providers: [MapShellService],
  template: `
    <app-dialog
      [visible]="visible()"
      (visibleChange)="visible.set($event)"
      (opened)="initFromInput()"
      [modal]="true"
      [closable]="false"
      [dirty]="dirty()"
      [header]="'geometry.editAsText.title' | transloco"
      [style]="{ width: '90vw', maxWidth: '56rem' }"
    >
      @if (parseError()) {
        <div
          class="mb-2 rounded border border-danger/30 bg-danger/10 px-3 py-2 text-sm text-danger"
        >
          <app-icon name="lucideTriangleAlert" class="mr-1" />
          {{ 'geometry.editAsText.parseError' | transloco }}
        </div>
      }
      <p class="mb-2 text-sm text-fg-muted">{{ 'geometry.editAsText.help' | transloco }}</p>
      <app-geometry-text-examples />
      <div class="flex min-h-0 gap-3" style="height: 22rem">
        <textarea
          #textArea
          class="min-w-0 flex-1 rounded border border-surface-300 bg-surface-0 p-2 font-mono text-xs dark:border-surface-700 dark:bg-surface-900"
          [style.border-color]="textInvalid() ? 'var(--color-red-500)' : null"
          [style.outline]="textInvalid() ? '2px solid var(--color-red-500)' : null"
          [value]="geometryText()"
          (input)="onTextInput(textArea.value)"
          [attr.aria-label]="'geometry.editAsText.title' | transloco"
        ></textarea>
        <!-- Width lives on this wrapper, not on <app-ol-map>: the map's own
             :host width:100% would override a width class set on it directly
             (and steal the flex row, collapsing the textarea). -->
        <div
          class="w-2/5 min-w-0 overflow-hidden rounded border border-surface-200 dark:border-surface-700"
        >
          <app-ol-map class="block h-full w-full" [readOnly]="true" [view]="initialView()">
            <app-ol-tile-layer source="OSM" />
            <app-ol-geometry-preview-layer [featureCollection]="workingFc()" />
          </app-ol-map>
        </div>
      </div>

      <ng-template #footer>
        <span class="mr-auto text-xs text-fg-muted">
          {{ 'geometry.import.featureCount' | transloco: { count: featureCount() } }}
        </span>
        <button type="button" class="btn-tertiary" (click)="requestClose()">
          {{ 'geometry.cancel' | transloco }}
        </button>
        <button
          type="button"
          class="btn-primary"
          [disabled]="featureCount() === 0 || parsePending() || !dirty()"
          (click)="save()"
        >
          <app-icon name="lucideCheck" />
          <span>{{ 'geometry.editAsText.save' | transloco }}</span>
        </button>
      </ng-template>
    </app-dialog>
  `,
  host: {
    class: 'contents',
  },
})
export class EditAsTextDialog {
  private readonly geometryApi = inject(GeometryApiService);
  private readonly languageService = inject(LanguageService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly config = inject(APP_CONFIG);
  private readonly domain = inject(DomainFacade);
  private readonly discardConfirm = inject(DiscardConfirmService);

  readonly visible = model<boolean>(false);
  /** The editor's current geometry — pre-formatted to plain text when the dialog opens. */
  readonly featureCollection = input<GeometryFeatureCollection | undefined>(undefined);
  /** Emits the working collection to REPLACE the editor's geometry on save. */
  readonly saved = output<GeometryFeatureCollection>();

  protected readonly geometryText = signal('');
  /** The text as first presented on open — Save stays disabled until it changes. */
  private readonly initialText = signal('');
  protected readonly workingFc = signal<GeometryFeatureCollection>(EMPTY_FC);
  protected readonly parseError = signal(false);
  // True from a keystroke until its debounced parse settles — gates Save so the (stale) debounced
  // workingFc cannot be committed in the sub-500ms window after the textarea changed.
  protected readonly parsePending = signal(false);
  protected readonly featureCount = computed(() => this.workingFc().features.length);
  /** True once the user has changed the text from its initial value — gates Save. */
  protected readonly dirty = computed(() => this.geometryText() !== this.initialText());
  /** Non-empty text that parsed to no geometry — drives the invalid-input highlight. */
  protected readonly textInvalid = computed(
    () => this.geometryText().trim().length > 0 && this.featureCount() === 0,
  );
  /** Initial map view when there's no geometry to fit: the session domain's center/zoom, like the editor. */
  protected readonly initialView = computed<OlMapView | undefined>(() =>
    computeInitialMapView(this.domain.mapDefaults(), this.config.map),
  );

  private readonly textParse$ = new Subject<string>();
  /** Bumped on each (re)open; async parse/format results from an earlier open are ignored. */
  private openToken = 0;

  constructor() {
    this.textParse$
      .pipe(
        // Capture the open-token at TYPE time (before the debounce) so a parse queued in a
        // previous open cannot land on a later open's freshly-initialised state.
        map((text) => ({ text, token: this.openToken })),
        debounceTime(500),
        switchMap(({ text, token }) =>
          (text.trim().length > 0
            ? this.geometryApi.parseGeometry(text).pipe(catchError(() => of(EMPTY_FC)))
            : of(EMPTY_FC)
          ).pipe(map((fc) => ({ fc, token }))),
        ),
        takeUntilDestroyed(),
      )
      .subscribe(({ fc, token }) => {
        if (token === this.openToken) {
          this.workingFc.set(fc?.features?.length ? fc : EMPTY_FC);
          this.parsePending.set(false);
        }
      });
  }

  /** On open: pre-format the input geometry to plain text; warn if it fails. */
  protected initFromInput(): void {
    this.openToken++;
    const token = this.openToken;
    this.parseError.set(false);
    this.parsePending.set(false);
    const fc = this.featureCollection();
    if (!fc || fc.features.length === 0) {
      this.geometryText.set('');
      this.initialText.set('');
      this.workingFc.set(EMPTY_FC);
      return;
    }
    this.workingFc.set(fc);
    this.geometryApi
      .formatGeometry(fc, this.languageService.selectedLanguage())
      .pipe(
        catchError(() => of('')),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((text) => {
        if (token !== this.openToken) return; // a newer open superseded this format
        this.geometryText.set(text ?? '');
        // Baseline for the dirty gate — Save enables only once this changes.
        this.initialText.set(text ?? '');
        if (!text) {
          this.parseError.set(true);
          this.workingFc.set(EMPTY_FC);
        }
      });
  }

  protected onTextInput(text: string): void {
    this.geometryText.set(text);
    this.parsePending.set(true);
    this.textParse$.next(text);
  }

  protected save(): void {
    if (this.featureCount() === 0 || this.parsePending() || !this.dirty()) return;
    this.saved.emit(this.workingFc());
    this.visible.set(false);
  }

  /**
   * Cancel-button close intent — confirms first when the text has unsaved edits,
   * through the app discard-confirm dialog (same surface as the drawer/editor), so
   * a stray Cancel can't silently drop the user's work. The dialog shell applies
   * the identical gate to its own Esc close via `[dirty]`.
   */
  protected async requestClose(): Promise<void> {
    if (this.dirty() && !(await this.discardConfirm.confirmDiscard())) {
      return;
    }
    this.visible.set(false);
  }
}
