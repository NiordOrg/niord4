import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { disabled, form, FormField } from '@angular/forms/signals';
import { By } from '@angular/platform-browser';
import type { Editor } from '@tiptap/core';
import { afterEach, describe, expect, it } from 'vitest';

import { provideNiordIcons } from '../../icons/niord-icons';
import { provideTestTransloco } from '../../testing/transloco-testing';
import { RichTextEditor, type RichTextLinkState } from './rich-text-editor';
import type { RichTextPreset } from './rich-text-engine';

// ProseMirror measures selection coordinates via `Range.getClientRects` when it focuses / scrolls a
// changed selection into view (a link edit does both). jsdom doesn't implement it, so the async
// measurement throws "getClientRects is not a function" AFTER the test — a false-positive unhandled
// error. A no-op polyfill lets the (visually irrelevant) measurement miss harmlessly.
if (typeof Range !== 'undefined' && !Range.prototype.getClientRects) {
  const emptyRect = {
    top: 0,
    left: 0,
    right: 0,
    bottom: 0,
    width: 0,
    height: 0,
    x: 0,
    y: 0,
    toJSON: () => ({}),
  } as DOMRect;
  // An empty array is iterable and length-0 — all ProseMirror reads off a rect list.
  Range.prototype.getClientRects = () => [] as unknown as DOMRectList;
  Range.prototype.getBoundingClientRect = () => emptyRect;
}

@Component({
  imports: [RichTextEditor],
  template: `<app-rich-text-editor
    [preset]="preset()"
    [value]="value()"
    (valueChange)="onChange($event)"
    [label]="label()"
  />`,
})
class ValueHost {
  readonly preset = signal<RichTextPreset>('full');
  readonly value = signal('');
  readonly label = signal('Body');
  readonly changes = signal<string[]>([]);
  onChange(v: string): void {
    this.changes.update((c) => [...c, v]);
  }
}

@Component({
  imports: [RichTextEditor, FormField],
  template: `<app-rich-text-editor [preset]="preset()" [formField]="form.body" />`,
})
class FormHost {
  readonly preset = signal<RichTextPreset>('full');
  readonly off = signal(false);
  private readonly data = signal<{ body: string }>({ body: '<p>hi</p>' });
  readonly form = form(this.data, (path) => disabled(path.body, { when: () => this.off() }));
}

@Component({
  imports: [RichTextEditor],
  template: `<app-rich-text-editor
    preset="inline"
    [value]="value()"
    [linkModal]="true"
    (linkEdit)="captured.set($event)"
    (valueChange)="onChange($event)"
  />`,
})
class LinkModalHost {
  readonly value = signal('');
  readonly captured = signal<RichTextLinkState | undefined>(undefined);
  readonly changes = signal<string[]>([]);
  onChange(v: string): void {
    this.changes.update((c) => [...c, v]);
  }
}

// The stub Transloco loader echoes the KEY, so aria-labels are the `shared.richText.*`
// keys — match on key fragments, not English words.
function findButton(fixture: ComponentFixture<unknown>, keyFragment: string): HTMLButtonElement {
  const button = Array.from(fixture.nativeElement.querySelectorAll('button')).find((b) =>
    (b as HTMLButtonElement).getAttribute('aria-label')?.includes(keyFragment),
  ) as HTMLButtonElement | undefined;
  if (!button) {
    throw new Error(`no toolbar button matching "${keyFragment}"`);
  }
  return button;
}

function findSelect(fixture: ComponentFixture<unknown>, keyFragment: string): HTMLSelectElement {
  const select = Array.from(fixture.nativeElement.querySelectorAll('select')).find((s) =>
    (s as HTMLSelectElement).getAttribute('aria-label')?.includes(keyFragment),
  ) as HTMLSelectElement | undefined;
  if (!select) {
    throw new Error(`no toolbar select matching "${keyFragment}"`);
  }
  return select;
}

function editorOf(rte: RichTextEditor): Editor {
  const handle = (rte as unknown as { handle?: { editor: Editor } }).handle;
  if (!handle) {
    throw new Error('editor handle not mounted');
  }
  return handle.editor;
}

/** Wait for the lazy TipTap import + mount (a floating promise `whenStable` doesn't track). */
async function waitForEditor(fixture: ComponentFixture<unknown>): Promise<HTMLElement> {
  for (let i = 0; i < 200; i++) {
    const surface = fixture.nativeElement.querySelector('.ProseMirror') as HTMLElement | null;
    if (surface) {
      await fixture.whenStable();
      return surface;
    }
    await new Promise((resolve) => setTimeout(resolve, 0));
  }
  throw new Error('editor never mounted');
}

function rteOf(fixture: ComponentFixture<unknown>): RichTextEditor {
  return fixture.debugElement.query(By.directive(RichTextEditor))
    .componentInstance as RichTextEditor;
}

async function setupValueHost(configure?: (host: ValueHost) => void) {
  TestBed.configureTestingModule({ providers: [provideTestTransloco(), provideNiordIcons()] });
  const fixture = TestBed.createComponent(ValueHost);
  configure?.(fixture.componentInstance);
  fixture.autoDetectChanges();
  await fixture.whenStable();
  const surface = await waitForEditor(fixture);
  return { fixture, host: fixture.componentInstance, surface };
}

async function setupLinkModalHost(configure?: (host: LinkModalHost) => void) {
  TestBed.configureTestingModule({ providers: [provideTestTransloco(), provideNiordIcons()] });
  const fixture = TestBed.createComponent(LinkModalHost);
  configure?.(fixture.componentInstance);
  fixture.autoDetectChanges();
  await fixture.whenStable();
  await waitForEditor(fixture);
  return { fixture, host: fixture.componentInstance };
}

describe('RichTextEditor', () => {
  afterEach(() => {
    document.querySelectorAll('.cdk-overlay-container').forEach((el) => el.replaceChildren());
  });

  it('renders an incoming value into the editing surface', async () => {
    const { surface } = await setupValueHost((host) => host.value.set('<p>Hello world</p>'));
    expect(surface.textContent).toContain('Hello world');
  });

  it('does NOT emit valueChange for a programmatic value write', async () => {
    const { fixture, host } = await setupValueHost();
    host.value.set('<p>Programmatic</p>');
    await fixture.whenStable();
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(host.changes()).toEqual([]);
  });

  it('emits valueChange exactly once for a genuine content edit', async () => {
    const { fixture, host } = await setupValueHost((host) => host.value.set('<p>Seed</p>'));
    expect(host.changes()).toEqual([]);

    rteOf(fixture).insertContentAtCursor(' more');
    await fixture.whenStable();

    expect(host.changes().length).toBe(1);
    expect(host.changes()[0]).toContain('more');
  });

  it('table class picker emits the exact class string', async () => {
    const { fixture, host } = await setupValueHost();

    findButton(fixture, 'insertTable').click();
    await fixture.whenStable();

    const tableSelect = findSelect(fixture, 'tableClass');
    tableSelect.value = 'no-border';
    tableSelect.dispatchEvent(new Event('change'));
    await fixture.whenStable();

    expect(host.changes().at(-1)).toContain('class="no-border"');
  });

  it('no-wrap emits a class, never an inline style', async () => {
    const { fixture, host } = await setupValueHost((host) => host.value.set('<p>hello</p>'));
    // Select "hello" so the toggle wraps real content.
    editorOf(rteOf(fixture)).commands.setTextSelection({ from: 1, to: 6 });

    findButton(fixture, 'noWrap').click();
    await fixture.whenStable();

    const last = host.changes().at(-1) ?? '';
    expect(last).toContain('class="no-wrap"');
    expect(last).not.toContain('style=');
  });

  it('reflects the form-driven disabled state onto the contenteditable', async () => {
    TestBed.configureTestingModule({ providers: [provideTestTransloco(), provideNiordIcons()] });
    const fixture = TestBed.createComponent(FormHost);
    fixture.autoDetectChanges();
    await fixture.whenStable();
    const surface = await waitForEditor(fixture);

    expect(surface.getAttribute('contenteditable')).toBe('true');

    fixture.componentInstance.off.set(true);
    await fixture.whenStable();
    expect(surface.getAttribute('contenteditable')).toBe('false');
  });

  it('inline preset shows a minimal toolbar (no bold button); full shows link', async () => {
    const { fixture } = await setupValueHost((host) => host.preset.set('inline'));
    const labels = Array.from(fixture.nativeElement.querySelectorAll('button')).map((b) =>
      (b as HTMLButtonElement).getAttribute('aria-label'),
    );
    expect(labels.some((l) => l?.includes('richText.bold'))).toBe(false);
    expect(labels.some((l) => l?.includes('richText.link'))).toBe(true);
  });

  it('linkModal: the link button emits the anchor state instead of a URL prompt', async () => {
    const { fixture, host } = await setupLinkModalHost((host) =>
      host.value.set('<a href="https://y.test" title="TT" target="_blank">hi</a>'),
    );
    editorOf(rteOf(fixture)).commands.selectAll();

    findButton(fixture, 'richText.link').click();
    await fixture.whenStable();

    expect(host.captured()).toEqual({
      href: 'https://y.test',
      title: 'TT',
      target: '_blank',
      text: 'hi',
    });
  });

  it('applyLink sets an anchor carrying href, title and target over the selection', async () => {
    const { fixture, host } = await setupLinkModalHost((host) => host.value.set('hello'));
    editorOf(rteOf(fixture)).commands.selectAll();

    rteOf(fixture).applyLink({ href: 'https://x.test', text: '', title: 'T', target: '_blank' });
    await fixture.whenStable();

    const last = host.changes().at(-1) ?? '';
    expect(last).toContain('href="https://x.test"');
    expect(last).toContain('title="T"');
    expect(last).toContain('target="_blank"');
    expect(last).toContain('hello');
  });

  it('applyLink replaces the selection with the provided display text', async () => {
    const { fixture, host } = await setupLinkModalHost((host) => host.value.set('old'));
    editorOf(rteOf(fixture)).commands.selectAll();

    rteOf(fixture).applyLink({ href: 'https://x.test', text: 'new label', title: '', target: '' });
    await fixture.whenStable();

    const last = host.changes().at(-1) ?? '';
    expect(last).toContain('>new label</a>');
    expect(last).not.toContain('old');
  });

  it('applyLink with a blank href removes the link', async () => {
    const { fixture, host } = await setupLinkModalHost((host) =>
      host.value.set('<a href="https://x.test">hello</a>'),
    );
    editorOf(rteOf(fixture)).commands.selectAll();

    rteOf(fixture).applyLink({ href: '', text: '', title: '', target: '' });
    await fixture.whenStable();

    expect(host.changes().at(-1) ?? '').not.toContain('<a');
  });

  it('code-view toggle swaps to a raw-HTML textarea whose edits flow to the value', async () => {
    const { fixture, host } = await setupValueHost((host) => host.value.set('<p>x</p>'));

    findButton(fixture, 'codeView').click();
    await fixture.whenStable();

    const textarea = fixture.nativeElement.querySelector('.rte-code') as HTMLTextAreaElement;
    expect(textarea).not.toBeNull();

    textarea.value = '<p>edited</p>';
    textarea.dispatchEvent(new Event('input'));
    await fixture.whenStable();

    expect(host.changes().at(-1)).toContain('edited');
  });
});
