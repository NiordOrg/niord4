import { TestBed } from '@angular/core/testing';
import { TranslocoService } from '@jsverse/transloco';
import { provideTestTransloco } from '@niord/admin/api/testing';
import { describe, expect, it } from 'vitest';

import english from '../../assets/branding/en.json';
import { HarbourPage, NOTES_SIZE } from './harbour-page';

/** The example's English branding. */
const branding: Readonly<Record<string, string>> = english;

describe('HarbourPage (example country)', () => {
  it('shows the title and the text of its branding', () => {
    TestBed.configureTestingModule({ providers: [provideTestTransloco()] });
    TestBed.inject(TranslocoService).setTranslation(branding, 'en');
    const fixture = TestBed.createComponent(HarbourPage);
    fixture.detectChanges();
    const page = fixture.nativeElement as HTMLElement;

    expect(page.querySelector('h1')?.textContent).toBe(branding['country.harbour.title']);
    expect(page.textContent).toContain(branding['country.harbour.text']);
  });

  it('states the size of the notes, formatted by the country dependency', () => {
    TestBed.configureTestingModule({ providers: [provideTestTransloco()] });
    TestBed.inject(TranslocoService).setTranslation(branding, 'en');
    const fixture = TestBed.createComponent(HarbourPage);
    fixture.detectChanges();
    const size = (fixture.nativeElement as HTMLElement).querySelectorAll('p')[1];

    expect(NOTES_SIZE).toBe(1_337_000);
    expect(size?.textContent).toBe(
      branding['country.harbour.size'].replace('{{ size }}', '1.34 MB'),
    );
  });
});
