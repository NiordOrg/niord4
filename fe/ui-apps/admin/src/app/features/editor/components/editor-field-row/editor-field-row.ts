import {
  afterNextRender,
  Component,
  computed,
  DestroyRef,
  ElementRef,
  inject,
  input,
  viewChild,
} from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';

import { AuthFacade } from '../../../../core/auth/facades/auth.facade';
import { Icon } from '../../../../shared/icons/icon';
import { EditorFieldsService } from '../../services/editor-fields.service';

/**
 * The central edit-form building block: a compact table-style row whose head toggles between a
 * collapsed value SUMMARY and an inline EDITOR. Callers project the two presentations into the
 * `[fieldViewer]` (collapsed) and `[fieldEditor]` (expanded) slots.
 *
 * The head is a three-cell grid — a right-aligned label, a single-line (ellipsised) value, and a
 * pencil affordance — and the WHOLE head is the click / focus target. Expand state is owned by
 * {@link EditorFieldsService} (`editMode[fieldId][index]`), so a whole form's rows share one
 * expand/collapse map and the action-menu expand/collapse-all. Expansion is editor-gated: a plain
 * `user` (read access via EDITOR_VIEW_ROLES) sees the viewer only and the head is inert static text
 * — no button semantics, no keyboard toggle.
 *
 * Keyboard model (editors only): with the head focused, Right expands, Left
 * collapses, Space/Enter toggle, and Up/Down move focus to the previous/next row head. Rows are NOT
 * DOM siblings (message parts interleave them), so cross-row focus movement rides the service's
 * roving-tabindex registry: the head registers on first render and hands its tab stop back on
 * destroy. Listeners are scoped to the head element — deliberately never a document-global keydown.
 *
 * The `edited` dot, `invalid` label treatment, and `required` asterisk are presentational inputs —
 * the parent computes `edited`/`invalid` (via the service / Signal Forms) and passes them in so the
 * row stays dumb. The open editor always renders in the value column (same indent as the collapsed
 * value) so every row expands identically.
 */
@Component({
  selector: 'app-editor-field-row',
  imports: [TranslocoPipe, Icon],
  templateUrl: './editor-field-row.html',
  styleUrl: './editor-field-row.css',
  host: {
    class: 'contents',
    // Stable DOM marker so hosts (live-preview focus requests) can scroll to a row.
    '[attr.data-field-row]': 'fieldId()',
    '[attr.data-field-index]': 'index()',
  },
})
export class EditorFieldRow {
  /** The `editorFields` key this row edits (its slice of the shared expand map). */
  readonly fieldId = input.required<string>();
  /** Part index for the part-indexed fields (event_dates/positions/subject/description); 0 for flat fields. */
  readonly index = input(0);
  /** Transloco KEY for the row label — the row renders it with `| transloco`. */
  readonly label = input.required<string>();
  /** Renders the required asterisk. */
  readonly required = input(false);
  /** Accent/red label treatment for an invalid field; the parent owns the validity computation. */
  readonly invalid = input(false);
  /** Accent "edited" dot; the parent computes edited-vs-baseline. */
  readonly edited = input(false);
  /** Renders the italic empty-summary in place of the projected viewer. */
  readonly empty = input(false);
  /** Transloco KEY for the empty-summary text. */
  readonly emptyLabel = input('editor.field.empty');

  private readonly fields = inject(EditorFieldsService);
  private readonly auth = inject(AuthFacade);
  private readonly destroyRef = inject(DestroyRef);

  private readonly rowHead = viewChild<ElementRef<HTMLElement>>('rowHead');

  protected readonly isEditor = this.auth.hasRole('editor');

  /** Show the editor slot only for an editor viewing an expanded row; otherwise the viewer/empty summary. */
  protected readonly showEditor = computed(
    () => this.isEditor() && this.fields.isExpanded(this.fieldId(), this.index()),
  );

  constructor() {
    afterNextRender(() => {
      if (!this.isEditor()) {
        return;
      }
      const el = this.rowHead()?.nativeElement;
      if (el) {
        this.fields.registerLabel(el);
        this.destroyRef.onDestroy(() => this.fields.unregisterLabel(el));
      }
    });
  }

  protected onClick(): void {
    if (!this.isEditor()) {
      return;
    }
    this.fields.toggle(this.fieldId(), this.index());
  }

  protected onKeydown(event: KeyboardEvent): void {
    if (!this.isEditor()) {
      return;
    }
    const el = this.rowHead()?.nativeElement;
    if (!el) {
      return;
    }
    switch (event.key) {
      case 'ArrowRight':
        this.fields.expand(this.fieldId(), this.index());
        break;
      case 'ArrowLeft':
        this.fields.collapse(this.fieldId(), this.index());
        break;
      case ' ':
      case 'Enter':
        this.fields.toggle(this.fieldId(), this.index());
        break;
      case 'ArrowUp':
        this.fields.focusPrevious(el);
        break;
      case 'ArrowDown':
        this.fields.focusNext(el);
        break;
      default:
        return;
    }
    // Suppress the browser default for every handled key: arrows would scroll the pane and
    // Space/Enter would synthesize a second (native) activation on top of the toggle.
    event.preventDefault();
  }
}
