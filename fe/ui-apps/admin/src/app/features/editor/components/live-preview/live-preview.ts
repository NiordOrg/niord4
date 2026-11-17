import { Component, ElementRef, inject, input, output, signal, viewChild } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';

import { LanguageService } from '../../../../core/main-shell/services/language.service';
import type { Message } from '../../../messages/models/message.model';
import { MessageDetailsRenderer } from '../../../messages/components/message-details-renderer/message-details-renderer';
import { EditorFieldsService } from '../../services/editor-fields.service';
import { LanguageFlag } from '../../../../shared/components/language-flag/language-flag';

/** Preview pane default width (px) and the drag clamp `[MIN, min(MAX, viewport − RESERVE)]`. */
export const DEFAULT_PREVIEW_WIDTH = 440;
const MIN_PREVIEW_WIDTH = 320;
const MAX_PREVIEW_WIDTH = 820;
const VIEWPORT_RESERVE = 420;
const DEFAULT_VIEWPORT = 1440;
/** Keyboard resize step (px); Shift multiplies it for a coarse jump. */
const KEYBOARD_STEP = 12;
const KEYBOARD_STEP_COARSE = 40;

/** A request to reveal + focus the edit-form row a preview region maps to. */
export interface PreviewFocusRequest {
  /** The `editorFields` key of the target row. */
  readonly fieldId: string;
  /** Part index for the part-indexed fields (event_dates/positions/subject/description); 0 otherwise. */
  readonly index: number;
}

/**
 * Clamp a candidate preview width into the design's range: never below
 * {@link MIN_PREVIEW_WIDTH}, never above `min(MAX_PREVIEW_WIDTH, viewport − RESERVE)`
 * so the form pane always keeps a usable minimum. The upper bound is floored at the
 * lower bound so a very narrow viewport still yields a valid width.
 */
export function clampPreviewWidth(rawWidth: number, viewportWidth: number): number {
  const upper = Math.max(
    MIN_PREVIEW_WIDTH,
    Math.min(MAX_PREVIEW_WIDTH, viewportWidth - VIEWPORT_RESERVE),
  );
  return Math.min(Math.max(rawWidth, MIN_PREVIEW_WIDTH), upper);
}

// Fallback map from a rendered preview region to its edit-form field, used when the DOM carries no
// explicit `data-pv-field` marker: match the nearest message-details child component by host
// selector. `event_dates` / `areas` / `positions` are absent because the current renderer does not
// render those regions — they light up automatically once a region is tagged `data-pv-field`.
const HOT_SELECTOR_RULES: readonly { readonly selector: string; readonly fieldId: string }[] = [
  { selector: 'app-message-source', fieldId: 'source' },
  { selector: 'app-message-publication', fieldId: 'publication' },
  { selector: 'app-message-charts', fieldId: 'charts' },
  { selector: 'app-message-reference-type', fieldId: 'references' },
  { selector: 'app-message-part', fieldId: 'description' },
];
/** The one fallback rule whose field is part-indexed — its DOM position gives the part index. */
const INDEXED_SELECTOR = 'app-message-part';

/**
 * Resolve which edit-form field a click inside the preview body targets. Prefers an explicit
 * `data-pv-field` (+ optional `data-pv-index`) marker — the precise, forward-compatible path — and
 * otherwise falls back to the nearest known message-details child component ({@link HOT_SELECTOR_RULES}),
 * deriving the part index from the clicked part's DOM order. Returns `null` for a click that hits no
 * mapped region (whitespace, a non-hot area).
 */
export function resolveHitTarget(target: Element, container: Element): PreviewFocusRequest | null {
  const marked = target.closest('[data-pv-field]');
  if (marked && container.contains(marked)) {
    const fieldId = marked.getAttribute('data-pv-field') ?? '';
    if (!fieldId) {
      return null;
    }
    const rawIndex = Number(marked.getAttribute('data-pv-index'));
    return { fieldId, index: Number.isFinite(rawIndex) ? rawIndex : 0 };
  }

  for (const rule of HOT_SELECTOR_RULES) {
    const el = target.closest(rule.selector);
    if (el && container.contains(el)) {
      const index =
        rule.selector === INDEXED_SELECTOR
          ? Math.max(0, [...container.querySelectorAll(INDEXED_SELECTOR)].indexOf(el))
          : 0;
      return { fieldId: rule.fieldId, index };
    }
  }
  return null;
}

/**
 * The editor's live preview: renders the WORKING message (a live signal read the host binds from
 * `EditorFacade.message()` — never a refetch) through the language-parametric
 * {@link MessageDetailsRenderer} at a locally-selected preview language, so edits reflect instantly.
 *
 * Two presentations, one component: `mode="inline"` renders a resizable pane with a drag splitter
 * (default {@link DEFAULT_PREVIEW_WIDTH} px, clamped per {@link clampPreviewWidth}); `mode="drawer"`
 * drops the splitter and fills the host's `app-drawer` (the ≤1080 px narrow presentation). The
 * breakpoint decision is the HOST's (it flips `mode` and owns the Preview button + drawer) —
 * this component is presentation-agnostic and reusable via `mediaQuerySignal` in the host.
 *
 * A sticky head carries one language pill per configured model language ({@link LanguageService};
 * N languages, flag + code — matching the `LocalizedInputs` treatment). Clicking a preview region
 * emits {@link focusField} AND unconditionally expands the matching row via {@link EditorFieldsService}
 * — the host reacts to the event to scroll/focus the row and, at narrow width, close the drawer
 * (the service's focus registry is roving-tabindex, not field-addressable, so row focus is host-owned).
 * Preview language + splitter width are LOCAL signals: never URL-synced, never persisted.
 */
@Component({
  selector: 'app-live-preview',
  templateUrl: './live-preview.html',
  imports: [LanguageFlag, MessageDetailsRenderer, TranslocoPipe],
  // `h-full` only in drawer mode (the drawer body has a definite height). Inline mode must keep
  // the cross-size `auto`: an explicit percentage height blocks the flex-row stretch that makes
  // the pane run the full length of the (page-growable) form column down to the save bar.
  // `overflow-hidden` is drawer-only: `overflow` establishes a scroll container, which would trap
  // the inline mode's sticky wrapper inside this host (it could then only "stick" to the host's own
  // box, i.e. do nothing). Inline clipping is already handled where it matters — the preview body
  // owns `overflow-auto`.
  // `contain-size` in inline mode: the preview must NEVER be what decides the page's height. Without
  // it the sticky wrapper's viewport-height cap was also the host's natural height, so on a tall
  // window a form shorter than the viewport was stretched to the viewport by its own preview and the
  // page overflowed by the chrome above and below. Size containment makes the host contribute no
  // height of its own; the row is as tall as the form, the host stretches to it, and a long preview
  // scrolls inside its body. The width is set explicitly, so inline-axis containment costs nothing.
  host: {
    class: 'live-preview-pane relative flex min-h-0 flex-col bg-surface-sunken',
    '[style.width.px]': "mode() === 'inline' ? width() : null",
    '[class.contain-size]': "mode() === 'inline'",
    '[class.overflow-hidden]': "mode() === 'drawer'",
    '[class.w-full]': "mode() === 'drawer'",
    '[class.h-full]': "mode() === 'drawer'",
    '[class.border-l]': "mode() === 'inline'",
    '[class.border-surface-200]': "mode() === 'inline'",
  },
})
export class LivePreview {
  private readonly language = inject(LanguageService);
  private readonly fields = inject(EditorFieldsService);

  /** The working message to preview — the host binds `facade.message()` (live, no refetch). */
  readonly message = input<Message | undefined>(undefined);
  /** `inline` shows the drag splitter; `drawer` (rendered inside the host's app-drawer) hides it. */
  readonly mode = input<'inline' | 'drawer'>('inline');

  /** A preview region was clicked — the host scrolls/focuses the row and closes the drawer if narrow. */
  readonly focusField = output<PreviewFocusRequest>();

  private readonly previewBody = viewChild.required<ElementRef<HTMLElement>>('previewBody');

  /** Configured model languages, one pill each (flag + code) — scales to N languages. */
  protected readonly languages = this.language.modelLanguages;
  /** Locally-selected preview language, defaulting to the current app language. */
  protected readonly previewLang = signal(this.language.selectedLanguage());
  /** Inline-pane width in px (drag/keyboard adjustable), clamped per the design. */
  protected readonly width = signal(DEFAULT_PREVIEW_WIDTH);
  protected readonly dragging = signal(false);

  /** Grab-point of the active splitter drag: pointer X and pane width at pointer-down. */
  private dragStartX = 0;
  private dragStartWidth = 0;

  protected selectLanguage(lang: string): void {
    this.previewLang.set(lang);
  }

  protected onPreviewClick(event: MouseEvent): void {
    // Element (not HTMLElement) so a click on an <svg>/<path> glyph inside a region still resolves.
    const target = event.target;
    if (!(target instanceof Element)) {
      return;
    }
    const hit = resolveHitTarget(target, this.previewBody().nativeElement);
    if (!hit) {
      return;
    }
    this.fields.expand(hit.fieldId, hit.index);
    this.focusField.emit(hit);
  }

  protected onSplitterPointerDown(event: PointerEvent): void {
    const handle = event.target;
    if (handle instanceof Element) {
      handle.setPointerCapture?.(event.pointerId);
    }
    this.dragStartX = event.clientX;
    this.dragStartWidth = this.width();
    this.dragging.set(true);
    event.preventDefault();
  }

  protected onSplitterPointerMove(event: PointerEvent): void {
    if (!this.dragging()) {
      return;
    }
    // The preview pane sits on the RIGHT, so dragging the handle left (smaller clientX) widens it.
    const raw = this.dragStartWidth + (this.dragStartX - event.clientX);
    this.width.set(clampPreviewWidth(raw, this.viewportWidth()));
  }

  protected onSplitterPointerUp(event: PointerEvent): void {
    if (!this.dragging()) {
      return;
    }
    const handle = event.target;
    if (handle instanceof Element) {
      handle.releasePointerCapture?.(event.pointerId);
    }
    this.dragging.set(false);
  }

  protected onSplitterKeydown(event: KeyboardEvent): void {
    const step = event.shiftKey ? KEYBOARD_STEP_COARSE : KEYBOARD_STEP;
    let delta: number;
    if (event.key === 'ArrowLeft') {
      delta = step; // widen the preview
    } else if (event.key === 'ArrowRight') {
      delta = -step; // narrow the preview
    } else {
      return;
    }
    this.width.set(clampPreviewWidth(this.width() + delta, this.viewportWidth()));
    event.preventDefault();
  }

  private viewportWidth(): number {
    return typeof window !== 'undefined' ? window.innerWidth : DEFAULT_VIEWPORT;
  }
}
