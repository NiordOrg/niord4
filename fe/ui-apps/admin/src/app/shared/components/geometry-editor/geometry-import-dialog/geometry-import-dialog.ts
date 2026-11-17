import { Component, computed, DestroyRef, inject, model, output, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { TranslocoPipe } from '@jsverse/transloco';
import { catchError, EMPTY, type Observable, of, Subject, switchMap, timer } from 'rxjs';

import { GeometryApiService } from '../../../api/geometry-api.service';
import {
  emptyFeatureCollection,
  type GeometryFeatureCollection,
} from '../../../models/geometry.model';
import { Icon } from '../../../icons/icon';
import { Dialog } from '../../dialog/dialog';
import { EntityTree } from '../../entity-tree/entity-tree';
import type { HierarchicalEntity } from '../../entity-tree/hierarchical-entity.model';
import { AppTab, AppTabList, AppTabPanel, AppTabPanels, AppTabs } from '../../tabs/tabs';
import { AREA_GEOMETRY_SOURCE } from '../area-geometry-source.token';
import { GeometryTextExamples } from '../geometry-text-examples/geometry-text-examples';

const EMPTY_FC = emptyFeatureCollection();

/**
 * The "Import geometry" dialog: three tabs that each build a working FeatureCollection — From Area
 * (pick an area that carries a geometry), From Text (a GeoJSON-as-plain-text textarea, parsed by the
 * backend), From UTM (a UTM list, parsed by the backend). Confirming emits the working collection;
 * the host APPENDS it to the current geometry.
 */
@Component({
  selector: 'app-geometry-import-dialog',
  imports: [
    TranslocoPipe,
    Dialog,
    Icon,
    EntityTree,
    AppTabs,
    AppTabList,
    AppTab,
    AppTabPanels,
    AppTabPanel,
    GeometryTextExamples,
  ],
  template: `
    <app-dialog
      [visible]="visible()"
      (visibleChange)="visible.set($event)"
      (opened)="reset()"
      [modal]="true"
      [header]="'geometry.import.title' | transloco"
      [style]="{ width: '90vw', maxWidth: '42rem' }"
    >
      <app-tabs [value]="activeTab()" (valueChange)="onTabChange($event)">
        <app-tab-list>
          @if (hasAreaSource) {
            <app-tab value="area">{{ 'geometry.import.tab.area' | transloco }}</app-tab>
          }
          <app-tab value="text">{{ 'geometry.import.tab.text' | transloco }}</app-tab>
          <app-tab value="utm">{{ 'geometry.import.tab.utm' | transloco }}</app-tab>
        </app-tab-list>
        <app-tab-panels>
          @if (hasAreaSource) {
            <app-tab-panel value="area">
              <span class="mb-1 block text-sm text-fg-muted">
                {{ 'geometry.import.areaLabel' | transloco }}
              </span>
              <app-entity-tree
                [entities]="areaRoots()"
                [expandFirstLevel]="true"
                [selectedIds]="selectedAreaIds()"
                [filterPlaceholder]="'geometry.import.areaPlaceholder' | transloco"
                (entitySelected)="onAreaSelected($event)"
              />
            </app-tab-panel>
          }

          <app-tab-panel value="text">
            <p class="mb-2 text-sm text-fg-muted">
              {{ 'geometry.import.textHelp' | transloco }}
            </p>
            <app-geometry-text-examples />
            <textarea
              #textArea
              rows="8"
              class="w-full rounded border border-surface-300 bg-surface-0 p-2 font-mono text-xs dark:border-surface-700 dark:bg-surface-900"
              [style.border-color]="textInvalid() ? 'var(--color-red-500)' : null"
              [style.outline]="textInvalid() ? '2px solid var(--color-red-500)' : null"
              [value]="geometryText()"
              (input)="onTextInput(textArea.value)"
              [attr.aria-label]="'geometry.import.tab.text' | transloco"
            ></textarea>
          </app-tab-panel>

          <app-tab-panel value="utm">
            <p class="mb-2 text-sm text-fg-muted">
              {{ 'geometry.import.utmHelp' | transloco }}
            </p>
            @if (showUtmExamples()) {
              <div
                class="mb-2 rounded border border-surface-200 p-2 text-xs dark:border-surface-700"
              >
                <p class="mb-1 text-fg-muted">
                  {{ 'geometry.import.utmExampleFormat' | transloco }}
                </p>
                <pre class="mb-2 overflow-auto rounded bg-surface-50 p-2 dark:bg-surface-900">
32V 624726.15 6207884.60</pre
                >
                <p class="text-fg-muted">
                  {{ 'geometry.import.utmExampleNote' | transloco }}
                  <a
                    href="https://upload.wikimedia.org/wikipedia/commons/9/9e/LA2-Europe-UTM-zones.png"
                    target="_blank"
                    rel="noopener"
                    class="text-primary hover:underline"
                    >{{ 'geometry.import.utmExampleNoteLink' | transloco }}</a
                  >.
                </p>
              </div>
            }
            <button
              type="button"
              class="link-button mb-2 text-xs"
              (click)="showUtmExamples.set(!showUtmExamples())"
            >
              {{
                (showUtmExamples() ? 'geometry.example.hide' : 'geometry.example.show') | transloco
              }}
            </button>
            <textarea
              #utmArea
              rows="8"
              class="w-full rounded border border-surface-300 bg-surface-0 p-2 font-mono text-xs dark:border-surface-700 dark:bg-surface-900"
              [style.border-color]="utmInvalid() ? 'var(--color-red-500)' : null"
              [style.outline]="utmInvalid() ? '2px solid var(--color-red-500)' : null"
              [value]="utmText()"
              (input)="onUtmInput(utmArea.value)"
              [attr.aria-label]="'geometry.import.tab.utm' | transloco"
            ></textarea>
          </app-tab-panel>
        </app-tab-panels>
      </app-tabs>

      <ng-template #footer>
        <span class="mr-auto text-xs text-fg-muted">
          {{ 'geometry.import.featureCount' | transloco: { count: featureCount() } }}
        </span>
        <button type="button" class="btn-tertiary" (click)="visible.set(false)">
          {{ 'geometry.cancel' | transloco }}
        </button>
        <button type="button" class="btn-primary" [disabled]="importDisabled()" (click)="confirm()">
          <app-icon name="lucideDownload" />
          <span>{{ 'geometry.import.import' | transloco }}</span>
        </button>
      </ng-template>
    </app-dialog>
  `,
  host: { class: 'contents' },
})
export class GeometryImportDialog {
  private readonly geometryApi = inject(GeometryApiService);
  /** Optional — when no consumer provides an area catalog, the From-Area tab is hidden. */
  private readonly areaSource = inject(AREA_GEOMETRY_SOURCE, { optional: true });
  private readonly destroyRef = inject(DestroyRef);

  protected readonly hasAreaSource = this.areaSource !== null;

  readonly visible = model<boolean>(false);

  /** Emits the working collection to APPEND to the editor on confirm. */
  readonly imported = output<GeometryFeatureCollection>();

  protected readonly activeTab = signal<'area' | 'text' | 'utm'>(this.defaultTab());
  protected readonly geometryText = signal('');
  protected readonly utmText = signal('');
  /** Root areas (with nested children) for the From-Area tree picker (loaded on dialog open). */
  protected readonly areaRoots = signal<readonly HierarchicalEntity[]>([]);
  /** Ids of the picked area — drives the tree's row-selected highlight (single-select). */
  protected readonly selectedAreaIds = signal<readonly (string | number)[]>([]);
  protected readonly showUtmExamples = signal(false);

  /** The working FeatureCollection — what gets imported (shared across tabs). */
  protected readonly featureCollection = signal<GeometryFeatureCollection>(EMPTY_FC);
  protected readonly featureCount = computed(() => this.featureCollection().features.length);
  protected readonly valid = computed(() => this.featureCount() > 0);
  /**
   * True from a change of the active tab's input (a keystroke, a tab switch that re-parses, an area
   * pick) until its answer arrives. Until then the working set still holds what the previous input
   * gave, so Import waits — the same guard as the edit-as-text dialog's.
   */
  protected readonly parsePending = signal(false);
  protected readonly importDisabled = computed(() => !this.valid() || this.parsePending());
  /** Non-empty input that parsed to no geometry — drives the invalid-input highlight per tab. */
  protected readonly textInvalid = computed(
    () => this.geometryText().trim().length > 0 && !this.valid(),
  );
  protected readonly utmInvalid = computed(() => this.utmText().trim().length > 0 && !this.valid());

  private readonly textParse$ = new Subject<string>();
  private readonly utmParse$ = new Subject<string>();
  /** Area picks; `null` cancels the pick in flight (a tab switch, a reopen). */
  private readonly areaPick$ = new Subject<HierarchicalEntity | null>();

  constructor() {
    this.textParse$
      .pipe(
        switchMap((text) => this.parseAfterPause(text, (t) => this.geometryApi.parseGeometry(t))),
        takeUntilDestroyed(),
      )
      // Gate on the active tab so a debounce that resolves AFTER a tab switch / dialog reopen
      // cannot re-populate the (cleared) working set and wrongly re-enable Import.
      .subscribe((fc) => {
        if (this.activeTab() === 'text') this.settle(fc);
      });

    this.utmParse$
      .pipe(
        switchMap((text) => this.parseAfterPause(text, (t) => this.geometryApi.parseUtm(t))),
        takeUntilDestroyed(),
      )
      .subscribe((fc) => {
        if (this.activeTab() === 'utm') this.settle(fc);
      });

    // switchMap: a newer pick drops the answer of an earlier one, so two quick picks end on the
    // second; the tab gate drops an answer that lands after a switch to Text or UTM.
    this.areaPick$
      .pipe(
        switchMap((area) =>
          area && this.areaSource
            ? this.areaSource.getGeometry(area).pipe(catchError(() => of(EMPTY_FC)))
            : EMPTY,
        ),
        takeUntilDestroyed(),
      )
      .subscribe((fc) => {
        if (this.activeTab() === 'area') this.settle(fc);
      });
  }

  /**
   * Parses `text` 500 ms after the last change. Used under a `switchMap` on the input itself, so a
   * keystroke drops both the pause and a parse already in flight: the answer for an older text can
   * never land after a newer change and end the pending state with the older text's positions.
   */
  private parseAfterPause(
    text: string,
    parse: (text: string) => Observable<GeometryFeatureCollection>,
  ): Observable<GeometryFeatureCollection> {
    return timer(500).pipe(
      switchMap(() =>
        text.trim().length > 0 ? parse(text).pipe(catchError(() => of(EMPTY_FC))) : of(EMPTY_FC),
      ),
    );
  }

  /** The active tab's answer arrived: it becomes the working set, and Import may go. */
  private settle(fc: GeometryFeatureCollection | undefined): void {
    this.featureCollection.set(this.normalize(fc));
    this.parsePending.set(false);
  }

  protected reset(): void {
    this.areaPick$.next(null);
    this.parsePending.set(false);
    this.activeTab.set(this.defaultTab());
    this.geometryText.set('');
    this.utmText.set('');
    this.featureCollection.set(EMPTY_FC);
    this.selectedAreaIds.set([]);
    this.loadAreaRoots();
  }

  private defaultTab(): 'area' | 'text' {
    return this.hasAreaSource ? 'area' : 'text';
  }

  /**
   * On tab switch, re-derive the working set for the tab being shown: re-parse its retained text so
   * returning to a tab with valid input restores Import and clears the false "invalid" highlight;
   * otherwise (area, or empty input) clear it so the disabled state reflects the active tab.
   */
  protected onTabChange(value: unknown): void {
    const tab = value as 'area' | 'text' | 'utm';
    this.activeTab.set(tab);
    // An area pick still loading belongs to the tab being left (or to a highlight cleared below).
    this.areaPick$.next(null);
    if (tab === 'text' && this.geometryText().trim().length > 0) {
      this.parsePending.set(true);
      this.textParse$.next(this.geometryText());
    } else if (tab === 'utm' && this.utmText().trim().length > 0) {
      this.parsePending.set(true);
      this.utmParse$.next(this.utmText());
    } else {
      this.parsePending.set(false);
      this.featureCollection.set(EMPTY_FC);
      // Working set cleared → drop the area highlight so it stays in step with Import.
      this.selectedAreaIds.set([]);
    }
  }

  protected onTextInput(text: string): void {
    this.geometryText.set(text);
    this.parsePending.set(true);
    this.textParse$.next(text);
  }

  protected onUtmInput(text: string): void {
    this.utmText.set(text);
    this.parsePending.set(true);
    this.utmParse$.next(text);
  }

  /** Picking an area in the tree — the source resolves it to the FeatureCollection to import. */
  protected onAreaSelected(area: HierarchicalEntity): void {
    if (!this.areaSource) return;
    this.selectedAreaIds.set([area.id]);
    this.parsePending.set(true);
    this.areaPick$.next(area);
  }

  private loadAreaRoots(): void {
    this.areaSource
      ?.getRoots()
      .pipe(
        catchError(() => of([] as readonly HierarchicalEntity[])),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((roots) => this.areaRoots.set(roots));
  }

  protected confirm(): void {
    if (this.importDisabled()) return;
    this.imported.emit(this.featureCollection());
    this.visible.set(false);
  }

  private normalize(fc: GeometryFeatureCollection | undefined): GeometryFeatureCollection {
    return fc?.features?.length ? fc : EMPTY_FC;
  }
}
