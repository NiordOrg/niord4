import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { disabled, form, FormField, required } from '@angular/forms/signals';
import { describe, expect, it } from 'vitest';

import { CodeTextInput } from './code-text-input';

@Component({
  imports: [CodeTextInput, FormField],
  template: `<app-code-text-input [rows]="rows" [ariaLabel]="ariaLabel" [formField]="form.code" />`,
})
class Host {
  rows = 8;
  ariaLabel: string | undefined = 'Value';
  readonly off = signal(false);
  private readonly data = signal<{ code: string }>({ code: 'initial' });
  readonly form = form(this.data, (path) => disabled(path.code, { when: () => this.off() }));
}

function setup(configure?: (host: Host) => void) {
  TestBed.configureTestingModule({ imports: [Host] });
  const fixture = TestBed.createComponent(Host);
  configure?.(fixture.componentInstance);
  fixture.detectChanges();
  return {
    fixture,
    host: fixture.componentInstance,
    textarea: fixture.nativeElement.querySelector('textarea') as HTMLTextAreaElement,
  };
}

describe('CodeTextInput', () => {
  it('round-trips the string form value through the textarea', () => {
    const { host, textarea, fixture } = setup();
    expect(textarea.value).toBe('initial');

    textarea.value = '{ "a": 1 }';
    textarea.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    expect(host.form.code().value()).toBe('{ "a": 1 }');
  });

  it('honors the rows input and the pre-translated aria-label', () => {
    const { textarea } = setup((host) => {
      host.rows = 4;
      host.ariaLabel = 'Setting value';
    });
    expect(textarea.rows).toBe(4);
    expect(textarea.getAttribute('aria-label')).toBe('Setting value');
  });

  it('reflects the form-driven disabled state', () => {
    const { host, textarea, fixture } = setup();
    host.off.set(true);
    fixture.detectChanges();
    expect(textarea.disabled).toBe(true);
  });

  it('shows an empty value as an empty textarea', () => {
    const { host, textarea, fixture } = setup();
    host.form.code().value.set('');
    fixture.detectChanges();
    expect(textarea.value).toBe('');
  });
});

@Component({
  imports: [CodeTextInput, FormField],
  template: `<app-code-text-input [formField]="form.code" />`,
})
class RequiredHost {
  private readonly data = signal<{ code: string }>({ code: '' });
  readonly form = form(this.data, (path) => required(path.code));
}

describe('CodeTextInput — validation comes from the form, not the control', () => {
  it('carries no baked-in validation — validity is entirely the form schema', () => {
    TestBed.configureTestingModule({ imports: [RequiredHost] });
    const fixture = TestBed.createComponent(RequiredHost);
    fixture.detectChanges();
    const host = fixture.componentInstance;

    // The control adds no validators; the schema `required` on an empty value is the
    // only source of invalidity.
    expect(host.form.code().invalid()).toBe(true);

    host.form.code().value.set('x');
    fixture.detectChanges();
    expect(host.form.code().valid()).toBe(true);
  });
});
