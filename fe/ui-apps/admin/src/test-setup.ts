import { getTestBed } from '@angular/core/testing';
import { beforeEach } from 'vitest';

// jsdom capability gap, not an isolation guard: it implements `getClientRects` and
// `getBoundingClientRect` on Element but on NEITHER for Range.
//
// ProseMirror's `singleRect` (prosemirror-view, reached from TipTap via
// EditorView.scrollToSelection → coordsAtPos) is handed a Range whenever the selection resolves
// inside a TEXT node, and calls `getClientRects()` with `getBoundingClientRect()` as the
// fallback. Both are missing, so the editor threw an unhandled
// `TypeError: target.getClientRects is not a function` that failed the RUN (vitest exits
// non-zero on unhandled errors) while every test still reported as passing. It surfaced only
// intermittently because whether a selection lands in a text node rather than an element
// depends on the editor's document state, which shifts with test ordering.
//
// jsdom has no layout engine, so the honest stub is precisely what it already returns for
// Elements: no rects, and an all-zero box. ProseMirror reads a zero rect as "unknown"
// (see its `nonZero` helper) and degrades gracefully — the same path element-backed positions
// already take in these tests.
if (typeof Range !== 'undefined' && typeof Range.prototype.getClientRects !== 'function') {
  Range.prototype.getClientRects = () => [] as unknown as DOMRectList;
  Range.prototype.getBoundingClientRect = () => new DOMRect(0, 0, 0, 0);
}

// Cross-test isolation guards for the single-fork runner (`fileParallelism: false`),
// where the TestBed singleton and jsdom's `localStorage` are shared across files.
// Two order-dependent leaks were flaking the suite; both are neutralised here so
// test outcomes never depend on which spec ran before them.
//
// 1. TestBed re-instantiation. Several specs call
//    `TestBed.inject(HttpTestingController).verify()` inside an `afterEach`; when
//    that runs after Angular's auto-teardown it re-instantiates the module, so the
//    next test's `configureTestingModule()` threw "Cannot configure the test module
//    when the test module has already been instantiated". Resetting first makes
//    module setup order-independent (resetTestingModule leaves the test ENVIRONMENT
//    intact, so it composes with the Angular builder's own setup).
//
// 2. Persisted language. Language-switch specs call `LanguageService.selectLanguage`,
//    which writes `niord.language` to localStorage. That value survived into later
//    facade specs whose initial fetch is language-scoped (e.g. `?lang=en` vs the
//    leaked `?lang=da`), failing their HttpTestingController expectations. Clearing
//    storage gives every test the default language.
beforeEach(() => {
  try {
    localStorage.clear();
    sessionStorage.clear();
  } catch {
    // No DOM storage in this environment — nothing to isolate.
  }
  getTestBed().resetTestingModule();
});
