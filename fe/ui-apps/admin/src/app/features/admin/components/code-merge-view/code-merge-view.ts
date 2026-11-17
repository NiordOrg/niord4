import {
  afterNextRender,
  Component,
  DestroyRef,
  effect,
  ElementRef,
  inject,
  input,
  viewChild,
} from '@angular/core';

import type { CodeLanguage, MergeViewHandle } from '../code-text-input/codemirror-editor';

/**
 * Read-only side-by-side diff over two source strings, backed by CodeMirror's
 * merge view (lazy-loaded). Used for the script-resource revision history diff —
 * the two `content` snapshots are FM/JS text, so this is a text diff, not a
 * structural JSON diff.
 */
@Component({
  selector: 'app-code-merge-view',
  imports: [],
  template: `<div #host class="block w-full overflow-auto text-sm"></div>`,
})
export class CodeMergeView {
  readonly original = input.required<string>();
  readonly modified = input.required<string>();
  readonly language = input<CodeLanguage>();

  private readonly host = viewChild.required<ElementRef<HTMLElement>>('host');
  private merge?: MergeViewHandle;
  private destroyed = false;

  constructor() {
    afterNextRender(() => void this.mount());
    inject(DestroyRef).onDestroy(() => {
      this.destroyed = true;
      this.merge?.destroy();
    });

    // Re-mount when the compared snapshots change (selecting a different pair).
    effect(() => {
      const original = this.original();
      const modified = this.modified();
      const language = this.language();
      if (this.merge) {
        void this.remount(original, modified, language);
      }
    });
  }

  private async mount(): Promise<void> {
    const host = this.host().nativeElement;
    try {
      const { createMergeView } = await import('../code-text-input/codemirror-editor');
      const merge = await createMergeView(host, {
        original: this.original(),
        modified: this.modified(),
        language: this.language(),
      });
      if (this.destroyed) {
        merge.destroy();
        return;
      }
      this.merge = merge;
    } catch {
      // Leave the host empty on a load failure rather than crash the dialog.
    }
  }

  private async remount(
    original: string,
    modified: string,
    language: CodeLanguage | undefined,
  ): Promise<void> {
    this.merge?.destroy();
    this.merge = undefined;
    const host = this.host().nativeElement;
    host.replaceChildren();
    const { createMergeView } = await import('../code-text-input/codemirror-editor');
    const merge = await createMergeView(host, { original, modified, language });
    if (this.destroyed) {
      merge.destroy();
      return;
    }
    this.merge = merge;
  }
}
