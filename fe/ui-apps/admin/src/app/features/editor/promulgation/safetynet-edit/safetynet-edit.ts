import { Component, computed, input } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';

import { Icon } from '../../../../shared/icons/icon';
import type {
  SafetynetPriority,
  SafetynetPromulgation,
} from '../../../messages/promulgation/safetynet-view/safetynet-promulgation.model';
import type { PromulgationEditorContext } from '../promulgation-editor.token';

// The editor offers SAFETY / URGENCY only; the model keeps DISTRESS for round-trip.
const SAFETYNET_PRIORITIES: readonly SafetynetPriority[] = ['SAFETY', 'URGENCY'];

/**
 * SafetyNET channel editor: the broadcast-area radio list, the monospace body, the priority select
 * (SAFETY/URGENCY), and the SafetyNET ID field shown only on a PUBLISHED message (the authority
 * assigns it after publication). A red "Undefined" indicator flags a promulgating channel with no
 * area. Generate + Format round-trip through the shared editor context;
 * enable/disable is the parent field's `<fieldset>` plus the per-type busy flag.
 */
@Component({
  selector: 'app-safetynet-edit',
  imports: [TranslocoPipe, Icon],
  template: `
    <div class="grid grid-cols-1 gap-4 md:grid-cols-2">
      <div class="flex flex-col gap-2">
        <textarea
          class="text-input safetynet-mono safetynet-body"
          rows="6"
          [value]="promulgation().text ?? ''"
          [attr.aria-label]="'editor.promulgation.safetynet.body' | transloco"
          (input)="onText($any($event.target).value)"
        ></textarea>
        <div class="flex gap-2">
          <button
            type="button"
            class="btn-secondary text-xs"
            [disabled]="busy()"
            (click)="context().generate(typeId())"
          >
            @if (busy()) {
              <app-icon name="lucideLoaderCircle" [spin]="true" />
            } @else {
              <app-icon name="lucideRefreshCw" />
            }
            <span>{{ 'term.generate' | transloco }}</span>
          </button>
          <button
            type="button"
            class="btn-secondary text-xs"
            [disabled]="busy()"
            (click)="context().reformat(serviceId(), typeId())"
          >
            <app-icon name="lucideAlignLeft" />
            <span>{{ 'term.format' | transloco }}</span>
          </button>
        </div>
      </div>

      <div class="flex flex-col gap-3">
        <div class="flex flex-col gap-1.5">
          <span class="text-sm font-medium text-fg-subtle">
            {{ 'editor.promulgation.safetynet.area' | transloco }}
          </span>
          <div class="flex flex-wrap items-center gap-x-4 gap-y-1">
            @for (area of areas(); track area.name) {
              <label class="inline-flex items-center gap-1.5 text-sm">
                <input
                  type="radio"
                  class="radio"
                  [name]="'safetynet-area-' + typeId()"
                  [checked]="promulgation().areaName === area.name"
                  (change)="onArea(area.name)"
                />
                <span>{{ area.name }}</span>
              </label>
            }
            @if (areaUndefined()) {
              <span class="text-sm text-danger">{{
                'editor.promulgation.safetynet.areaUndefined' | transloco
              }}</span>
            }
          </div>
        </div>

        <div class="flex flex-col gap-1.5">
          <span class="text-sm font-medium text-fg-subtle">
            {{ 'term.priority' | transloco }}
          </span>
          <select
            class="text-input w-48 text-sm"
            [attr.aria-label]="'term.priority' | transloco"
            (change)="onPriority($any($event.target).value)"
          >
            @for (option of priorities; track option) {
              <option [value]="option" [selected]="priority() === option">
                {{ 'editor.promulgation.priority.' + option | transloco }}
              </option>
            }
          </select>
        </div>

        @if (showId()) {
          <div class="flex flex-col gap-1.5">
            <span class="text-sm font-medium text-fg-subtle">
              {{ 'editor.promulgation.safetynet.id.label' | transloco }}
            </span>
            <input
              type="text"
              class="text-input w-48 text-sm"
              [value]="promulgation().safetyNetId ?? ''"
              [title]="'editor.promulgation.safetynet.id.tooltip' | transloco"
              [attr.aria-label]="'editor.promulgation.safetynet.id.label' | transloco"
              (input)="onId($any($event.target).value)"
            />
          </div>
        }
      </div>
    </div>
  `,
  styles: [
    `
      .safetynet-mono {
        font-family: var(--niord-font-mono);
        font-size: 0.75rem;
      }
      /* Fixed 40-column guide — the SafetyNET body shares the same column-40 margin line as the
         NAVTEX body. */
      .safetynet-body {
        background-image: linear-gradient(
          to right,
          transparent calc(0.625rem + 40ch),
          var(--niord-border) calc(0.625rem + 40ch),
          var(--niord-border) calc(0.625rem + 40ch + 1px),
          transparent calc(0.625rem + 40ch + 1px)
        );
        background-repeat: no-repeat;
      }
    `,
  ],
})
export class SafetynetEdit {
  readonly promulgation = input.required<SafetynetPromulgation>();
  readonly context = input.required<PromulgationEditorContext>();

  protected readonly priorities = SAFETYNET_PRIORITIES;

  protected readonly typeId = computed(() => this.promulgation().type.typeId);
  protected readonly serviceId = computed(() => this.promulgation().type.serviceId);
  protected readonly busy = computed(() => this.context().busy(this.typeId()));
  protected readonly areas = computed(() => this.promulgation().areas ?? []);
  protected readonly priority = computed(() => this.promulgation().priority);
  protected readonly showId = computed(() => this.context().messageStatus() === 'PUBLISHED');

  /** A promulgating channel with no chosen area and no offered areas. */
  protected readonly areaUndefined = computed<boolean>(() => {
    const p = this.promulgation();
    return !!p.promulgate && !p.areaName && (p.areas === undefined || p.areas.length === 0);
  });

  protected onText(text: string): void {
    this.context().patch(this.typeId(), { text });
  }

  protected onArea(areaName: string): void {
    this.context().patch(this.typeId(), { areaName });
  }

  protected onPriority(priority: string): void {
    this.context().patch(this.typeId(), { priority: priority as SafetynetPriority });
  }

  protected onId(safetyNetId: string): void {
    this.context().patch(this.typeId(), { safetyNetId });
  }
}
