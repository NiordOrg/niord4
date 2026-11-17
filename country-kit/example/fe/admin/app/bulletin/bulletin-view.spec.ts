import { TestBed } from '@angular/core/testing';
import { TranslocoService } from '@jsverse/transloco';
import { provideTestTransloco } from '@niord/admin/api/testing';
import { describe, expect, it } from 'vitest';

import english from '../../assets/branding/en.json';
import { BulletinView, type BulletinPromulgation } from './bulletin-view';

/** The example's English branding. */
const branding: Readonly<Record<string, string>> = english;

const bulletin = {
  type: { typeId: 'xx-bulletin', serviceId: 'xx-bulletin', name: 'Harbour bulletin' },
  promulgate: true,
} as const;

function render(promulgation: BulletinPromulgation): HTMLElement {
  TestBed.configureTestingModule({ providers: [provideTestTransloco()] });
  TestBed.inject(TranslocoService).setTranslation(branding, 'en');
  const fixture = TestBed.createComponent(BulletinView);
  fixture.componentRef.setInput('promulgation', promulgation);
  fixture.detectChanges();
  return fixture.nativeElement as HTMLElement;
}

describe('BulletinView (example country)', () => {
  it('shows the bulletin title and its text as plain text, its line breaks kept', () => {
    const view = render({ ...bulletin, text: 'Quay 3 closed\n<b>until noon</b>' });
    const body = view.querySelector('div');

    expect(view.querySelector('p')?.textContent).toBe(branding['country.bulletin.title']);
    expect(view.querySelector('b')).toBeNull();
    expect(body?.querySelectorAll('br')).toHaveLength(1);
    // The pipe writes each space as a no-break space between zero-width spaces.
    expect(body?.textContent?.replace(/\u200b/g, '').replace(/\u00a0/g, ' ')).toBe(
      'Quay 3 closed<b>until noon</b>',
    );
  });

  it('shows only the title when the bulletin has no text', () => {
    const view = render({ ...bulletin });

    expect(view.querySelector('div')).toBeNull();
    expect(view.querySelectorAll('p')).toHaveLength(1);
  });
});
