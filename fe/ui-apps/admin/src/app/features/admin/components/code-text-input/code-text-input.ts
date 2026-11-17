import {
  afterNextRender,
  Component,
  DestroyRef,
  effect,
  ElementRef,
  inject,
  input,
  model,
  output,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import { FormValueControl } from '@angular/forms/signals';

import type { CodeEditorHandle, CodeLanguage } from './codemirror-editor';

/**
 * A monospace code editor exposed as a Signal Forms `FormValueControl` over a STRING
 * value. It bakes in NO validation — the consuming form supplies any validators
 * (e.g. a JSON-syntax check).
 *
 * Default (no `language`) is a plain wrapping `<textarea>` and pulls in NO editor
 * library — the pre-existing behaviour, unchanged for current consumers. When a
 * `language` is supplied, a lazy-loaded CodeMirror 6 editor mounts behind the SAME
 * string `value` model; the `<textarea>` stays as the graceful fallback until the
 * editor mounts and if its dynamic import ever fails.
 */
@Component({
  selector: 'app-code-text-input',
  imports: [],
  templateUrl: './code-text-input.html',
  // When `fill` is set the host becomes a growing flex column so the editor
  // stretches to the available height of a flex parent (e.g. a drawer body)
  // instead of sizing to a fixed `rows`.
  host: {
    '[class.flex]': 'fill()',
    '[class.flex-col]': 'fill()',
    '[class.flex-1]': 'fill()',
    '[class.min-h-0]': 'fill()',
  },
})
export class CodeTextInput implements FormValueControl<string> {
  readonly rows = input(8);
  /** Grow to fill the parent's height instead of sizing to `rows`. */
  readonly fill = input(false);
  /** Pre-translated accessible name (the consumer translates). */
  readonly ariaLabel = input<string | undefined>(undefined);
  /** When set, a syntax-aware CodeMirror editor replaces the textarea (lazy-loaded). */
  readonly language = input<CodeLanguage | undefined>(undefined);
  /** Read-only presentation (e.g. a history snapshot) — distinct from disabled form state. */
  readonly readOnly = input(false);

  /** The code — two-way bound by `[formField]`, or `[value]`/`(valueChange)`. */
  readonly value = model<string>('');
  /** Set by the form when the field is disabled. */
  readonly disabled = input(false);
  /** Emitted on blur for Signal Forms touched-tracking. */
  readonly touch = output<void>();
  /** True once CodeMirror has mounted; until then (and on load failure) the textarea is shown. */
  protected readonly editorReady = signal(false);

  private readonly host = viewChild<ElementRef<HTMLElement>>('host');

  private editor?: CodeEditorHandle;
  /** Set in the destroy hook so an in-flight async mount can tear itself down. */
  private destroyed = false;
  /** Last language pushed to the editor — guards the effect against redundant re-imports. */
  private appliedLanguage: CodeLanguage | undefined;
  /** Last value the editor holds — guards the value effect against echoing a user edit back. */
  private lastEditorValue = '';

  constructor() {
    afterNextRender(() => {
      if (this.language() !== undefined) {
        void this.mountEditor();
      }
    });
    inject(DestroyRef).onDestroy(() => {
      this.destroyed = true;
      this.editor?.destroy();
    });

    // Push an external value write (form/`[value]`) into the editor. The guard skips
    // the echo from a user edit, which already set `value` from the editor's onChange.
    effect(() => {
      const v = this.value();
      untracked(() => {
        if (v === this.lastEditorValue) {
          return;
        }
        this.lastEditorValue = v;
        this.editor?.setValue(v);
      });
    });

    // Reflect a language change into the mounted editor (skip a no-op re-apply).
    effect(() => {
      const language = this.language();
      if (this.editor && language !== this.appliedLanguage) {
        this.appliedLanguage = language;
        this.editor.setLanguage(language);
      }
    });

    // Reflect read-only / disabled into the editor's editability (cheap; no import).
    effect(() => {
      const editable = !this.readOnly() && !this.disabled();
      this.editor?.setEditable(editable);
    });
  }

  private async mountEditor(): Promise<void> {
    const host = this.host()?.nativeElement;
    if (!host || this.editor) {
      return;
    }
    try {
      const { createCodeEditor } = await import('./codemirror-editor');
      const editor = await createCodeEditor(host, {
        doc: this.value(),
        language: this.language(),
        editable: !this.readOnly() && !this.disabled(),
        ariaLabel: this.ariaLabel(),
        onChange: (value) => {
          this.lastEditorValue = value;
          this.value.set(value);
        },
        onBlur: () => this.touch.emit(),
      });
      // Destroyed mid-mount: tear the freshly created view down, never adopt it.
      if (this.destroyed) {
        editor.destroy();
        return;
      }
      // Close the in-flight-write window — seed from the latest value.
      this.lastEditorValue = this.value();
      editor.setValue(this.value());
      this.appliedLanguage = this.language();
      this.editor = editor;
      this.editorReady.set(true);
    } catch {
      // Any load/mount failure leaves the textarea fallback in place.
      this.editorReady.set(false);
    }
  }

  protected onInput(event: Event): void {
    const value = (event.target as HTMLTextAreaElement).value;
    this.lastEditorValue = value;
    this.value.set(value);
  }
}
