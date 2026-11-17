import { Component, DestroyRef, ElementRef, inject, input, viewChild } from '@angular/core';
import { DialogRef, type CdkDialogContainer } from '@angular/cdk/dialog';
import { TranslocoPipe } from '@jsverse/transloco';

import { Icon } from '../../icons/icon';

let dialogSeq = 0;

/**
 * The chrome every dialog of this site wears: a header band carrying the title and the close
 * control, a body that scrolls inside the panel, and an optional footer the caller projects as
 * a `<footer class="dialog-foot">` element. The `.dialog-*` classes are the shared Layer-3 set,
 * so a dialog here and a dialog in the administration application are the same object.
 *
 * Everything around the panel belongs to `@angular/cdk/dialog`'s `Dialog` service: the scrim,
 * the focus trap, `role="dialog"` with `aria-modal`, the Escape key and returning focus to
 * whatever opened the dialog. This component therefore owns only the inside of the panel, and a
 * dialog component's whole template is:
 *
 *     <app-dialog-frame [title]="…">
 *       …body…
 *       <footer class="dialog-foot">…</footer>
 *     </app-dialog-frame>
 *
 * The title is rendered verbatim: the caller passes an already-translated string. It is the
 * dialog's accessible name, so a caller never leaves it empty. It is also where a dialog puts
 * focus when the control that had it goes away, which is why it takes focus from a script and
 * never from the Tab key.
 */
@Component({
  selector: 'app-dialog-frame',
  imports: [Icon, TranslocoPipe],
  template: `
    <div class="dialog-panel">
      <header class="dialog-header">
        <h2 #heading [id]="titleId" tabindex="-1" class="dialog-title">{{ title() }}</h2>
        <button
          type="button"
          class="icon-button"
          [attr.aria-label]="'BTN_CLOSE' | transloco"
          (click)="close()"
        >
          <app-icon name="lucideX" />
        </button>
      </header>
      <div class="dialog-body"><ng-content /></div>
      <ng-content select="footer" />
    </div>
  `,
  host: { class: 'contents' },
})
export class DialogFrame {
  private readonly ref = inject<DialogRef<unknown>>(DialogRef, { optional: true });

  readonly title = input('');

  protected readonly titleId = `info-dialog-title-${dialogSeq++}`;

  private readonly heading = viewChild.required<ElementRef<HTMLElement>>('heading');

  constructor() {
    // The element that names the dialog is in here, but the element that carries the name is
    // the CDK's container above it, so the heading registers its id with the queue that
    // container keeps for exactly this. `containerInstance` is typed as the structural minimum
    // a container must satisfy; this site never substitutes one, so it is the CDK's own. A
    // frame rendered outside a dialog has no container and simply skips it.
    const container = this.ref?.containerInstance as CdkDialogContainer | undefined;
    if (container) {
      container._addAriaLabelledBy(this.titleId);
      inject(DestroyRef).onDestroy(() => container._removeAriaLabelledBy(this.titleId));
    }
  }

  /**
   * Puts focus on the title. A dialog calls it when the control the reader used is about to
   * leave the page — without it focus would drop to the document behind the dialog, and a
   * screen reader would lose its place.
   */
  focusTitle(): void {
    this.heading().nativeElement.focus();
  }

  protected close(): void {
    this.ref?.close();
  }
}
