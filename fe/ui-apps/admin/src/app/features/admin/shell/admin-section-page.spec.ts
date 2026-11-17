import { Component, Injectable, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { type Translation, type TranslocoLoader, provideTransloco } from '@jsverse/transloco';
import { type Observable, of } from 'rxjs';
import { describe, expect, it } from 'vitest';

import { AdminSectionPage } from './admin-section-page';

/**
 * Real phrases rather than the shared key-echoing stub: what is under test is the
 * interpolation of the description, which a stub that answers with the key hides.
 */
@Injectable({ providedIn: 'root' })
class Loader implements TranslocoLoader {
  getTranslation(): Observable<Translation> {
    return of<Translation>({
      'admin.sections.publications.label': 'Publications',
      'admin.publications.intro': 'Series and their issues — in the {{domain}} domain.',
      'admin.sources.intro': 'Add, modify and manage sources.',
    });
  }
}

@Component({
  imports: [AdminSectionPage],
  template: `<app-admin-section-page
    titleKey="admin.sections.publications.label"
    [descriptionKey]="descriptionKey()"
    [descriptionParams]="descriptionParams()"
    [showHeader]="showHeader()"
  >
    <p class="body">The section content</p>
  </app-admin-section-page>`,
})
class Host {
  readonly descriptionKey = signal<string | undefined>('admin.publications.intro');
  readonly descriptionParams = signal<Record<string, unknown> | undefined>({ domain: 'NM Annex' });
  readonly showHeader = signal(true);
}

function setup() {
  TestBed.configureTestingModule({
    providers: [
      provideTransloco({
        config: { availableLangs: ['en'], defaultLang: 'en' },
        loader: Loader,
      }),
    ],
  });
  const fixture = TestBed.createComponent(Host);
  fixture.detectChanges();
  return {
    fixture,
    host: fixture.componentInstance,
    element: fixture.nativeElement as HTMLElement,
  };
}

describe('AdminSectionPage', () => {
  it('renders the title and the description above the section content', () => {
    const { element } = setup();

    expect(element.querySelector('header h2')?.textContent).toContain('Publications');
    expect(element.querySelector('.body')).not.toBeNull();
  });

  /**
   * A section scoped to something chosen elsewhere names it, which needs the
   * value at render time rather than one key per scope.
   */
  it('interpolates the description parameters', () => {
    const { element } = setup();

    expect(element.querySelector('header p')?.textContent).toContain('in the NM Annex domain');
  });

  /** Params are optional: a description with nothing to fill in renders as written. */
  it('renders a description that takes no parameters', () => {
    const { fixture, host, element } = setup();
    host.descriptionKey.set('admin.sources.intro');
    host.descriptionParams.set(undefined);
    fixture.detectChanges();

    expect(element.querySelector('header p')?.textContent).toContain(
      'Add, modify and manage sources.',
    );
  });

  /**
   * ONE HEADER PER LEVEL.
   *
   * A section whose content drills into a record drawing its own header cluster
   * hides this one, so the title of the screen the reader left does not sit above
   * the screen they are on. The projected content stays either way.
   */
  it('draws no header at all when the section suppresses it', () => {
    const { fixture, host, element } = setup();
    host.showHeader.set(false);
    fixture.detectChanges();

    expect(element.querySelector('header')).toBeNull();
    expect(element.textContent).not.toContain('Publications');
    expect(element.querySelector('.body')).not.toBeNull();
  });

  /** No description key, no paragraph — the title stands alone. */
  it('omits the description paragraph without a key', () => {
    const { fixture, host, element } = setup();
    host.descriptionKey.set(undefined);
    fixture.detectChanges();

    expect(element.querySelector('header p')).toBeNull();
    expect(element.querySelector('header h2')).not.toBeNull();
  });
});
