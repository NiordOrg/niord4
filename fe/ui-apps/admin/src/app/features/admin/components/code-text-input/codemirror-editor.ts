import { defaultKeymap, history, historyKeymap } from '@codemirror/commands';
import { defaultHighlightStyle, syntaxHighlighting } from '@codemirror/language';
import { Compartment, EditorState, type Extension } from '@codemirror/state';
import { EditorView, keymap, lineNumbers } from '@codemirror/view';

/**
 * Framework-agnostic CodeMirror 6 wrapper. This module is reached ONLY through a
 * dynamic `import()` (from the `code-text-input` CVA and the `code-merge-view`
 * component), so every `@codemirror/*` import here lands in a lazy chunk and never
 * in the main bundle. The language packages are themselves dynamically imported so
 * an editor only pulls the grammar it actually uses.
 */
export type CodeLanguage = 'ftl' | 'javascript' | 'json';

async function resolveLanguage(language: CodeLanguage | undefined): Promise<Extension> {
  switch (language) {
    case 'json':
      return (await import('@codemirror/lang-json')).json();
    case 'javascript':
      return (await import('@codemirror/lang-javascript')).javascript();
    case 'ftl':
      return (await import('./ftl-mode')).ftl();
    default:
      return [];
  }
}

/** Read-only needs BOTH facets: `readOnly` blocks edit commands, `editable` makes the DOM inert. */
function editableExtension(editable: boolean): Extension {
  return editable ? [] : [EditorState.readOnly.of(true), EditorView.editable.of(false)];
}

const baseExtensions: Extension = [
  lineNumbers(),
  history(),
  syntaxHighlighting(defaultHighlightStyle, { fallback: true }),
  keymap.of([...defaultKeymap, ...historyKeymap]),
  EditorView.lineWrapping,
];

export interface CodeEditorOptions {
  readonly doc: string;
  readonly language?: CodeLanguage;
  readonly editable: boolean;
  readonly ariaLabel?: string;
  readonly onChange: (value: string) => void;
  readonly onBlur: () => void;
}

export interface CodeEditorHandle {
  /** Replace the whole document (no-op when unchanged, to preserve cursor/selection). */
  setValue(value: string): void;
  setLanguage(language: CodeLanguage | undefined): void;
  setEditable(editable: boolean): void;
  destroy(): void;
}

export async function createCodeEditor(
  parent: HTMLElement,
  options: CodeEditorOptions,
): Promise<CodeEditorHandle> {
  const languageCompartment = new Compartment();
  const editableCompartment = new Compartment();
  const initialLanguage = await resolveLanguage(options.language);
  // Monotonic token so out-of-order grammar imports don't clobber a newer language.
  let languageToken = 0;

  const view = new EditorView({
    parent,
    state: EditorState.create({
      doc: options.doc,
      extensions: [
        baseExtensions,
        languageCompartment.of(initialLanguage),
        editableCompartment.of(editableExtension(options.editable)),
        EditorView.contentAttributes.of(
          options.ariaLabel ? { 'aria-label': options.ariaLabel } : {},
        ),
        EditorView.updateListener.of((update) => {
          if (update.docChanged) {
            options.onChange(update.state.doc.toString());
          }
        }),
        EditorView.domEventHandlers({
          blur: () => {
            options.onBlur();
            return false;
          },
        }),
      ],
    }),
  });

  return {
    setValue(value: string): void {
      if (view.state.doc.toString() !== value) {
        view.dispatch({ changes: { from: 0, to: view.state.doc.length, insert: value } });
      }
    },
    setLanguage(language: CodeLanguage | undefined): void {
      const token = ++languageToken;
      resolveLanguage(language)
        .then((extension) => {
          if (token === languageToken) {
            view.dispatch({ effects: languageCompartment.reconfigure(extension) });
          }
        })
        .catch(() => {
          // A grammar that fails to load degrades to no highlighting.
          if (token === languageToken) {
            view.dispatch({ effects: languageCompartment.reconfigure([]) });
          }
        });
    },
    setEditable(editable: boolean): void {
      view.dispatch({ effects: editableCompartment.reconfigure(editableExtension(editable)) });
    },
    destroy(): void {
      view.destroy();
    },
  };
}

export interface MergeViewOptions {
  readonly original: string;
  readonly modified: string;
  readonly language?: CodeLanguage;
}

export interface MergeViewHandle {
  destroy(): void;
}

/** Side-by-side read-only diff over two source strings (the revision-history diff). */
export async function createMergeView(
  parent: HTMLElement,
  options: MergeViewOptions,
): Promise<MergeViewHandle> {
  const { MergeView } = await import('@codemirror/merge');
  const language = await resolveLanguage(options.language);
  const sideExtensions: Extension = [
    lineNumbers(),
    syntaxHighlighting(defaultHighlightStyle, { fallback: true }),
    EditorView.lineWrapping,
    EditorState.readOnly.of(true),
    EditorView.editable.of(false),
    language,
  ];
  const merge = new MergeView({
    a: { doc: options.original, extensions: sideExtensions },
    b: { doc: options.modified, extensions: sideExtensions },
    parent,
    orientation: 'a-b',
    highlightChanges: true,
    gutter: true,
    collapseUnchanged: { margin: 3, minSize: 4 },
  });
  return { destroy: () => merge.destroy() };
}
