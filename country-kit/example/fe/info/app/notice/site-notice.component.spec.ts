import { readFileSync } from 'node:fs';
import { TestBed } from '@angular/core/testing';
import { TranslocoService } from '@jsverse/transloco';
import { COMPONENT_SLOTS } from '@niord/info/api';
import { getTranslocoTestingModule } from '@niord/info/api/testing';
import { describe, expect, it } from 'vitest';

import { provideCountry } from '../country.providers';
import { SiteNoticeComponent } from './site-notice.component';

/** The example's branding, relative to the workspace folder the runner starts in. */
const branding = JSON.parse(readFileSync('info/assets/branding/zz.json', 'utf8')) as Record<
  string,
  string
>;

describe('SiteNoticeComponent (example country)', () => {
  it('shows the notice of its branding', () => {
    TestBed.configureTestingModule({ imports: [getTranslocoTestingModule({ zz: branding })] });
    TestBed.inject(TranslocoService).setActiveLang('zz');
    const fixture = TestBed.createComponent(SiteNoticeComponent);
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).textContent?.trim()).toBe(
      branding['country.notice.text'],
    );
  });

  it('is what provideCountry adds above the footer, loaded on demand', async () => {
    TestBed.configureTestingModule({ providers: [provideCountry()] });

    const slots = TestBed.inject(COMPONENT_SLOTS);
    expect(slots.map((registration) => registration.slot)).toEqual(['layout.beforeFooter']);
    expect(await slots[0].load()).toBe(SiteNoticeComponent);
  });
});
