import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { TranslocoService } from '@jsverse/transloco';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';

import { LanguageService } from '../../../core/main-shell/services/language.service';
import { provideTestTransloco } from '../../testing/transloco-testing';
import { LocalizedInputs, type LocalizedValueInput } from './localized-inputs';

// Three configured languages — the N-language contract; a two-language deployment must never
// be baked in. `kl` exercises a third language beyond the usual en/da pair.
const THREE_LANGUAGES = [
  { label: 'English', value: 'en', locale: 'en-GB', flagPath: 'assets/img/flags/en.png' },
  { label: 'Dansk', value: 'da', locale: 'da', flagPath: 'assets/img/flags/da.png' },
  { label: 'Kalaallisut', value: 'kl', locale: 'kl', flagPath: 'assets/img/flags/kl.png' },
];

@Component({
  imports: [LocalizedInputs],
  template: `<app-localized-inputs
    [languages]="languages()"
    [value]="value()"
    [disabled]="disabled()"
    [multiline]="multiline()"
    [stacked]="stacked()"
    [placeholderKey]="placeholderKey()"
    (valueInput)="edits.push($event)"
    (inputFocus)="focused.set($event)"
  />`,
})
class Host {
  readonly languages = signal<readonly string[] | undefined>(undefined);
  readonly value = signal<Record<string, string>>({});
  readonly disabled = signal(false);
  readonly multiline = signal(false);
  readonly stacked = signal(false);
  readonly placeholderKey = signal('');
  readonly edits: LocalizedValueInput[] = [];
  readonly focused = signal<string | null>(null);
}

function setup(configure?: (host: Host) => void) {
  TestBed.configureTestingModule({
    providers: [
      provideTestTransloco(),
      { provide: LanguageService, useValue: { modelLanguages: THREE_LANGUAGES } },
    ],
  });
  const fixture = TestBed.createComponent(Host);
  configure?.(fixture.componentInstance);
  fixture.detectChanges();
  return { fixture, host: fixture.componentInstance, el: fixture.nativeElement as HTMLElement };
}

function inputs(el: HTMLElement): HTMLInputElement[] {
  return Array.from(el.querySelectorAll('input'));
}

describe('LocalizedInputs', () => {
  it('renders one input per configured language with its flag painted inside', () => {
    const { el } = setup();
    expect(inputs(el)).toHaveLength(3);
    // One flag <img> per language, in configured order, sitting inside the field.
    const flags = Array.from(el.querySelectorAll('img')).map((img) => img.getAttribute('src'));
    expect(flags).toEqual([
      'assets/img/flags/en.png',
      'assets/img/flags/da.png',
      'assets/img/flags/kl.png',
    ]);
    // `pl-9` reserves room for the inline flag on every field.
    expect(inputs(el).every((input) => input.className.includes('pl-9'))).toBe(true);
  });

  it('displays the per-language values from the value record', () => {
    const { el } = setup((host) => host.value.set({ en: 'Hello', da: 'Hej' }));
    const [en, da, kl] = inputs(el);
    expect(en.value).toBe('Hello');
    expect(da.value).toBe('Hej');
    expect(kl.value).toBe('');
  });

  it('emits {lang,value} per keystroke', () => {
    const { el, host } = setup();
    const da = inputs(el)[1];
    da.value = 'typed';
    da.dispatchEvent(new Event('input'));
    expect(host.edits).toEqual([{ lang: 'da', value: 'typed' }]);
  });

  it('emits the focused language', () => {
    const { el, host } = setup();
    inputs(el)[2].dispatchEvent(new Event('focus'));
    expect(host.focused()).toBe('kl');
  });

  it('locks every input when disabled', () => {
    const { el } = setup((host) => host.disabled.set(true));
    expect(inputs(el).every((input) => input.disabled)).toBe(true);
  });

  it('honors the languages override and shows the code of a language without a flag', () => {
    const { el } = setup((host) => host.languages.set(['en', 'fr']));
    expect(inputs(el)).toHaveLength(2);
    const flags = Array.from(el.querySelectorAll('img')).map((img) => img.getAttribute('src'));
    expect(flags).toEqual(['assets/img/flags/en.png']);
    const marks = Array.from(el.querySelectorAll('app-language-flag')).map((flag) =>
      flag.textContent?.trim(),
    );
    expect(marks).toEqual(['', 'FR']);
  });

  it('renders textareas in multiline mode', () => {
    const { el } = setup((host) => host.multiline.set(true));
    expect(el.querySelectorAll('textarea')).toHaveLength(3);
    expect(el.querySelectorAll('input')).toHaveLength(0);
  });

  it('drops the 2-column grid when stacked (vertical layout)', () => {
    const { el } = setup((host) => host.stacked.set(true));
    const grid = el.querySelector('div');
    expect(grid?.className).toContain('grid');
    expect(grid?.className).not.toContain('grid-cols-2');
  });

  it('gives each input the aria-label key resolved with the language label', () => {
    const { el } = setup();
    // The stub loader has no bundle, so the key echoes — proving the pipe ran with the param.
    expect(inputs(el)[0].getAttribute('aria-label')).toBe('shared.forms.localizedInputs.inputAria');
  });

  it('resolves placeholderKey into each row’s own language', async () => {
    TestBed.configureTestingModule({
      providers: [
        provideTestTransloco(),
        { provide: LanguageService, useValue: { modelLanguages: THREE_LANGUAGES } },
      ],
    });
    // selectTranslate resolves the key in the requested language — assert each row gets its own.
    const transloco = TestBed.inject(TranslocoService);
    vi.spyOn(transloco, 'selectTranslate').mockImplementation(((
      key: string,
      _params: unknown,
      lang: string,
    ) => of(`${key}:${lang}`)) as never);
    const fixture = TestBed.createComponent(Host);
    fixture.componentInstance.placeholderKey.set('editor.field.areas.vicinityPlaceholder');
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    const [en, da, kl] = inputs(fixture.nativeElement as HTMLElement);
    expect(en.placeholder).toBe('editor.field.areas.vicinityPlaceholder:en');
    expect(da.placeholder).toBe('editor.field.areas.vicinityPlaceholder:da');
    expect(kl.placeholder).toBe('editor.field.areas.vicinityPlaceholder:kl');
  });
});
