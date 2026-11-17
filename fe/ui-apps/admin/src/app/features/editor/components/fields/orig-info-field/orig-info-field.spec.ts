import { Component, signal } from '@angular/core';
import { form } from '@angular/forms/signals';
import { TestBed } from '@angular/core/testing';
import { TranslocoService } from '@jsverse/transloco';
import { describe, expect, it, vi } from 'vitest';

import { AuthFacade } from '../../../../../core/auth/facades/auth.facade';
import { provideTestTransloco } from '../../../../../shared/testing/transloco-testing';
import { EditorFacade } from '../../../services/editor-facade';
import { EditorFieldsService } from '../../../services/editor-fields.service';
import { OrigInfoField } from './orig-info-field';

/** Minimal {@link EditorFieldsService} contract the field-row + field read. */
class StubFields {
  readonly expanded = signal(true);
  isExpanded = () => this.expanded();
  edited = () => false;
  toggle = vi.fn();
  expand = vi.fn();
  collapse = vi.fn();
  registerLabel = vi.fn();
  unregisterLabel = vi.fn();
  focusNext = vi.fn();
  focusPrevious = vi.fn();
}

class StubFacade {
  readonly editableNowState = signal(true);
  editableNow = () => this.editableNowState();
}

function authStub(isEditor = true): AuthFacade {
  return {
    hasRole: (role: string) => signal(role === 'editor' ? isEditor : false),
  } as unknown as AuthFacade;
}

@Component({
  imports: [OrigInfoField],
  template: `<app-orig-info-field [field]="form.originalInformation" />`,
})
class Host {
  readonly model = signal({ originalInformation: false });
  readonly form = form(this.model);
}

function setup() {
  const fields = new StubFields();
  const facade = new StubFacade();
  TestBed.configureTestingModule({
    providers: [
      provideTestTransloco(),
      { provide: EditorFieldsService, useValue: fields },
      { provide: EditorFacade, useValue: facade },
      { provide: AuthFacade, useValue: authStub(true) },
    ],
  });
  TestBed.inject(TranslocoService).load('en').subscribe();
  const fixture = TestBed.createComponent(Host);
  fixture.autoDetectChanges();
  const el = fixture.nativeElement as HTMLElement;
  return { fixture, host: fixture.componentInstance, fields, facade, el };
}

describe('OrigInfoField', () => {
  it('shows the empty summary when not marked and the star viewer when marked (collapsed)', async () => {
    const { fixture, host, fields, el } = setup();
    fields.expanded.set(false);
    await fixture.whenStable();
    expect(el.querySelector('.ed-row-val.empty')?.textContent).toContain(
      'editor.field.origInfo.empty',
    );

    host.form.originalInformation().value.set(true);
    await fixture.whenStable();
    expect(el.querySelector('.ed-row-val.empty')).toBeNull();
    expect(el.textContent).toContain('editor.field.origInfo.set');
  });

  it('toggles originalInformation through the star button (single write path — the field signal)', async () => {
    const { fixture, host, el } = setup();
    await fixture.whenStable();
    const button = el.querySelector<HTMLButtonElement>('button[aria-pressed]')!;
    expect(button.getAttribute('aria-pressed')).toBe('false');

    button.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    await fixture.whenStable();
    expect(host.form.originalInformation().value()).toBe(true);
    expect(button.getAttribute('aria-pressed')).toBe('true');
  });

  it('does not toggle when not editable and disables the button', async () => {
    const { fixture, host, facade, el } = setup();
    facade.editableNowState.set(false);
    await fixture.whenStable();
    const button = el.querySelector<HTMLButtonElement>('button[aria-pressed]')!;
    expect(button.disabled).toBe(true);

    button.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    await fixture.whenStable();
    expect(host.form.originalInformation().value()).toBe(false);
  });
});
