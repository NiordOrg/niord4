import { Component, computed, inject, linkedSignal, ResourceStatus, signal } from '@angular/core';
import { httpResource } from '@angular/common/http';
import { TranslocoPipe } from '@jsverse/transloco';
import { PublicationListComponent } from './components/publication-list/publication-list.component';
import { PublicationService } from '../../core/services/publication.service';
import { LanguageService } from '../../core/services/language.service';
import { AppConfigService } from '../../core/services/app-config.service';
import { DateFormatService } from '../../core/services/date-format.service';
import { Publication } from '../../core/models/publication.model';
import {
  PublicationRow,
  sortPublicationDescs,
  withCategoryHeadings,
} from '../../core/models/publication.util';
import { AppTab, AppTabList, AppTabPanel, AppTabs } from '../../shared/components/tabs/tabs';
import { Icon } from '../../shared/icons/icon';
import { settledValue } from '../../shared/utils/settled-value';
import { endOfZonedDayMs, startOfZonedDayMs } from '../../shared/utils/zoned-date';

@Component({
  selector: 'app-publications',
  imports: [
    TranslocoPipe,
    PublicationListComponent,
    AppTabs,
    AppTabList,
    AppTab,
    AppTabPanel,
    Icon,
  ],
  template: `
    <div class="publication-list">
      <app-tabs>
        <app-tab-list class="mb-0" [value]="activeTab()" (valueChange)="selectTab($event)">
          <app-tab value="active">{{ 'ACTIVE_PUBLICATIONS' | transloco }}</app-tab>
          <app-tab value="historical">{{ 'HISTORICAL_PUBLICATIONS' | transloco }}</app-tab>
        </app-tab-list>

        <app-tab-panel value="active">
          <div class="rounded-b border border-t-0 border-surface-300 bg-surface-0 p-5">
            <!-- A live region from the start, so the notice is announced when it arrives: an
                 empty table reads as "there are none", which a read that failed is not. -->
            <div role="alert">
              @if (activeFailed()) {
                <p class="message message-error mb-5">
                  <app-icon name="lucideTriangleAlert" class="message-icon" />
                  {{ 'PUBLICATIONS_LOAD_FAILED' | transloco }}
                </p>
              }
            </div>
            <app-publication-list [rows]="activeRows()" />
          </div>
        </app-tab-panel>

        <app-tab-panel value="historical">
          <div class="rounded-b border border-t-0 border-surface-300 bg-surface-0 p-5">
            <fieldset
              class="mb-5 flex flex-wrap items-center justify-center gap-2 rounded border border-surface-200 p-5"
            >
              <legend class="px-1 text-sm">{{ 'CUSTOM_DATE_RANGE' | transloco }}</legend>

              <!-- Label and field stay one wrapping unit: at phone width a bare row leaves the
                   second label on the line above its own input. -->
              <span class="inline-flex items-center gap-2">
                <label class="text-sm" for="publications-from">{{ 'DATE_FROM' | transloco }}</label>
                <input
                  id="publications-from"
                  type="date"
                  class="text-input w-40"
                  [value]="startDate()"
                  (change)="startDate.set(dateValue($event))"
                />
              </span>

              <span class="inline-flex items-center gap-2">
                <label class="text-sm" for="publications-to">{{ 'DATE_TO' | transloco }}</label>
                <!-- Its calendar starts at the From day; a day before it typed by hand is not
                     read (historicalResource). -->
                <input
                  id="publications-to"
                  type="date"
                  class="text-input w-40"
                  [min]="startDate()"
                  [value]="endDate()"
                  (change)="endDate.set(dateValue($event))"
                />
              </span>

              @for (range of ranges; track range.name) {
                <button
                  type="button"
                  class="chip"
                  [class.is-active]="isSelected(range)"
                  [attr.aria-pressed]="isSelected(range)"
                  (click)="setDateRange(range.start, range.end)"
                >
                  {{ range.name }}
                </button>
              }
            </fieldset>

            <div role="alert">
              @if (historicalFailed()) {
                <p class="message message-error mb-5">
                  <app-icon name="lucideTriangleAlert" class="message-icon" />
                  {{ 'PUBLICATIONS_LOAD_FAILED' | transloco }}
                </p>
              }
            </div>
            <app-publication-list [rows]="historicalRows()" />
          </div>
        </app-tab-panel>
      </app-tabs>
    </div>
  `,
})
export class PublicationsComponent {
  private readonly api = inject(PublicationService);
  private readonly language = inject(LanguageService).language;
  private readonly timeZone = inject(AppConfigService).timeZone;
  private readonly dateFormat = inject(DateFormatService);

  readonly activeTab = signal('active');
  /** Both hold what a native date input produces: '' or 'YYYY-MM-DD'. */
  readonly startDate = signal('');
  readonly endDate = signal('');

  readonly ranges: { name: string; start: string; end: string }[];

  constructor() {
    const currentYear = this.dateFormat.currentYear();
    this.ranges = Array.from({ length: 5 }, (_, i) => {
      const year = currentYear - i;
      return {
        name: String(year),
        start: `${year}-01-01`,
        end: `${year}-12-31`,
      };
    });
  }

  /**
   * Keyed on the language alone: the list the backend considers current. `lang` is the one name
   * the public API reads the language by; each row comes back with its text in that language, or
   * in one it has when it has none in that.
   */
  private readonly activeResource = httpResource<Publication[]>(
    () => ({
      url: '/rest/public/v1/publications',
      params: { lang: this.language(), externalize: 'false' },
    }),
    { defaultValue: [], parse: (raw) => this.api.parse(raw) },
  );

  /**
   * Keyed on the language and the two day bounds, as epoch millis resolved in the DEPLOYMENT's
   * zone — so "1 January" is the first of January in the deployment's zone, never the reader's.
   * Until the reader has filled both inputs there is nothing to read, and the resource stays
   * idle; a bound that names a day the calendar does not have is left off the query rather than
   * sent as whatever the arithmetic rolled it over to.
   *
   * A From day after the To day is no range either, and stays idle too: the backend's overlap
   * search does not check the order, and would answer with what was in force on both days. No
   * notice says so — that needs wording the site owner has not been asked for — and swapping the
   * two would answer a question the reader did not ask; the To input's calendar starts at the
   * From day, so only a day typed by hand gets here.
   */
  private readonly historicalResource = httpResource<Publication[]>(
    () => {
      const start = this.startDate();
      const end = this.endDate();
      if (!start || !end) return undefined;
      const timeZone = this.timeZone();
      const from = startOfZonedDayMs(start, timeZone);
      const to = endOfZonedDayMs(end, timeZone);
      if (from !== undefined && to !== undefined && from > to) return undefined;
      return {
        url: '/rest/public/v1/publications',
        params: {
          lang: this.language(),
          externalize: 'false',
          ...(from !== undefined ? { from } : {}),
          ...(to !== undefined ? { to } : {}),
        },
      };
    },
    { defaultValue: [], parse: (raw) => this.api.parse(raw) },
  );

  // A failed read empties its table and says so above it. Keeping the rows it had would leave
  // the previous year's editions under the year just pressed, or the previous language's under
  // the language just chosen, and a reader downloads what they believe is the one they asked for.
  private readonly activePublications = settledValue<Publication[]>(this.activeResource, []);
  private readonly historicalPublications = settledValue<Publication[]>(
    this.historicalResource,
    [],
  );

  readonly activeFailed = computed(() => this.activeResource.status() === 'error');
  readonly historicalFailed = computed(() => this.historicalResource.status() === 'error');

  /**
   * The range whose rows the historical table shows: the one its last settled read was for.
   * The inputs change the moment a shortcut is pressed, while the rows stay those of the range
   * before until the new ones arrive — and are none at all when the read fails — so a shortcut
   * is marked from this, not from the inputs.
   */
  private readonly shownRange = linkedSignal<
    { readonly status: ResourceStatus; readonly start: string; readonly end: string },
    { readonly start: string; readonly end: string } | undefined
  >({
    source: () => ({
      status: this.historicalResource.status(),
      start: this.startDate(),
      end: this.endDate(),
    }),
    computation: (read, previous) => {
      switch (read.status) {
        case 'resolved':
        case 'local':
          return { start: read.start, end: read.end };
        case 'loading':
        case 'reloading':
          return previous?.value;
        default:
          return undefined;
      }
    },
  });

  /** Each row's text in the active language first — a rendering concern, not a fetch. */
  readonly activeRows = computed(() => this.rows(this.activePublications()));
  readonly historicalRows = computed(() => this.rows(this.historicalPublications()));

  private rows(publications: readonly Publication[]): PublicationRow[] {
    const language = this.language();
    return withCategoryHeadings(
      publications.map((publication) => sortPublicationDescs(publication, language)),
    );
  }

  /** The tab set holds no value until aria has resolved one, which the two tabs always do. */
  selectTab(value: string | undefined): void {
    if (value) this.activeTab.set(value);
  }

  /** A date input reports '' when it is cleared and 'YYYY-MM-DD' otherwise. */
  dateValue(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  isSelected(range: { start: string; end: string }): boolean {
    const shown = this.shownRange();
    return shown?.start === range.start && shown?.end === range.end;
  }

  setDateRange(start: string, end: string): void {
    this.startDate.set(start);
    this.endDate.set(end);
  }
}
