import { Component, computed, input, signal } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';

import { Card } from '../../../../shared/components/card/card';
import { Icon } from '../../../../shared/icons/icon';

/** Distinct body ids so each panel's toggle can `aria-controls` its own collapsible region. */
let panelSeq = 0;

/**
 * Titled panel chrome for the editor edit form — the frame around the
 * Preamble / Parts / Misc sections. A thin wrapper over the shared
 * {@link Card}: the card supplies the bordered frame + header band,
 * this component owns the body padding and the sunken-body variant used by the
 * Parts panel (where the part cards sit in a recessed well).
 *
 * The header carries a chevron toggle that collapses the body (local `expanded`
 * signal, default open; the header band stays visible). The toggle exposes
 * `aria-expanded` and `aria-controls` pointing at the body region. An optional
 * `[panelActions]` slot projects controls to the LEFT of the chevron (the Parts
 * panel's dashed "Add part" button).
 *
 * `heading` is a Transloco key — the panel renders it itself (translation-
 * ownership rule: the leaf that renders the text owns `| transloco`), so
 * consumers pass the key, not a pre-translated string. Named `heading` rather
 * than `title` so the static attribute never doubles as the native tooltip /
 * accessible name carrying a raw key.
 *
 * ```html
 * <app-section-panel heading="editor.section.preamble"> … field rows … </app-section-panel>
 * <app-section-panel heading="editor.parts.title" [sunken]="true"> … part cards … </app-section-panel>
 * ```
 */
@Component({
  selector: 'app-section-panel',
  imports: [Card, Icon, TranslocoPipe],
  template: `
    <app-card [header]="heading() | transloco" [padded]="false" sectionClass="shadow-sm mb-4">
      <div cardActions class="flex items-center gap-2">
        <ng-content select="[panelActions]" />
        <button
          type="button"
          class="icon-button"
          [attr.aria-expanded]="expanded()"
          [attr.aria-controls]="bodyId"
          [attr.aria-label]="'editor.section.toggle' | transloco"
          (click)="expanded.set(!expanded())"
        >
          <app-icon
            name="lucideChevronDown"
            class="transition-transform"
            [class.-rotate-90]="!expanded()"
          />
        </button>
      </div>
      <div [id]="bodyId" [class]="bodyClasses()" [hidden]="!expanded()">
        <ng-content />
      </div>
    </app-card>
  `,
  host: { class: 'contents' },
})
export class SectionPanel {
  /** Transloco key for the header band label. */
  readonly heading = input.required<string>();
  /** Recessed body treatment for the Parts panel (part cards sit in a sunken well). */
  readonly sunken = input(false);

  /** Local collapse state; the header stays visible, the body toggles. */
  protected readonly expanded = signal(true);
  /** Stable id linking the toggle's `aria-controls` to the collapsible body. */
  protected readonly bodyId = `ed-section-body-${panelSeq++}`;

  protected readonly bodyClasses = computed(() =>
    this.sunken()
      ? 'bg-surface-100 px-3 pt-3 pb-1 dark:bg-surface-800'
      : 'ed-field-group px-4 py-0.5',
  );
}
