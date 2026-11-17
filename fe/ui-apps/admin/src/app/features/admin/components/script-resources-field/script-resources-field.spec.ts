import { Component, signal } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { form, FormField, pattern, required } from '@angular/forms/signals';
import { By } from '@angular/platform-browser';
import { describe, expect, it } from 'vitest';

import { APP_CONFIG } from '../../../../core/config/app-config.token';
import { provideTestTransloco } from '../../../../shared/testing/transloco-testing';
import { ScriptResourcesField } from './script-resources-field';

@Component({
  imports: [ScriptResourcesField, FormField],
  template: `<app-script-resources-field [formField]="$any(form.path)" [pathPattern]="pattern" />`,
})
class SingleHost {
  pattern = '.+\\.(ftl|FTL|js|JS)';
  private readonly data = signal<{ path: string }>({ path: '' });
  readonly form = form(this.data);
}

// Mirrors how a consumer (report-editor) applies validation: the CONTROL is a plain
// value control; the FORM schema owns required + the path pattern.
@Component({
  imports: [ScriptResourcesField, FormField],
  template: `<app-script-resources-field [formField]="$any(form.path)" [pathPattern]="pattern" />`,
})
class ValidatedHost {
  pattern = '.+\\.(ftl|FTL)';
  private readonly data = signal<{ path: string }>({ path: '' });
  readonly form = form(this.data, (path) => {
    required(path.path);
    pattern(path.path, () => /^(?:.+\.(ftl|FTL))$/);
  });
}

@Component({
  imports: [ScriptResourcesField, FormField],
  template: `<app-script-resources-field
    [formField]="$any(form.path)"
    [multiple]="true"
    [pathPattern]="pattern"
  />`,
})
class MultipleHost {
  pattern = '.+\\.(ftl|FTL|js|JS)';
  private readonly data = signal<{ path: string[] }>({ path: [] });
  readonly form = form(this.data);
}

function configure(): void {
  TestBed.configureTestingModule({
    providers: [
      provideTestTransloco(),
      provideHttpClient(),
      provideHttpClientTesting(),
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
    ],
  });
}

function fieldOf(fixture: ComponentFixture<unknown>): { paths: () => readonly string[] } {
  return fixture.debugElement.query(By.directive(ScriptResourcesField))
    .componentInstance as unknown as { paths: () => readonly string[] };
}

describe('ScriptResourcesField (single mode)', () => {
  it('round-trips the string value through the path input', () => {
    configure();
    const fixture = TestBed.createComponent(SingleHost);
    fixture.componentInstance.form.path().value.set('templates/a.ftl');
    fixture.detectChanges();

    const input = fixture.nativeElement.querySelector('input') as HTMLInputElement;
    expect(input.value).toBe('templates/a.ftl');

    input.value = 'templates/b.ftl';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    expect(fixture.componentInstance.form.path().value()).toBe('templates/b.ftl');
  });

  it('points the pencil at the editor with the path as an encoded query param', () => {
    configure();
    const fixture = TestBed.createComponent(SingleHost);
    fixture.componentInstance.form.path().value.set('templates/x.ftl');
    fixture.detectChanges();

    const link = fixture.nativeElement.querySelector('a.icon-button') as HTMLAnchorElement;
    expect(link.getAttribute('href')).toBe('/sysadmin/script-resources?path=templates%2Fx.ftl');
  });
});

describe('ScriptResourcesField — validation applied by the consumer schema', () => {
  it('is required and pattern-validated through the form field', () => {
    configure();
    const fixture = TestBed.createComponent(ValidatedHost);
    fixture.detectChanges();
    const path = () => fixture.componentInstance.form.path();

    expect(path().invalid()).toBe(true); // empty → required

    path().value.set('notes.txt');
    fixture.detectChanges();
    expect(path().invalid()).toBe(true); // wrong extension → pattern

    path().value.set('templates/ok.ftl');
    fixture.detectChanges();
    expect(path().valid()).toBe(true);
  });
});

describe('ScriptResourcesField (multiple mode)', () => {
  it('holds one row per path and keeps a ≥1-row invariant', () => {
    configure();
    const fixture = TestBed.createComponent(MultipleHost);
    fixture.componentInstance.form.path().value.set(['a.ftl', 'b.js']);
    fixture.detectChanges();
    expect(fieldOf(fixture).paths().length).toBe(2);

    // Writing an empty array still leaves a single blank row.
    fixture.componentInstance.form.path().value.set([]);
    fixture.detectChanges();
    expect(fieldOf(fixture).paths().length).toBe(1);
  });

  it('emits a string[] when a row is edited', () => {
    configure();
    const fixture = TestBed.createComponent(MultipleHost);
    fixture.componentInstance.form.path().value.set(['a.ftl']);
    fixture.detectChanges();

    const input = fixture.nativeElement.querySelector('input') as HTMLInputElement;
    input.value = 'changed.ftl';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    expect(fixture.componentInstance.form.path().value()).toEqual(['changed.ftl']);
  });
});
