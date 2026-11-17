import { Component, input, model, output, signal } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';

import { Dialog } from '../../../../shared/components/dialog/dialog';
import type { RichTextLinkState } from '../../../../shared/forms/rich-text-editor/rich-text-editor';

/**
 * Insert / edit hyperlink dialog for the Publication editors — an anchor editor
 * (URL, Text to display, Title, Target), and a distinct feature from the Insert-Publications
 * dialog. The invoking {@link RichTextEditor} hands its anchor-at-selection state in through
 * `[state]`; on save/remove the dialog emits the edited {@link RichTextLinkState} and the host calls
 * the editor's `applyLink`. A blank URL (or Remove) clears the link.
 */
@Component({
  selector: 'app-link-dialog',
  imports: [TranslocoPipe, Dialog],
  template: `
    <app-dialog
      [visible]="visible()"
      (visibleChange)="visible.set($event)"
      (opened)="onOpened()"
      [modal]="true"
      [header]="'editor.dialog.link.header' | transloco"
      [style]="{ width: '32rem' }"
    >
      <div class="flex flex-col gap-3">
        <label class="flex flex-col gap-1">
          <span class="text-sm text-fg-muted">{{ 'editor.dialog.link.url' | transloco }}</span>
          <input
            type="url"
            class="w-full text-input"
            [value]="url()"
            (input)="url.set($any($event.target).value)"
            [attr.aria-label]="'editor.dialog.link.url' | transloco"
            autocomplete="off"
          />
        </label>

        <label class="flex flex-col gap-1">
          <span class="text-sm text-fg-muted">{{ 'editor.dialog.link.text' | transloco }}</span>
          <input
            type="text"
            class="w-full text-input"
            [value]="text()"
            (input)="text.set($any($event.target).value)"
            [attr.aria-label]="'editor.dialog.link.text' | transloco"
            autocomplete="off"
          />
        </label>

        <label class="flex flex-col gap-1">
          <span class="text-sm text-fg-muted">{{ 'editor.dialog.link.title' | transloco }}</span>
          <input
            type="text"
            class="w-full text-input"
            [value]="title()"
            (input)="title.set($any($event.target).value)"
            [attr.aria-label]="'editor.dialog.link.title' | transloco"
            autocomplete="off"
          />
        </label>

        <label class="flex flex-col gap-1">
          <span class="text-sm text-fg-muted">{{ 'editor.dialog.link.target' | transloco }}</span>
          <select
            class="w-full text-input"
            [attr.aria-label]="'editor.dialog.link.target' | transloco"
            (change)="target.set($any($event.target).value)"
          >
            <option value="" [selected]="target() === ''">
              {{ 'editor.dialog.link.sameWindow' | transloco }}
            </option>
            <option value="_blank" [selected]="target() === '_blank'">
              {{ 'editor.dialog.link.newWindow' | transloco }}
            </option>
          </select>
        </label>
      </div>

      <ng-template #footer>
        <button type="button" class="btn-tertiary" (click)="visible.set(false)">
          {{ 'term.cancel' | transloco }}
        </button>
        @if (state().href) {
          <button type="button" class="btn-danger" (click)="remove()">
            {{ 'editor.dialog.link.remove' | transloco }}
          </button>
        }
        <button type="button" class="btn-primary" [disabled]="!url().trim()" (click)="save()">
          {{ 'editor.dialog.link.save' | transloco }}
        </button>
      </ng-template>
    </app-dialog>
  `,
  host: { class: 'contents' },
})
export class LinkDialog {
  readonly visible = model(false);
  /** The anchor state at the invoking editor's selection (prefill). */
  readonly state = input<RichTextLinkState>({ href: '', text: '', title: '', target: '' });
  /** The edited link (blank `href` ⇒ remove); the host applies it to the invoking editor. */
  readonly confirmed = output<RichTextLinkState>();

  protected readonly url = signal('');
  protected readonly text = signal('');
  protected readonly title = signal('');
  protected readonly target = signal('');

  protected onOpened(): void {
    const state = this.state();
    this.url.set(state.href);
    this.text.set(state.text);
    this.title.set(state.title);
    this.target.set(state.target);
  }

  protected save(): void {
    this.confirmed.emit({
      href: this.url().trim(),
      text: this.text(),
      title: this.title().trim(),
      target: this.target(),
    });
    this.visible.set(false);
  }

  protected remove(): void {
    this.confirmed.emit({ href: '', text: '', title: '', target: '' });
    this.visible.set(false);
  }
}
