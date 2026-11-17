import { Component, computed, input } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';

import { Icon } from '../../../../shared/icons/icon';
import type {
  NavtexPriority,
  NavtexPromulgation,
} from '../../../messages/promulgation/navtex-view/navtex-promulgation.model';
import type { PromulgationEditorContext } from '../promulgation-editor.token';

const NAVTEX_PRIORITIES: readonly NavtexPriority[] = ['NONE', 'ROUTINE', 'IMPORTANT', 'VITAL'];

/**
 * NAVTEX channel editor: a read-only preamble, the monospace body with a fixed 40-column guide
 * (a static CSS margin line at column 40 — no code-editor dependency), the deployment-configured
 * transmitter checkboxes (one per key of the `transmitters` map, listed by name), and the priority
 * select (NONE is deliberately included as a selectable option). Generate + Format both
 * round-trip through the shared editor context (facade single write path); enable/disable is the
 * parent field's `<fieldset>` plus the per-type busy flag.
 */
@Component({
  selector: 'app-navtex-edit',
  imports: [TranslocoPipe, Icon],
  template: `
    <div class="grid grid-cols-1 gap-4 md:grid-cols-2">
      <div class="flex flex-col gap-2">
        <textarea
          class="text-input navtex-mono resize-none"
          rows="2"
          readonly
          tabindex="-1"
          [value]="promulgation().preamble ?? ''"
          [placeholder]="'editor.promulgation.navtex.preamble' | transloco"
        ></textarea>
        <textarea
          class="text-input navtex-mono navtex-body"
          rows="6"
          [value]="promulgation().text ?? ''"
          [attr.aria-label]="'editor.promulgation.navtex.body' | transloco"
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

      <div class="flex flex-col gap-2">
        <div class="text-sm font-medium text-fg-subtle">
          {{ 'editor.promulgation.navtex.transmitters' | transloco }}
        </div>
        <div class="flex flex-col gap-1">
          @for (name of transmitterNames(); track name) {
            <label class="inline-flex items-center gap-1.5 text-sm">
              <input
                type="checkbox"
                class="checkbox"
                [checked]="transmitters()[name]"
                (change)="onTransmitter(name, $any($event.target).checked)"
              />
              <span>{{ name }}</span>
            </label>
          }
        </div>

        <div class="mt-2 text-sm font-medium text-fg-subtle">
          {{ 'term.priority' | transloco }}
        </div>
        <select
          class="text-input w-40 text-sm"
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
    </div>
  `,
  styles: [
    `
      .navtex-mono {
        font-family: var(--niord-font-mono);
        font-size: 0.75rem;
      }
      /* Fixed 40-column guide: a 1px margin line at the textarea's left padding (0.625rem) + 40
         monospace columns; painted on the element so it stays put as the text scrolls vertically. */
      .navtex-body {
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
export class NavtexEdit {
  readonly promulgation = input.required<NavtexPromulgation>();
  readonly context = input.required<PromulgationEditorContext>();

  protected readonly priorities = NAVTEX_PRIORITIES;

  protected readonly typeId = computed(() => this.promulgation().type.typeId);
  protected readonly serviceId = computed(() => this.promulgation().type.serviceId);
  protected readonly busy = computed(() => this.context().busy(this.typeId()));
  protected readonly transmitters = computed(() => this.promulgation().transmitters ?? {});
  /** By name, as the transmitters are listed elsewhere: the server's map carries no order. */
  protected readonly transmitterNames = computed(() =>
    Object.keys(this.transmitters()).sort((a, b) => a.localeCompare(b)),
  );
  protected readonly priority = computed<NavtexPriority>(
    () => this.promulgation().priority ?? 'NONE',
  );

  protected onText(text: string): void {
    this.context().patch(this.typeId(), { text });
  }

  protected onTransmitter(name: string, checked: boolean): void {
    this.context().patch(this.typeId(), {
      transmitters: { ...this.transmitters(), [name]: checked },
    });
  }

  protected onPriority(priority: string): void {
    this.context().patch(this.typeId(), { priority: priority as NavtexPriority });
  }
}
