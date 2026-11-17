import { Component, computed, inject, input, model, output, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { TranslocoPipe } from '@jsverse/transloco';

import { Dialog } from '../../../../shared/components/dialog/dialog';
import { OlTileLayer } from '@niord/map/layers/ol-tile-layer';
import { OlMap } from '@niord/map/ol-map';
import type { GeometryFeature } from '../../../../shared/models/geometry.model';
import { MessagesApiService } from '../../../messages/services/api/messages-api.service';
import { InsertLocationsMapLayer } from './insert-locations-map-layer';
import type {
  Message,
  MessageGeometryFeatureCollection,
} from '../../../messages/models/message.model';
import { readFormatLocationSettings, writeFormatLocationSettings } from './format-location-prefs';

/** A `{ value, labelKey }` option for the template / coordinate-format controls. */
interface DialogOption {
  readonly value: string;
  readonly labelKey: string;
}

const TEMPLATES: readonly DialogOption[] = [
  { value: 'numbered-list', labelKey: 'editor.dialog.locations.template.numberedList' },
  { value: 'bullet-list', labelKey: 'editor.dialog.locations.template.bulletList' },
  { value: 'table', labelKey: 'editor.dialog.locations.template.table' },
  { value: 'from-to-table', labelKey: 'editor.dialog.locations.template.fromToTable' },
  { value: 'text', labelKey: 'editor.dialog.locations.template.text' },
];

const FORMATS: readonly DialogOption[] = [
  { value: 'dec', labelKey: 'editor.dialog.locations.format.dec' },
  { value: 'dec-2', labelKey: 'editor.dialog.locations.format.dec2' },
  { value: 'dec-1', labelKey: 'editor.dialog.locations.format.dec1' },
  { value: 'sec', labelKey: 'editor.dialog.locations.format.sec' },
  { value: 'navtex', labelKey: 'editor.dialog.locations.format.navtex' },
];

/** The name property key on the feature carries the feature-level name per language. */
const NAME_PREFIX = 'name:';
/** Buffered features carry this property and are excluded from the list. */
const PARENT_FEATURE_IDS = 'parentFeatureIds';

interface FormatParams {
  readonly geometry: MessageGeometryFeatureCollection;
  readonly lang: string;
  readonly template: string;
  readonly format: string;
}

/**
 * The Insert-Locations dialog. Formats the invoking part's geometry into an
 * HTML fragment for the Description editor. Buffered features
 * (`properties.parentFeatureIds !== undefined`)
 * are excluded from the checkbox list; a sole feature auto-selects. The layout
 * (`numbered-list`/`bullet-list`/`table`/`from-to-table`/`text`) + coordinate
 * format (`dec`/`dec-2`/`dec-1`/`sec`/`navtex`) drive a live server render via
 * `format-message-geometry` (POST of the selected FeatureCollection) that re-fires
 * on any selection/template/format change; with no feature selected the endpoint
 * is NOT called and the preview stays blank. The layout + format round-trip
 * through `localStorage['formatLocationSettings']` (with the `'list' →
 * 'numbered-list'` rewrite on read). Insert emits the preview HTML and closes.
 *
 * A read-only map (`InsertLocationsMapLayer`) shows the message's features and
 * marks the selected ones, so a feature can be picked by position as well as by
 * its row in the list.
 */
@Component({
  selector: 'app-insert-locations-dialog',
  imports: [TranslocoPipe, Dialog, OlMap, OlTileLayer, InsertLocationsMapLayer],
  template: `
    <app-dialog
      [visible]="visible()"
      (visibleChange)="visible.set($event)"
      (opened)="onOpened()"
      [modal]="true"
      [header]="'editor.dialog.locations.header' | transloco"
      [style]="{ width: '50rem' }"
    >
      <div class="flex flex-col gap-4">
        @if (parts().length > 1) {
          <div class="flex flex-wrap items-center gap-3">
            <span class="text-sm font-medium">{{
              'editor.dialog.locations.part' | transloco
            }}</span>
            @for (part of parts(); track $index) {
              <label class="flex items-center gap-1.5 text-sm">
                <input
                  type="radio"
                  class="radio"
                  name="insert-locations-part"
                  [checked]="selectedPartIndex() === $index"
                  (change)="selectPart($index)"
                />
                <span>#{{ $index + 1 }}</span>
              </label>
            }
          </div>
        }

        <div class="grid gap-4 md:grid-cols-2">
          <div class="flex flex-col gap-4">
            <div class="flex flex-col gap-1">
              <div class="flex items-center justify-between border-b border-surface-200 pb-1">
                <span class="text-sm font-medium">{{
                  'editor.dialog.locations.features' | transloco
                }}</span>
                @if (features().length > 0) {
                  <span class="text-xs text-fg-subtle">
                    {{ 'editor.dialog.locations.select' | transloco }}
                    <button type="button" class="link-button is-subtle" (click)="selectAll(true)">
                      {{ 'editor.dialog.locations.all' | transloco }}
                    </button>
                    /
                    <button type="button" class="link-button is-subtle" (click)="selectAll(false)">
                      {{ 'editor.dialog.locations.none' | transloco }}
                    </button>
                  </span>
                }
              </div>
              @for (feature of features(); track $index) {
                <label class="flex items-center gap-2 text-sm">
                  <input
                    type="checkbox"
                    class="checkbox"
                    [checked]="selectedFeatures().has($index)"
                    (change)="toggleFeature($index)"
                  />
                  <span class="text-fg-subtle">#{{ $index + 1 }}</span>
                  <span>{{ featureLabel(feature) }}</span>
                </label>
              } @empty {
                <p class="py-1 text-sm text-fg-subtle">
                  {{ 'editor.dialog.locations.noFeatures' | transloco }}
                </p>
              }
            </div>
          </div>

          <div class="flex flex-col gap-3">
            <div class="flex flex-col gap-1">
              <span class="border-b border-surface-200 pb-1 text-sm font-medium">{{
                'editor.dialog.locations.layout' | transloco
              }}</span>
              @for (option of templates; track option.value) {
                <label class="flex items-center gap-2 text-sm">
                  <input
                    type="radio"
                    class="radio"
                    name="insert-locations-template"
                    [checked]="template() === option.value"
                    (change)="template.set(option.value)"
                  />
                  <span>{{ option.labelKey | transloco }}</span>
                </label>
              }
              <select
                class="text-input mt-2 w-40"
                [attr.aria-label]="'editor.dialog.locations.coordinateFormat' | transloco"
                (change)="format.set($any($event.target).value)"
              >
                @for (option of formats; track option.value) {
                  <option [value]="option.value" [selected]="option.value === format()">
                    {{ option.labelKey | transloco }}
                  </option>
                }
              </select>
            </div>
          </div>
        </div>

        <!-- Interactive feature-picker map: click a feature to toggle its checkbox (and back).
             Gated on visible() so a correctly-sized map mounts fresh each time the dialog opens. -->
        @if (visible() && features().length > 0) {
          <div class="relative min-h-56 overflow-hidden rounded border border-surface-200">
            <div class="absolute inset-0">
              <app-ol-map>
                <app-ol-tile-layer source="OSM" />
                <app-insert-locations-map-layer
                  [features]="features()"
                  [selectedIndices]="selectedFeatures()"
                  [mainType]="message()?.mainType"
                  (toggled)="toggleFeature($event)"
                />
              </app-ol-map>
            </div>
          </div>
        }

        <div class="flex flex-col gap-1 border-t border-surface-200 pt-3">
          <span class="text-sm font-medium">{{
            'editor.dialog.locations.result' | transloco
          }}</span>
          @if (previewResource.error()) {
            <p class="text-sm text-danger">{{ 'editor.dialog.locations.error' | transloco }}</p>
          } @else {
            <div
              class="message-details-html prose-sm max-w-none rounded border border-surface-200 p-2"
              [innerHTML]="previewResource.value()"
            ></div>
          }
        </div>
      </div>

      <ng-template #footer>
        <button type="button" class="btn-tertiary" (click)="visible.set(false)">
          {{ 'term.cancel' | transloco }}
        </button>
        <button
          type="button"
          class="btn-primary"
          [disabled]="selectedFeatures().size === 0"
          (click)="insert()"
        >
          {{ 'editor.dialog.locations.insert' | transloco }}
        </button>
      </ng-template>
    </app-dialog>
  `,
  host: { class: 'contents' },
})
export class InsertLocationsDialog {
  readonly visible = model(false);
  /** The message whose parts supply the geometry (facade-owned, read-only). */
  readonly message = input<Message | undefined>(undefined);
  /** The invoking part index — preselected. */
  readonly partIndex = input<number>(0);
  /** The target model language for the render + the field the fragment is inserted into. */
  readonly lang = input<string>('');
  /** The rendered HTML fragment, emitted on Insert. */
  readonly insertContent = output<string>();

  private readonly api = inject(MessagesApiService);

  protected readonly templates = TEMPLATES;
  protected readonly formats = FORMATS;

  protected readonly selectedPartIndex = signal(0);
  protected readonly template = signal('numbered-list');
  protected readonly format = signal('dec');
  protected readonly selectedFeatures = signal<ReadonlySet<number>>(new Set());

  protected readonly parts = computed(() => this.message()?.parts ?? []);

  /** The part's geometry features EXCLUDING buffered ones. */
  protected readonly features = computed<readonly GeometryFeature[]>(() => {
    const collection = this.parts()[this.selectedPartIndex()]?.geometry;
    return (collection?.features ?? []).filter(
      (feature) => (feature.properties ?? {})[PARENT_FEATURE_IDS] === undefined,
    );
  });

  private readonly selectedFeatureList = computed<readonly GeometryFeature[]>(() => {
    const selected = this.selectedFeatures();
    return this.features().filter((_, index) => selected.has(index));
  });

  // Live server-rendered preview. `undefined` params (no feature selected) leaves the
  // resource idle — the endpoint is never called with an empty FeatureCollection.
  protected readonly previewResource = rxResource<string, FormatParams | undefined>({
    params: () => {
      const features = this.selectedFeatureList();
      if (features.length === 0) {
        return undefined;
      }
      return {
        geometry: { type: 'FeatureCollection', features: [...features] },
        lang: this.lang(),
        template: this.template(),
        format: this.format(),
      };
    },
    // An undefined params() puts the resource in the idle state — the stream never runs then.
    stream: ({ params }) =>
      this.api.formatMessageGeometry(params.geometry, {
        lang: params.lang,
        template: params.template,
        format: params.format,
      }),
    defaultValue: '',
  });

  protected onOpened(): void {
    const parts = this.parts();
    const index = Math.min(Math.max(0, this.partIndex()), Math.max(0, parts.length - 1));
    this.selectedPartIndex.set(index);
    const prefs = readFormatLocationSettings();
    this.template.set(prefs.template);
    this.format.set(prefs.format);
    this.autoSelect();
  }

  protected selectPart(index: number): void {
    this.selectedPartIndex.set(index);
    this.autoSelect();
  }

  protected toggleFeature(index: number): void {
    this.selectedFeatures.update((selected) => {
      const next = new Set(selected);
      if (next.has(index)) {
        next.delete(index);
      } else {
        next.add(index);
      }
      return next;
    });
  }

  protected selectAll(select: boolean): void {
    this.selectedFeatures.set(
      select ? new Set(this.features().map((_, index) => index)) : new Set(),
    );
  }

  /** Feature-level name in the target language, else the geometry type (the list label). */
  protected featureLabel(feature: GeometryFeature): string {
    const name = (feature.properties ?? {})[`${NAME_PREFIX}${this.lang()}`];
    if (typeof name === 'string' && name.trim() !== '') {
      return name;
    }
    return feature.geometry?.type ?? '';
  }

  protected insert(): void {
    writeFormatLocationSettings({ template: this.template(), format: this.format() });
    // value() THROWS in the resource error state — guard so a failed render inserts nothing.
    const html = this.previewResource.hasValue() ? this.previewResource.value() : '';
    this.insertContent.emit(this.normalizeTemplateWhitespace(html));
    this.visible.set(false);
  }

  /**
   * Normalise the server-rendered fragment before insertion:
   *  1. strip the FreeMarker template's formatting whitespace — the leading indentation and the
   *     newline after each position's separator — which the pre-wrap editor surface would otherwise
   *     render as extra space (the "text" layout) or a blank line around the table (list/table
   *     layouts). Only whitespace runs that SPAN a newline are template artifacts; a coordinate's
   *     own inline spaces never cross a line, so they survive.
   *  2. replace each `,&nbsp;` position separator with a plain comma + space, so the inserted list
   *     reads as `POS1, POS2, POS3` with no non-breaking-space artifact.
   */
  private normalizeTemplateWhitespace(html: string): string {
    return html
      .replace(/\s*[\r\n]+\s*/g, '')
      .replace(/,&nbsp;/gi, ', ')
      .trim();
  }

  /** Auto-select a sole feature (a feature collection of exactly one). */
  private autoSelect(): void {
    const count = this.features().length;
    this.selectedFeatures.set(count === 1 ? new Set([0]) : new Set());
  }
}
