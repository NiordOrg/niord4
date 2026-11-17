import { Component, ElementRef, input, output, viewChild } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';
import { Area } from '../../../../core/models/message.model';
import { MessageAreaNameComponent } from '../message-detail-card/message-area-name.component';
import { Icon } from '../../../../shared/icons/icon';
import { publishHeight } from '../../../../shared/utils/publish-height';

@Component({
  selector: 'app-message-filter',
  imports: [TranslocoPipe, MessageAreaNameComponent, Icon],
  // The host draws no box of its own, so the bar is pinned against the whole message view it
  // filters and leaves with the end of it.
  host: { class: 'contents' },
  template: `
    <div #bars class="filter-bar bg-surface-200 text-center text-fg">
      <div class="border-b border-surface-300">
        <div class="mx-auto flex flex-wrap items-center justify-center gap-1 py-1">
          <!-- A publication is browsed as the list it was published with, so the chips that
               choose the current list's scope are left out while one is: pressed, they would
               change nothing on screen. -->
          <!-- The list is always of one main type or both: the chip that is left on cannot be
               turned off, and says so to a screen reader while it stays pressed to the eye; the
               pointer says it to a reader who can see it. -->
          @if (!browsingPublication()) {
            <button
              type="button"
              class="chip px-2.5 text-xs aria-disabled:cursor-not-allowed"
              [class.is-active]="mainTypes().NW"
              [attr.aria-pressed]="mainTypes().NW"
              [attr.aria-disabled]="isLastOn('NW') ? 'true' : null"
              (click)="toggleMainType('NW')"
            >
              {{ 'MENU_NW' | transloco }}
            </button>
            <button
              type="button"
              class="chip px-2.5 text-xs aria-disabled:cursor-not-allowed"
              [class.is-active]="mainTypes().NM"
              [attr.aria-pressed]="mainTypes().NM"
              [attr.aria-disabled]="isLastOn('NM') ? 'true' : null"
              (click)="toggleMainType('NM')"
            >
              {{ 'MENU_NM' | transloco }}
            </button>

            <span
              aria-hidden="true"
              class="mx-5 my-1 self-stretch border-l border-dotted border-surface-400"
            ></span>

            <button
              type="button"
              class="chip px-2.5 text-xs"
              [class.is-active]="activeNow()"
              [attr.aria-pressed]="activeNow()"
              (click)="activeNowChange.emit()"
            >
              {{ 'ACTIVE_NOW' | transloco }}
            </button>

            <span
              aria-hidden="true"
              class="mx-5 my-1 self-stretch border-l border-dotted border-surface-400"
            ></span>
          }

          <!-- Printing is an action, not a state, so this one carries no aria-pressed. It is
               offered only while the list on screen is a list the backend can be asked for. -->
          <button
            type="button"
            class="chip px-2.5 text-xs"
            [disabled]="!canPrint()"
            (click)="printRequested.emit()"
          >
            <app-icon name="lucidePrinter" />
            {{ 'MENU_PRINT' | transloco }}
          </button>
        </div>
      </div>

      @if (browsingPublication() ? subAreas().length > 0 : areaRoots().length > 0) {
        <div class="border-b border-surface-300">
          <div class="mx-auto flex flex-wrap items-center justify-center gap-1 py-1">
            @if (areaRoots().length > 1 && !browsingPublication()) {
              @for (area of areaRoots(); track area.id) {
                <button
                  type="button"
                  class="chip px-2.5 text-xs"
                  [class.is-active]="rootArea()?.id === area.id"
                  [attr.aria-pressed]="rootArea()?.id === area.id"
                  (click)="rootAreaChange.emit(area)"
                >
                  <app-message-area-name [area]="area" [lineage]="false" />
                </button>
              }
            }

            @if (
              areaRoots().length > 1 &&
              !browsingPublication() &&
              rootArea() &&
              subAreas().length > 0
            ) {
              <span
                aria-hidden="true"
                class="mx-5 my-1 self-stretch border-l border-dotted border-surface-400"
              ></span>
            }

            @if ((rootArea() || browsingPublication()) && subAreas().length > 0) {
              @for (area of subAreas(); track area.id) {
                <button
                  type="button"
                  class="chip px-2.5 text-xs"
                  [class.is-active]="subAreaSelection().has(area.id)"
                  [attr.aria-pressed]="subAreaSelection().has(area.id)"
                  (click)="subAreaToggle.emit(area)"
                >
                  <app-message-area-name [area]="area" [lineage]="false" />
                </button>
              }
            }
          </div>
        </div>
      }
    </div>
  `,
})
export class MessageFilterComponent {
  private readonly bars = viewChild.required<ElementRef<HTMLElement>>('bars');

  constructor() {
    // The height is not a constant: the bar grows by a row whenever the country's area chips
    // wrap, which happens at every screen width somewhere. The map view is sized against it and
    // the scroll padding keeps focus clear of it, so it is measured and published.
    publishHeight(this.bars, '--niord-filter-bar-height');
  }

  areaRoots = input<readonly Area[]>([]);
  rootArea = input<Area | undefined>(undefined);
  subAreas = input<readonly Area[]>([]);
  /** The ticked sub-areas, by id. Which chips are on is the route's state, not the areas'. */
  subAreaSelection = input<ReadonlySet<number>>(new Set<number>());
  mainTypes = input<{ NW: boolean; NM: boolean }>({ NW: true, NM: true });
  activeNow = input(false);
  /** Whether the list the bar filters can be printed — the print chip is inert while it is not. */
  canPrint = input(true);
  /**
   * Whether the list is one publication's messages. The main types, active-now and the root
   * area are not offered then; printing and the sub-areas the publication's messages fall in are.
   */
  browsingPublication = input(false);

  rootAreaChange = output<Area>();
  mainTypesChange = output<{ NW: boolean; NM: boolean }>();
  activeNowChange = output<void>();
  subAreaToggle = output<Area>();
  printRequested = output<void>();

  toggleMainType(type: 'NW' | 'NM'): void {
    if (this.isLastOn(type)) return;
    const current = this.mainTypes();
    this.mainTypesChange.emit({ ...current, [type]: !current[type] });
  }

  /** Whether this is the one main type the list is still of, which cannot be turned off. */
  protected isLastOn(type: 'NW' | 'NM'): boolean {
    const current = this.mainTypes();
    return current[type] && !current[type === 'NW' ? 'NM' : 'NW'];
  }
}
