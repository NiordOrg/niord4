import { Component, computed, input, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { TranslocoService } from '@jsverse/transloco';
import { describe, expect, it, vi } from 'vitest';

import { LanguageService } from '../../../../core/main-shell/services/language.service';
import { provideTestTransloco } from '../../../../shared/testing/transloco-testing';
import type { Message } from '../../../messages/models/message.model';
import { MessageDetailsRenderer } from '../../../messages/components/message-details-renderer/message-details-renderer';
import { EditorFacade } from '../../services/editor-facade';
import { EditorFieldsService } from '../../services/editor-fields.service';
import {
  clampPreviewWidth,
  LivePreview,
  PreviewFocusRequest,
  resolveHitTarget,
} from './live-preview';

// ── Pure helpers (no TestBed) ──────────────────────────────────────────────────

function el(tag: string, attrs: Record<string, string> = {}, ...children: Element[]): HTMLElement {
  const node = document.createElement(tag);
  for (const [key, value] of Object.entries(attrs)) {
    node.setAttribute(key, value);
  }
  children.forEach((child) => node.appendChild(child));
  return node;
}

describe('clampPreviewWidth', () => {
  it('keeps the default width inside a wide viewport', () => {
    expect(clampPreviewWidth(440, 1440)).toBe(440);
  });

  it('caps the width at 820 on very wide viewports', () => {
    expect(clampPreviewWidth(2000, 3000)).toBe(820);
  });

  it('never drops below 320', () => {
    expect(clampPreviewWidth(50, 1440)).toBe(320);
  });

  it('reserves room for the form pane on a mid-width viewport', () => {
    // upper bound = min(820, 900 − 420) = 480
    expect(clampPreviewWidth(700, 900)).toBe(480);
  });

  it('floors the upper bound at the lower bound on a tiny viewport', () => {
    // 700 − 420 = 280 < 320, so the range collapses to a single valid value
    expect(clampPreviewWidth(500, 700)).toBe(320);
  });
});

describe('resolveHitTarget', () => {
  it('maps an explicit data-pv-field marker (with default index)', () => {
    const marker = el('span', { 'data-pv-field': 'title' });
    const container = el('div', {}, marker);
    expect(resolveHitTarget(marker, container)).toEqual({ fieldId: 'title', index: 0 });
  });

  it('reads data-pv-index and resolves from a nested click target', () => {
    const inner = el('em');
    const marker = el('span', { 'data-pv-field': 'description', 'data-pv-index': '2' }, inner);
    const container = el('div', {}, marker);
    expect(resolveHitTarget(inner, container)).toEqual({ fieldId: 'description', index: 2 });
  });

  it('falls back to the nearest message-details child selector', () => {
    const inner = el('span');
    const source = el('app-message-source', {}, inner);
    const container = el('div', {}, source);
    expect(resolveHitTarget(inner, container)).toEqual({ fieldId: 'source', index: 0 });
  });

  it('derives the part index from DOM order for the part-indexed fallback', () => {
    const inner = el('em');
    const part1 = el('app-message-part', {}, inner);
    const container = el('div', {}, el('app-message-part'), part1);
    expect(resolveHitTarget(inner, container)).toEqual({ fieldId: 'description', index: 1 });
  });

  it('returns null for a click that hits no mapped region', () => {
    const inner = el('span');
    const container = el('div', {}, inner);
    expect(resolveHitTarget(inner, container)).toBeNull();
  });
});

// ── Component behaviour (stubbed renderer) ─────────────────────────────────────

/**
 * Test double for the language-parametric renderer: it exposes the same `message` + `language`
 * inputs and resolves the title client-side, so the language-tab test asserts LivePreview's wiring
 * (pill → previewLang → renderer language) without depending on the real renderer's internals. It
 * also emits two `data-pv-field` markers for the click-to-focus test.
 */
@Component({
  selector: 'app-message-details-renderer',
  template: `
    <span class="stub-title">{{ resolvedTitle() }}</span>
    <span class="hot-source" data-pv-field="source">source</span>
    <span class="hot-desc" data-pv-field="description" data-pv-index="1">description</span>
  `,
})
class RendererStub {
  readonly message = input.required<Message>();
  readonly language = input<string>();
  readonly showPromulgation = input(false);
  protected readonly resolvedTitle = computed(() => {
    const lang = this.language();
    const descs = this.message().descs ?? [];
    return (descs.find((desc) => desc.lang === lang) ?? descs[0])?.title ?? '';
  });
}

const MESSAGE = {
  descs: [
    { lang: 'en', title: 'English title' },
    { lang: 'da', title: 'Dansk titel' },
    { lang: 'de', title: 'Deutscher Titel' },
  ],
  parts: [],
} as unknown as Message;

function languageServiceStub(): LanguageService {
  return {
    modelLanguages: [
      { label: 'English', value: 'en', locale: 'en-GB', flagPath: 'assets/img/flags/en.png' },
      { label: 'Dansk', value: 'da', locale: 'da', flagPath: 'assets/img/flags/da.png' },
      { label: 'Deutsch', value: 'de', locale: 'de-DE', flagPath: 'assets/img/flags/de.png' },
    ],
    selectedLanguage: signal('en'),
  } as unknown as LanguageService;
}

function editorFacadeStub(): EditorFacade {
  return {
    message: signal<Message | undefined>(MESSAGE),
    loadedBaseline: signal<Message | undefined>(undefined),
    isEditor: signal(true),
  } as unknown as EditorFacade;
}

function firePointer(target: Element, type: string, clientX: number): void {
  const event = new Event(type, { bubbles: true, cancelable: true });
  Object.defineProperty(event, 'clientX', { value: clientX });
  Object.defineProperty(event, 'pointerId', { value: 1 });
  target.dispatchEvent(event);
}

async function setup(mode: 'inline' | 'drawer' = 'inline') {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({
    imports: [LivePreview],
    providers: [
      provideTestTransloco(),
      EditorFieldsService,
      { provide: EditorFacade, useValue: editorFacadeStub() },
      { provide: LanguageService, useValue: languageServiceStub() },
    ],
  });
  TestBed.overrideComponent(LivePreview, {
    remove: { imports: [MessageDetailsRenderer] },
    add: { imports: [RendererStub] },
  });
  TestBed.inject(TranslocoService).load('en').subscribe();

  const fixture = TestBed.createComponent(LivePreview);
  fixture.componentRef.setInput('message', MESSAGE);
  fixture.componentRef.setInput('mode', mode);
  fixture.autoDetectChanges();
  await fixture.whenStable();
  const el = fixture.nativeElement as HTMLElement;
  return { fixture, el };
}

describe('LivePreview', () => {
  it('swaps the rendered preview content when a language tab is selected', async () => {
    const { fixture, el } = await setup();
    const title = () => el.querySelector('.stub-title')?.textContent?.trim();
    const pill = (code: string) =>
      [...el.querySelectorAll('button')].find((b) => b.textContent?.trim().endsWith(code))!;

    expect(title()).toBe('English title');

    pill('DA').click();
    await fixture.whenStable();
    expect(title()).toBe('Dansk titel');

    pill('DE').click();
    await fixture.whenStable();
    expect(title()).toBe('Deutscher Titel');
  });

  it('renders one language pill per configured model language', async () => {
    const { el } = await setup();
    expect(el.querySelectorAll('[role="group"] button')).toHaveLength(3);
  });

  it('emits a focus request and expands the matching row on a preview region click', async () => {
    const { fixture, el } = await setup();
    const fields = TestBed.inject(EditorFieldsService);
    const expandSpy = vi.spyOn(fields, 'expand');
    const emitted: PreviewFocusRequest[] = [];
    fixture.componentInstance.focusField.subscribe((request) => emitted.push(request));

    (el.querySelector('.hot-source') as HTMLElement).click();
    await fixture.whenStable();
    expect(emitted.at(-1)).toEqual({ fieldId: 'source', index: 0 });
    expect(expandSpy).toHaveBeenCalledWith('source', 0);

    (el.querySelector('.hot-desc') as HTMLElement).click();
    await fixture.whenStable();
    expect(emitted.at(-1)).toEqual({ fieldId: 'description', index: 1 });
    expect(expandSpy).toHaveBeenLastCalledWith('description', 1);
  });

  it('resizes within the clamp on drag and ignores moves after release', async () => {
    const { fixture, el } = await setup('inline');
    const width = () => (fixture.componentInstance as unknown as { width(): number }).width();
    const dragging = () =>
      (fixture.componentInstance as unknown as { dragging(): boolean }).dragging();
    const handle = el.querySelector('[role="separator"]') as HTMLElement;

    firePointer(handle, 'pointerdown', 500);
    expect(dragging()).toBe(true);

    firePointer(handle, 'pointermove', 400); // widen by 100 → 540
    expect(width()).toBe(clampPreviewWidth(540, window.innerWidth));

    firePointer(handle, 'pointermove', -1000); // far past the clamp
    expect(width()).toBe(clampPreviewWidth(1940, window.innerWidth));

    firePointer(handle, 'pointerup', -1000);
    expect(dragging()).toBe(false);

    const settled = width();
    firePointer(handle, 'pointermove', 900); // ignored once released
    expect(width()).toBe(settled);
  });

  it('renders the splitter handle in inline mode', async () => {
    const { el } = await setup('inline');
    expect(el.querySelector('[role="separator"]')).toBeTruthy();
  });

  it('hides the splitter handle in drawer mode', async () => {
    const { el } = await setup('drawer');
    expect(el.querySelector('[role="separator"]')).toBeNull();
  });
});
