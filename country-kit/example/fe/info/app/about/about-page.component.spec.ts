import { readFileSync } from 'node:fs';
import { TestBed } from '@angular/core/testing';
import { TranslocoService } from '@jsverse/transloco';
import { getTranslocoTestingModule } from '@niord/info/api/testing';
import { describe, expect, it } from 'vitest';

import routes from '../country.routes';
import { AboutPageComponent } from './about-page.component';

/** The example's branding, relative to the workspace folder the runner starts in. */
const branding = JSON.parse(readFileSync('info/assets/branding/zz.json', 'utf8')) as Record<
  string,
  string
>;

describe('AboutPageComponent (example country)', () => {
  it('shows the title and the text of its branding', () => {
    TestBed.configureTestingModule({ imports: [getTranslocoTestingModule({ zz: branding })] });
    TestBed.inject(TranslocoService).setActiveLang('zz');
    const fixture = TestBed.createComponent(AboutPageComponent);
    fixture.detectChanges();
    const page = fixture.nativeElement as HTMLElement;

    expect(page.querySelector('h1')?.textContent).toBe(branding['country.about.title']);
    expect(page.textContent).toContain(branding['country.about.text']);
  });

  it('is the page of the about route, loaded lazily', async () => {
    const about = routes.find((route) => route.path === 'about');
    expect(about?.component).toBeUndefined();
    expect(await about?.loadComponent?.()).toBe(AboutPageComponent);
  });
});
