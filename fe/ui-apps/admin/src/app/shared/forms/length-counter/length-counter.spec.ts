import { TestBed } from '@angular/core/testing';
import { TranslocoService } from '@jsverse/transloco';
import { describe, expect, it } from 'vitest';

import en from '../../../../../../../languages/en/i18n/admin/en.json';
import { provideTestTransloco } from '../../testing/transloco-testing';
import {
  LengthCounter,
  lengthError,
  lengthErrorAny,
  TOO_LONG,
  textLength,
  tooLong,
} from './length-counter';

function setup(inputs: { text: string; limit: number; counterId?: string; language?: string }) {
  TestBed.configureTestingModule({ providers: [provideTestTransloco()] });
  TestBed.inject(TranslocoService).setTranslation(en, 'en');
  const fixture = TestBed.createComponent(LengthCounter);
  fixture.componentRef.setInput('text', inputs.text);
  fixture.componentRef.setInput('limit', inputs.limit);
  if (inputs.counterId) {
    fixture.componentRef.setInput('counterId', inputs.counterId);
  }
  if (inputs.language) {
    fixture.componentRef.setInput('language', inputs.language);
  }
  fixture.detectChanges();
  const line = (): HTMLParagraphElement =>
    (fixture.nativeElement as HTMLElement).querySelector('p') as HTMLParagraphElement;
  return { fixture, line };
}

describe('textLength', () => {
  it('counts characters as the database does, not UTF-16 units', () => {
    expect(textLength('abc')).toBe(3);
    // One character outside the basic plane: two UTF-16 units, one character.
    expect('\u{1F6A2}'.length).toBe(2);
    expect(textLength('\u{1F6A2}')).toBe(1);
    expect(textLength('')).toBe(0);
    expect(textLength(null)).toBe(0);
    expect(textLength(undefined)).toBe(0);
  });
});

describe('tooLong and the form rules', () => {
  it('lets a text of exactly the limit through and refuses one character more', () => {
    expect(tooLong('a'.repeat(255), 255)).toBe(false);
    expect(tooLong('a'.repeat(256), 255)).toBe(true);
    expect(lengthError('a'.repeat(255), 255)).toBeNull();
    expect(lengthError('a'.repeat(256), 255)).toEqual({ kind: TOO_LONG });
  });

  it('refuses a map of per-language texts when any one of them is too long', () => {
    expect(lengthErrorAny({ en: 'a'.repeat(255), da: '' }, 255)).toBeNull();
    expect(lengthErrorAny({ en: 'short', da: 'a'.repeat(256) }, 255)).toEqual({
      kind: TOO_LONG,
    });
    expect(lengthErrorAny({}, 255)).toBeNull();
  });
});

describe('LengthCounter', () => {
  it('says how many of the limit are used, muted, while the text fits', () => {
    const { line } = setup({ text: 'Skagerrak', limit: 255, counterId: 'name-counter' });

    expect(line().textContent?.trim()).toBe('9 of 255 characters');
    expect(line().id).toBe('name-counter');
    expect(line().classList).toContain('text-fg-muted');
    expect(line().getAttribute('role')).toBeNull();
  });

  it('says by how much a pasted text is too long, in red and as an alert, and keeps counting all of it', () => {
    const { line } = setup({ text: 'a'.repeat(300), limit: 255, counterId: 'name-counter' });

    expect(line().textContent?.trim()).toBe('300 of 255 characters, 45 too many');
    expect(line().id).toBe('name-counter');
    expect(line().classList).toContain('text-danger');
    expect(line().getAttribute('role')).toBe('alert');
  });

  it('follows the text as it changes', () => {
    const { fixture, line } = setup({ text: 'a'.repeat(256), limit: 255 });
    expect(line().getAttribute('role')).toBe('alert');

    fixture.componentRef.setInput('text', 'a'.repeat(255));
    fixture.detectChanges();

    expect(line().textContent?.trim()).toBe('255 of 255 characters');
    expect(line().getAttribute('role')).toBeNull();
  });

  it('names the language when it counts for one row of a group', () => {
    const { fixture, line } = setup({ text: 'Notice', limit: 255, language: 'English' });
    expect(line().textContent?.trim()).toBe('English: 6 of 255 characters');

    fixture.componentRef.setInput('text', 'a'.repeat(257));
    fixture.detectChanges();
    expect(line().textContent?.trim()).toBe('English: 257 of 255 characters, 2 too many');
  });
});
