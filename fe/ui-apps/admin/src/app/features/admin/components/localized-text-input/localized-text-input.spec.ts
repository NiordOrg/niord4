import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { disabled, form, FormField } from '@angular/forms/signals';
import { TranslocoService } from '@jsverse/transloco';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';

import type { FormatToken } from '../../../../shared/forms/format-token-input/token-insert';
import { provideNiordIcons } from '../../../../shared/icons/niord-icons';
import { provideTestLanguages } from '../../../../shared/testing/language-testing';
import { provideTestTransloco } from '../../../../shared/testing/transloco-testing';
import { LocalizedTextInput } from './localized-text-input';

// Binds the component the way production does — Signal Forms' [formField] against a form()
// backed by a data signal — so the real value round-trip and field-driven disabled state are
// exercised (not Angular's reactive-forms auto-bridge).
@Component({
  imports: [LocalizedTextInput, FormField],
  template: `
    <app-localized-text-input
      [lang]="lang()"
      [multiline]="multiline()"
      [tokens]="tokens()"
      [placeholderKey]="placeholderKey()"
      [formField]="f.text"
    />
  `,
})
class Host {
  readonly lang = signal('da');
  readonly multiline = signal(false);
  readonly disable = signal(false);
  readonly placeholderKey = signal('');
  readonly tokens = signal<readonly FormatToken[]>([]);
  readonly model = signal({ text: 'initial' });
  readonly f = form(this.model, (path) => {
    disabled(path.text, { when: () => this.disable() });
  });
}

function setup(configure?: (host: Host) => void) {
  TestBed.configureTestingModule({
    providers: [provideTestTransloco(), provideNiordIcons(), provideTestLanguages()],
  });
  const fixture = TestBed.createComponent(Host);
  configure?.(fixture.componentInstance);
  fixture.detectChanges();
  return { fixture, host: fixture.componentInstance, el: fixture.nativeElement as HTMLElement };
}

/** The control itself, for the insert the menu would command. */
function control(fixture: { debugElement: { children: { componentInstance: unknown }[] } }) {
  return fixture.debugElement.children[0].componentInstance as unknown as {
    insert(token: string): void;
  };
}

const TOKENS: readonly FormatToken[] = [
  { token: '${uge}', label: '${uge}' },
  { token: '${år}', label: '${år}' },
];

describe('LocalizedTextInput', () => {
  it('renders the language flag and round-trips the [formField] value', () => {
    const { el, host } = setup();
    const flag = el.querySelector('img');
    expect(flag?.getAttribute('src')).toContain('flags/da');
    expect(flag?.getAttribute('alt')).toBe(''); // decorative — the field's label carries the name
    const input = el.querySelector('input') as HTMLInputElement;
    expect(input.value).toBe('initial');
    // Fallback accessible name is the human language label, not the raw code.
    expect(input.getAttribute('aria-label')).toBe('Dansk');

    input.value = 'changed';
    input.dispatchEvent(new Event('input'));
    expect(host.model().text).toBe('changed');
  });

  it('shows the code, and no image, for a language the build ships no flag for', () => {
    const { el } = setup((host) => host.lang.set('kl'));
    expect(el.querySelector('img')).toBeNull();
    expect(el.querySelector('app-language-flag')?.textContent?.trim()).toBe('KL');
  });

  it('renders a textarea in multiline mode and honors the disabled field state', () => {
    const { el, host, fixture } = setup((host) => host.multiline.set(true));
    const textarea = el.querySelector('textarea') as HTMLTextAreaElement;
    expect(textarea).not.toBeNull();
    host.disable.set(true);
    fixture.detectChanges();
    expect(textarea.disabled).toBe(true);
  });

  /**
   * The insert menu is offered only where there is a vocabulary to offer.
   *
   * A prose field has none, and a permanently visible "Insert" button beside one
   * is a control that opens an empty list.
   */
  it('offers no insert menu without tokens, and one with them', () => {
    const { el, host, fixture } = setup();
    expect(el.querySelector('button')).toBeNull();

    host.tokens.set(TOKENS);
    fixture.detectChanges();

    const insert = el.querySelector('button') as HTMLButtonElement;
    expect(insert).not.toBeNull();
    expect(insert.textContent).toContain('shared.forms.formatTokenInput.insert');
  });

  /**
   * An untouched field appends.
   *
   * A text input reports `selectionStart: 0` before it was ever focused, so
   * trusting the selection would splice the token in front of a pattern somebody
   * had just opened for editing.
   */
  it('appends the token when the field has not been focused', () => {
    const { host, fixture } = setup((h) => h.tokens.set(TOKENS));

    control(fixture).insert('${uge}');
    fixture.detectChanges();

    expect(host.model().text).toBe('initial${uge}');
  });

  /** Once the caret has been in the field, the token lands where it was left. */
  it('splices at the caret and replaces a selection', () => {
    const { el, host, fixture } = setup((h) => h.tokens.set(TOKENS));
    const input = el.querySelector('input') as HTMLInputElement;

    input.dispatchEvent(new Event('focus'));
    input.setSelectionRange(0, 7);
    control(fixture).insert('${uge}');
    fixture.detectChanges();

    expect(host.model().text).toBe('${uge}');
  });

  it('renders no placeholder without a placeholderKey', () => {
    const { el } = setup();
    expect((el.querySelector('input') as HTMLInputElement).placeholder).toBe('');
  });

  /**
   * The hint is written in the language of the field it sits in, not the language
   * the operator happens to be running the app in — a Danish name field hints in
   * Danish beside an English one hinting in English.
   */
  it('resolves placeholderKey in the field’s own language', async () => {
    TestBed.configureTestingModule({
      providers: [provideTestTransloco(), provideNiordIcons()],
    });
    const transloco = TestBed.inject(TranslocoService);
    vi.spyOn(transloco, 'selectTranslate').mockImplementation(((
      key: string,
      _params: unknown,
      lang: string,
    ) => of(`${key}:${lang}`)) as never);

    const fixture = TestBed.createComponent(Host);
    fixture.componentInstance.placeholderKey.set('admin.publications.series.placeholder.name');
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    const el = fixture.nativeElement as HTMLElement;
    expect((el.querySelector('input') as HTMLInputElement).placeholder).toBe(
      'admin.publications.series.placeholder.name:da',
    );

    fixture.componentInstance.lang.set('en');
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    expect((el.querySelector('input') as HTMLInputElement).placeholder).toBe(
      'admin.publications.series.placeholder.name:en',
    );
  });

  /** A disabled field is not edited from its own menu either. */
  it('inserts nothing while the field is disabled', () => {
    const { host, fixture } = setup((h) => {
      h.tokens.set(TOKENS);
      h.disable.set(true);
    });

    control(fixture).insert('${uge}');
    fixture.detectChanges();

    expect(host.model().text).toBe('initial');
  });
});
