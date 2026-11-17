import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { TranslocoService } from '@jsverse/transloco';
import { describe, expect, it, vi } from 'vitest';

import { AuthFacade } from '../../../../core/auth/facades/auth.facade';
import { provideTestTransloco } from '../../../../shared/testing/transloco-testing';
import { EditorFieldsService } from '../../services/editor-fields.service';
import { EditorFieldRow } from './editor-field-row';

/**
 * Stub of the sibling {@link EditorFieldsService} contract this row consumes: `isExpanded`
 * reads a signal so the viewer↔editor swap stays reactive, while the mutators and the
 * focus-registry methods are spies the keyboard/click tests assert against.
 */
class StubEditorFieldsService {
  readonly expandedState = signal(false);
  isExpanded = vi.fn(() => this.expandedState());
  toggle = vi.fn();
  expand = vi.fn();
  collapse = vi.fn();
  registerLabel = vi.fn();
  unregisterLabel = vi.fn();
  focusNext = vi.fn();
  focusPrevious = vi.fn();
}

function authFacadeStub(isEditor: boolean): AuthFacade {
  return {
    hasRole: (role: string) => signal(role === 'editor' ? isEditor : false),
  } as unknown as AuthFacade;
}

@Component({
  imports: [EditorFieldRow],
  template: `
    <app-editor-field-row
      [fieldId]="fieldId()"
      [index]="index()"
      [label]="label()"
      [required]="required()"
      [invalid]="invalid()"
      [edited]="edited()"
      [empty]="empty()"
    >
      <div fieldViewer data-testid="viewer">VIEWER</div>
      <div fieldEditor data-testid="editor">EDITOR</div>
    </app-editor-field-row>
  `,
})
class HostComponent {
  readonly fieldId = signal('title');
  readonly index = signal(0);
  readonly label = signal('editor.field.title');
  readonly required = signal(false);
  readonly invalid = signal(false);
  readonly edited = signal(false);
  readonly empty = signal(false);
}

function setup(opts: { isEditor?: boolean } = {}) {
  const service = new StubEditorFieldsService();
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({
    providers: [
      provideTestTransloco(),
      { provide: EditorFieldsService, useValue: service },
      { provide: AuthFacade, useValue: authFacadeStub(opts.isEditor ?? true) },
    ],
  });
  TestBed.inject(TranslocoService).load('en').subscribe();

  const fixture = TestBed.createComponent(HostComponent);
  fixture.autoDetectChanges();
  const host = fixture.componentInstance;
  const el = fixture.nativeElement as HTMLElement;
  const row = () => el.querySelector('.ed-row');
  const head = () => el.querySelector<HTMLElement>('.ed-row-head');
  const viewer = () => el.querySelector('[data-testid="viewer"]');
  const editor = () => el.querySelector('[data-testid="editor"]');
  const editorContainer = () => el.querySelector('.ed-row-editor');
  const dot = () => el.querySelector('.ed-row-dot');
  const label = () => el.querySelector('.ed-row-label');
  const emptySummary = () => el.querySelector('.ed-row-val.empty');
  const editingSummary = () => el.querySelector('.ed-row-val.editing');
  return {
    fixture,
    host,
    service,
    el,
    row,
    head,
    viewer,
    editor,
    editorContainer,
    dot,
    label,
    emptySummary,
    editingSummary,
  };
}

function press(target: Element, key: string): KeyboardEvent {
  const event = new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true });
  target.dispatchEvent(event);
  return event;
}

describe('EditorFieldRow', () => {
  it('shows the viewer collapsed and swaps to the editor when expanded (editor)', async () => {
    const { fixture, service, viewer, editor, editingSummary } = setup({ isEditor: true });
    await fixture.whenStable();
    expect(viewer()).not.toBeNull();
    expect(editor()).toBeNull();
    expect(editingSummary()).toBeNull();

    service.expandedState.set(true);
    await fixture.whenStable();
    expect(editor()).not.toBeNull();
    expect(viewer()).toBeNull();
    // Open rows show the localized "editing…" placeholder in the value cell.
    expect(editingSummary()).not.toBeNull();
    expect(editingSummary()!.textContent?.trim()).toBe('editor.row.editing');
  });

  it('makes the head a button with aria-expanded reflecting the open state (editor)', async () => {
    const { fixture, service, head } = setup({ isEditor: true });
    await fixture.whenStable();
    expect(head()!.getAttribute('role')).toBe('button');
    expect(head()!.getAttribute('aria-expanded')).toBe('false');

    service.expandedState.set(true);
    await fixture.whenStable();
    expect(head()!.getAttribute('aria-expanded')).toBe('true');
  });

  it('gives a non-editor the viewer only — no interactive head, no editor even when expanded', async () => {
    const { fixture, service, head, viewer, editor } = setup({ isEditor: false });
    service.expandedState.set(true);
    await fixture.whenStable();
    expect(head()!.getAttribute('role')).toBeNull();
    expect(head()!.classList).not.toContain('interactive');
    expect(viewer()).not.toBeNull();
    expect(editor()).toBeNull();
  });

  it('toggles the field on a head click', async () => {
    const { fixture, service, head } = setup({ isEditor: true });
    await fixture.whenStable();
    head()!.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    expect(service.toggle).toHaveBeenCalledWith('title', 0);
  });

  it('drives expand/collapse/toggle and cross-row focus from the keyboard (editors)', async () => {
    const { fixture, service, head } = setup({ isEditor: true });
    await fixture.whenStable();
    const el = head()!;

    expect(press(el, 'ArrowRight').defaultPrevented).toBe(true);
    expect(service.expand).toHaveBeenCalledWith('title', 0);

    expect(press(el, 'ArrowLeft').defaultPrevented).toBe(true);
    expect(service.collapse).toHaveBeenCalledWith('title', 0);

    press(el, ' ');
    press(el, 'Enter');
    expect(service.toggle).toHaveBeenCalledTimes(2);

    expect(press(el, 'ArrowUp').defaultPrevented).toBe(true);
    expect(service.focusPrevious).toHaveBeenCalledWith(el);

    expect(press(el, 'ArrowDown').defaultPrevented).toBe(true);
    expect(service.focusNext).toHaveBeenCalledWith(el);
  });

  it('ignores unhandled keys without preventing default or mutating state', async () => {
    const { fixture, service, head } = setup({ isEditor: true });
    await fixture.whenStable();
    const event = press(head()!, 'a');
    expect(event.defaultPrevented).toBe(false);
    expect(service.expand).not.toHaveBeenCalled();
    expect(service.toggle).not.toHaveBeenCalled();
  });

  it('does not toggle or navigate from a non-editor head', async () => {
    const { fixture, service, head } = setup({ isEditor: false });
    await fixture.whenStable();
    head()!.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    press(head()!, 'Enter');
    expect(service.toggle).not.toHaveBeenCalled();
  });

  it('registers its head with the focus registry on render and hands it back on destroy', async () => {
    const { fixture, service, head } = setup({ isEditor: true });
    await fixture.whenStable();
    const el = head()!;
    expect(service.registerLabel).toHaveBeenCalledWith(el);

    fixture.destroy();
    expect(service.unregisterLabel).toHaveBeenCalledWith(el);
  });

  it('does not register a head for a non-editor (no keyboard nav)', async () => {
    const { fixture, service } = setup({ isEditor: false });
    await fixture.whenStable();
    expect(service.registerLabel).not.toHaveBeenCalled();
  });

  it('shows the edited dot before the label text only when [edited] is set', async () => {
    const { fixture, host, dot, label } = setup({ isEditor: true });
    await fixture.whenStable();
    expect(dot()).toBeNull();

    host.edited.set(true);
    await fixture.whenStable();
    expect(dot()).not.toBeNull();
    // The dot renders LEFT of the label text (first child of the label cell).
    expect(label()!.firstElementChild?.classList.contains('ed-row-dot')).toBe(true);
  });

  it('applies the invalid row treatment only when [invalid] is set', async () => {
    const { fixture, host, row } = setup({ isEditor: true });
    await fixture.whenStable();
    expect(row()!.classList).not.toContain('invalid');

    host.invalid.set(true);
    await fixture.whenStable();
    expect(row()!.classList).toContain('invalid');
  });

  it('renders the required asterisk only when [required] is set', async () => {
    const { fixture, host, head } = setup({ isEditor: true });
    await fixture.whenStable();
    expect(head()!.textContent).not.toContain('*');

    host.required.set(true);
    await fixture.whenStable();
    expect(head()!.textContent).toContain('*');
  });

  it('renders the italic empty summary in place of the viewer when [empty] is set', async () => {
    const { fixture, host, viewer, emptySummary } = setup({ isEditor: true });
    await fixture.whenStable();
    expect(viewer()).not.toBeNull();
    expect(emptySummary()).toBeNull();

    host.empty.set(true);
    await fixture.whenStable();
    expect(viewer()).toBeNull();
    expect(emptySummary()).not.toBeNull();
    expect(emptySummary()!.textContent?.trim()).toBe('editor.field.empty');
  });
});
