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
import { translateSignal, TranslocoPipe } from '@jsverse/transloco';

import { Icon } from '../../icons/icon';
import type { RichTextEditorHandle, RichTextPreset } from './rich-text-engine';

/** A `{ value, labelKey }` option for a toolbar `<select>`; the label is a Transloco key. */
interface ToolbarOption {
  readonly value: string;
  readonly labelKey: string;
}

const PARAGRAPH_STYLES: readonly ToolbarOption[] = [
  { value: 'p', labelKey: 'shared.richText.paragraph.normal' },
  { value: 'h1', labelKey: 'shared.richText.paragraph.h1' },
  { value: 'h2', labelKey: 'shared.richText.paragraph.h2' },
  { value: 'h3', labelKey: 'shared.richText.paragraph.h3' },
  { value: 'h4', labelKey: 'shared.richText.paragraph.h4' },
];

// The message-content class model. Values are the exact class strings the sanitizer keeps.
const TABLE_CLASSES: readonly ToolbarOption[] = [
  { value: '', labelKey: 'shared.richText.tableClass.none' },
  { value: 'no-border', labelKey: 'shared.richText.tableClass.noBorder' },
  { value: 'condensed', labelKey: 'shared.richText.tableClass.condensed' },
  { value: 'no-border condensed', labelKey: 'shared.richText.tableClass.noBorderCondensed' },
  { value: 'positions', labelKey: 'shared.richText.tableClass.positions' },
  { value: 'position-table', labelKey: 'shared.richText.tableClass.positionTable' },
];

const CELL_CLASSES: readonly ToolbarOption[] = [
  { value: '', labelKey: 'shared.richText.cellClass.none' },
  { value: 'underline', labelKey: 'shared.richText.cellClass.underline' },
  { value: 'pos-col', labelKey: 'shared.richText.cellClass.posCol' },
  { value: 'pos-index', labelKey: 'shared.richText.cellClass.posIndex' },
];

const ROW_CLASSES: readonly ToolbarOption[] = [
  { value: '', labelKey: 'shared.richText.rowClass.none' },
  { value: 'underline', labelKey: 'shared.richText.rowClass.underline' },
];

/**
 * Shared TipTap rich-text control. Exposed as a Signal Forms `FormValueControl<string>`
 * over an HTML string, and equally usable standalone via `[value]`/`(valueChange)` for
 * facade-driven hosts. TipTap is loaded LAZILY (dynamic `import('./rich-text-engine')`
 * on first mount) so no `@tiptap/*` code lands in the initial bundle.
 *
 * Three presets: `full` (Description — full toolbar + table class model + the
 * `.message-details-html` WYSIWYG surface), `inline` (Publication — inline-only schema
 * with no root `<p>`, minimal toolbar, preserves arbitrary inline attributes including the
 * server's `publication=<id>`), and `basic` (Comments — standard formatting, no niord
 * buttons, no content CSS).
 *
 * SINGLE-WRITE-PATH RULE: programmatic writes (the `value` input, the initial seed) NEVER
 * emit `valueChange`; only genuine user edits do. Feature insert buttons are projected in
 * via `[richTextTools]` and drive {@link insertContentAtCursor} / the image seam — the
 * shared control carries no feature knowledge.
 */
/** The anchor state at the selection, exchanged with a host that owns the Insert/Edit-link modal. */
export interface RichTextLinkState {
  readonly href: string;
  readonly text: string;
  readonly title: string;
  readonly target: string;
}

@Component({
  selector: 'app-rich-text-editor',
  imports: [Icon, TranslocoPipe],
  templateUrl: './rich-text-editor.html',
  styleUrl: './rich-text-editor.css',
  host: {
    class: 'block',
    '[class.rte-fullscreen]': 'fullscreen()',
    '[class.rte-inline]': "preset() === 'inline'",
    '[class.rte-full]': "preset() === 'full'",
    '[class.rte-resized]': 'userResized()',
  },
})
export class RichTextEditor implements FormValueControl<string> {
  /** Which schema + toolbar to present. */
  readonly preset = input<RichTextPreset>('full');
  /** The HTML — two-way via `[formField]`, or `[value]`/`(valueChange)`. */
  readonly value = model<string>('');
  /** Set by the form when the field is disabled; makes the contenteditable non-editable. */
  readonly disabled = input(false);
  /** Pre-translated accessible name for the editing surface (the host translates). */
  readonly label = input('');
  /** Passed through to the contenteditable's `tabindex`. */
  readonly tabIndex = input<number | undefined>(undefined);
  /** Emitted on blur for Signal Forms touched-tracking. */
  readonly touch = output<void>();
  /**
   * The image toolbar button emits this instead of opening a picker — the HOST owns the
   * attachment grid and calls {@link insertContentAtCursor} with the chosen `<img>`.
   */
  readonly imageRequest = output<void>();
  /**
   * Opt-in for a host-owned Insert/Edit-link MODAL (used by the Publication editor). When set, the
   * link toolbar button emits {@link linkEdit} with the anchor state at the selection instead of
   * running the built-in URL prompt; the host opens its dialog and calls {@link applyLink} with the
   * result.
   */
  readonly linkModal = input(false);
  readonly linkEdit = output<RichTextLinkState>();

  protected readonly editorReady = signal(false);
  /** Set once the user grabs the full-preset resize handle — lifts the surface's default height cap. */
  protected readonly userResized = signal(false);
  protected readonly codeView = signal(false);
  /**
   * The HTML shown in the code-view textarea. Seeded from `value()` on entering code view and
   * written through on every keystroke. `formatCode()` pretty-prints INTO this signal only, leaving
   * `value()` (the persisted HTML) untouched until the user actually edits — so "Format" is a
   * read-time convenience, not a mutation of the saved content.
   */
  protected readonly codeDisplay = signal('');
  protected readonly fullscreen = signal(false);
  // Bumped on every editor transaction/selection so toolbar active-state getters recompute.
  private readonly stateVersion = signal(0);

  protected readonly paragraphStyles = PARAGRAPH_STYLES;
  protected readonly tableClasses = TABLE_CLASSES;
  protected readonly cellClasses = CELL_CLASSES;
  protected readonly rowClasses = ROW_CLASSES;

  private readonly linkPromptLabel = translateSignal('shared.richText.linkPrompt');

  private readonly surface = viewChild<ElementRef<HTMLElement>>('surface');

  private handle?: RichTextEditorHandle;
  private heightObserver?: MutationObserver;
  private destroyed = false;
  // True while a programmatic write is being applied — suppresses the change echo.
  private applying = false;
  // Last value we emitted or seeded (normalized) — guards the value effect against re-seeding
  // the editor with the user's own echo (which would reset the cursor).
  private lastValueOut = '';

  constructor() {
    afterNextRender(() => void this.mountEditor());

    inject(DestroyRef).onDestroy(() => {
      this.destroyed = true;
      this.heightObserver?.disconnect();
      this.handle?.destroy();
    });

    // Push an external value write into the editor without emitting. Skips the echo of a
    // user edit (which already set `value` from the editor's own onChange).
    effect(() => {
      const next = this.value() || '';
      untracked(() => {
        if (!this.handle || next === this.lastValueOut) {
          return;
        }
        this.applying = true;
        this.handle.setValue(next);
        this.applying = false;
        this.lastValueOut = this.handle.getValue();
      });
    });

    effect(() => {
      const disabled = this.disabled();
      untracked(() => this.handle?.setEditable(!disabled));
    });

    effect(() => {
      const label = this.label();
      const tab = this.tabIndex();
      untracked(() => this.handle?.setSurfaceAttributes(label || undefined, tab));
    });
  }

  private async mountEditor(): Promise<void> {
    const host = this.surface()?.nativeElement;
    if (!host || this.handle) {
      return;
    }
    try {
      const { createRichTextEditor } = await import('./rich-text-engine');
      // Destroyed before the import resolved — never touch the DOM.
      if (this.destroyed) {
        return;
      }
      const handle = createRichTextEditor({
        element: host,
        preset: this.preset(),
        content: this.value() || '',
        editable: !this.disabled(),
        ariaLabel: this.label() || undefined,
        tabindex: this.tabIndex(),
        onChange: () => this.onEditorChange(),
        onSelectionChange: () => this.stateVersion.update((v) => v + 1),
        onBlur: () => this.touch.emit(),
      });
      if (this.destroyed) {
        handle.destroy();
        return;
      }
      // Close the seed window — replay the latest value (it may have changed mid-import).
      this.applying = true;
      handle.setValue(this.value() || '');
      this.applying = false;
      this.lastValueOut = handle.getValue();
      this.handle = handle;
      this.editorReady.set(true);
      // Lift the full surface's height cap the moment the user drags the grabber. We observe the
      // resulting inline `style.height` write rather than the pointer, so the cap lifts only AFTER a
      // height is already in place — never leaving the surface briefly uncapped (which would flash it
      // to full content height and back at the start of a drag).
      if (this.preset() === 'full') {
        const pm = host.querySelector<HTMLElement>('.ProseMirror');
        if (pm) {
          this.heightObserver = new MutationObserver(() => {
            if (pm.style.height) {
              this.userResized.set(true);
              this.heightObserver?.disconnect();
              this.heightObserver = undefined;
            }
          });
          this.heightObserver.observe(pm, { attributes: true, attributeFilter: ['style'] });
        }
      }
    } catch {
      // A load/mount failure leaves the surface empty; the code-view textarea still works.
      this.editorReady.set(false);
    }
  }

  private onEditorChange(): void {
    this.stateVersion.update((v) => v + 1);
    if (this.applying) {
      return;
    }
    const html = this.handle?.getValue() ?? '';
    if (html === this.lastValueOut) {
      return;
    }
    this.lastValueOut = html;
    this.value.set(html);
  }

  // ---- Command surface for projected feature buttons ----------------------------------

  /** Insert HTML at the current cursor (used by the host image picker + insert dialogs). */
  insertContentAtCursor(html: string): void {
    // `preserveWhitespace: false` collapses the backend template's indentation/newlines the same way
    // the seed (`setContent`) path does — otherwise `insertContent` forces `preserveWhitespace:'full'`
    // and the inserted geometry position lists / locations arrive wrapped in blank paragraphs and
    // broken one-position-per-line. The intended inline separators (`,&nbsp;`) are preserved.
    this.handle?.editor
      .chain()
      .focus()
      .insertContent(html, { parseOptions: { preserveWhitespace: false } })
      .run();
  }

  /** The `publication` id on the mark/node under the selection (Insert-Publications EDIT mode). */
  publicationIdAtSelection(): string | undefined {
    const editor = this.handle?.editor;
    if (!editor) {
      return undefined;
    }
    const link = editor.getAttributes('link')['publication'];
    if (link) {
      return String(link);
    }
    const span = editor.getAttributes('publicationSpan')['publication'];
    return span ? String(span) : undefined;
  }

  // ---- Toolbar state (all read `stateVersion` first so they track editor changes) ------

  protected isActive(
    nameOrAttrs: string | Record<string, unknown>,
    attrs?: Record<string, unknown>,
  ): boolean {
    this.stateVersion();
    const editor = this.handle?.editor;
    if (!editor) {
      return false;
    }
    return typeof nameOrAttrs === 'string'
      ? editor.isActive(nameOrAttrs, attrs)
      : editor.isActive(nameOrAttrs);
  }

  protected inTable(): boolean {
    this.stateVersion();
    return this.handle?.editor.isActive('table') ?? false;
  }

  protected currentParagraphStyle(): string {
    this.stateVersion();
    const editor = this.handle?.editor;
    if (!editor) {
      return 'p';
    }
    for (const level of [1, 2, 3, 4] as const) {
      if (editor.isActive('heading', { level })) {
        return `h${level}`;
      }
    }
    return 'p';
  }

  protected currentTableClass(): string {
    this.stateVersion();
    return (this.handle?.editor.getAttributes('table')['class'] as string) ?? '';
  }

  protected currentCellClass(): string {
    this.stateVersion();
    return (this.handle?.editor.getAttributes('tableCell')['class'] as string) ?? '';
  }

  protected currentRowClass(): string {
    this.stateVersion();
    return (this.handle?.editor.getAttributes('tableRow')['class'] as string) ?? '';
  }

  protected currentColor(): string {
    this.stateVersion();
    return (this.handle?.editor.getAttributes('textStyle')['color'] as string) ?? '#000000';
  }

  // ---- Toolbar commands ----------------------------------------------------------------

  /**
   * Keep the editing surface focused through toolbar BUTTON clicks — without this the button
   * steals focus on mousedown, the selection collapses and follow-up typing goes nowhere.
   * Selects and the color input are exempt: they need focus to open.
   */
  protected onToolbarMousedown(event: MouseEvent): void {
    if (event.target instanceof Element && event.target.closest('button')) {
      event.preventDefault();
    }
  }

  protected toggleBold(): void {
    this.handle?.editor.chain().focus().toggleBold().run();
  }

  protected toggleItalic(): void {
    this.handle?.editor.chain().focus().toggleItalic().run();
  }

  protected toggleBulletList(): void {
    this.handle?.editor.chain().focus().toggleBulletList().run();
  }

  protected toggleOrderedList(): void {
    this.handle?.editor.chain().focus().toggleOrderedList().run();
  }

  protected setAlign(align: 'left' | 'center' | 'right' | 'justify'): void {
    this.handle?.editor.chain().focus().setTextAlign(align).run();
  }

  protected indent(): void {
    this.handle?.editor.chain().focus().sinkListItem('listItem').run();
  }

  protected outdent(): void {
    this.handle?.editor.chain().focus().liftListItem('listItem').run();
  }

  protected toggleHighlight(): void {
    this.handle?.editor.chain().focus().toggleHighlight().run();
  }

  protected toggleNoWrap(): void {
    this.handle?.editor.chain().focus().toggleMark('noWrap').run();
  }

  protected setColor(event: Event): void {
    const color = (event.target as HTMLInputElement).value;
    this.handle?.editor.chain().focus().setColor(color).run();
  }

  protected onParagraphStyle(event: Event): void {
    const value = (event.target as HTMLSelectElement).value;
    const editor = this.handle?.editor;
    if (!editor) {
      return;
    }
    if (value === 'p') {
      editor.chain().focus().setParagraph().run();
      return;
    }
    const level = Number(value.slice(1)) as 1 | 2 | 3 | 4;
    editor.chain().focus().setHeading({ level }).run();
  }

  protected setLink(): void {
    const editor = this.handle?.editor;
    if (!editor) {
      return;
    }
    // Host-owned modal (Publication editor): extend to the whole anchor, then hand its state up.
    if (this.linkModal()) {
      editor.chain().focus().extendMarkRange('link').run();
      const attrs = editor.getAttributes('link');
      const { from, to } = editor.state.selection;
      this.linkEdit.emit({
        href: (attrs['href'] as string) ?? '',
        title: (attrs['title'] as string) ?? '',
        target: (attrs['target'] as string) ?? '',
        text: editor.state.doc.textBetween(from, to, ' '),
      });
      return;
    }
    const previous = editor.getAttributes('link')['href'] as string | undefined;
    const url = window.prompt(this.linkPromptLabel(), previous ?? 'https://');
    if (url === null) {
      return;
    }
    if (url === '') {
      editor.chain().focus().extendMarkRange('link').unsetLink().run();
      return;
    }
    editor.chain().focus().extendMarkRange('link').setLink({ href: url }).run();
  }

  /**
   * Apply (or remove) a link from a host Insert/Edit-link modal (see {@link linkModal}). A blank
   * href removes the link. The selection is first extended to the whole anchor (so editing from
   * inside a link replaces the entire anchor); a non-empty `text` replaces the anchor/selection
   * text, while an empty selection with no text inserts the href itself as the visible link text.
   */
  applyLink(result: RichTextLinkState | null): void {
    const editor = this.handle?.editor;
    if (!editor) {
      return;
    }
    editor.chain().focus().extendMarkRange('link').run();
    const href = result?.href?.trim();
    if (!href) {
      editor.chain().focus().unsetLink().run();
      return;
    }
    const attrs: Record<string, string | null> = {
      href,
      title: result?.title?.trim() || null,
      target: result?.target?.trim() || null,
    };
    const { from, to, empty } = editor.state.selection;
    const text = result?.text?.trim();
    if (text) {
      editor
        .chain()
        .focus()
        .insertContentAt({ from, to }, { type: 'text', text, marks: [{ type: 'link', attrs }] })
        .run();
    } else if (empty) {
      editor
        .chain()
        .focus()
        .insertContent({ type: 'text', text: href, marks: [{ type: 'link', attrs }] })
        .run();
    } else {
      editor
        .chain()
        .focus()
        .setLink(attrs as { href: string })
        .run();
    }
  }

  protected insertTable(): void {
    this.handle?.editor
      .chain()
      .focus()
      .insertTable({ rows: 3, cols: 3, withHeaderRow: true })
      .run();
  }

  // Table structure ops (shown only when the caret is inside a table). The commands ship with the
  // registered @tiptap/extension-table; `deleteColumn`/`deleteRow` drop the whole table on the last
  // column/row (TipTap default), which is the intended "clear the table" affordance.
  protected addColumnBefore(): void {
    this.handle?.editor.chain().focus().addColumnBefore().run();
  }
  protected addColumnAfter(): void {
    this.handle?.editor.chain().focus().addColumnAfter().run();
  }
  protected deleteColumn(): void {
    this.handle?.editor.chain().focus().deleteColumn().run();
  }
  protected addRowBefore(): void {
    this.handle?.editor.chain().focus().addRowBefore().run();
  }
  protected addRowAfter(): void {
    this.handle?.editor.chain().focus().addRowAfter().run();
  }
  protected deleteRow(): void {
    this.handle?.editor.chain().focus().deleteRow().run();
  }
  protected deleteTable(): void {
    this.handle?.editor.chain().focus().deleteTable().run();
  }

  protected onTableClass(event: Event): void {
    const cls = (event.target as HTMLSelectElement).value;
    this.handle?.editor
      .chain()
      .focus()
      .updateAttributes('table', { class: cls || null })
      .run();
  }

  protected onCellClass(event: Event): void {
    const cls = (event.target as HTMLSelectElement).value;
    this.handle?.editor
      .chain()
      .focus()
      .updateAttributes('tableCell', { class: cls || null })
      .run();
  }

  protected onRowClass(event: Event): void {
    const cls = (event.target as HTMLSelectElement).value;
    this.handle?.editor
      .chain()
      .focus()
      .updateAttributes('tableRow', { class: cls || null })
      .run();
  }

  protected requestImage(): void {
    this.imageRequest.emit();
  }

  protected toggleCodeView(): void {
    this.codeView.update((v) => !v);
    // Opening source view: seed the textarea from the current value immediately, then pretty-print
    // it (the formatter loads lazily) so the HTML is readable by default.
    if (this.codeView()) {
      this.codeDisplay.set(this.value());
      void this.formatCode();
    }
  }

  protected toggleFullscreen(): void {
    this.fullscreen.update((v) => !v);
  }

  protected onCodeInput(event: Event): void {
    // A genuine user edit via the raw-HTML textarea — emit, and let the value effect re-seed the
    // (hidden) editor so the two stay in sync when toggling back. Mirror into `codeDisplay` so the
    // textarea keeps rendering exactly what the user typed after a prior Format.
    const html = (event.target as HTMLTextAreaElement).value;
    this.codeDisplay.set(html);
    this.value.set(html);
  }

  /** Pretty-print the current HTML into the source-view textarea (display only; `value()` unchanged). */
  protected async formatCode(): Promise<void> {
    const { formatHtml } = await import('./html-format');
    this.codeDisplay.set(formatHtml(this.value()));
  }
}
