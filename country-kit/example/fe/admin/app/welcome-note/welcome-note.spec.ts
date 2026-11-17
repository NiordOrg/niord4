import { TestBed } from '@angular/core/testing';
import { TranslocoService } from '@jsverse/transloco';
import { provideTestTransloco } from '@niord/admin/api/testing';
import { describe, expect, it } from 'vitest';

import english from '../../assets/branding/en.json';
import { WelcomeNote } from './welcome-note';

/** The example's English branding. */
const branding: Readonly<Record<string, string>> = english;

describe('WelcomeNote (example country)', () => {
  it('shows the note of its branding', () => {
    TestBed.configureTestingModule({ providers: [provideTestTransloco()] });
    TestBed.inject(TranslocoService).setTranslation(branding, 'en');
    const fixture = TestBed.createComponent(WelcomeNote);
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).textContent?.trim()).toBe(
      branding['country.welcome.note'],
    );
  });
});
