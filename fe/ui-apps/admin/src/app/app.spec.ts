import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';

import { App } from './app';
import { appConfig } from './app.config';
import { DomainsApiService } from './core/domain/services/domains-api.service';

describe('App', () => {
  beforeEach(async () => {
    Object.defineProperty(window, 'matchMedia', {
      writable: true,
      value: (query: string) => ({
        matches: false,
        media: query,
        onchange: null,
        addListener: () => void 0,
        removeListener: () => void 0,
        addEventListener: () => void 0,
        removeEventListener: () => void 0,
        dispatchEvent: () => false,
      }),
    });

    await TestBed.configureTestingModule({
      imports: [App],
      providers: [
        ...(appConfig.providers ?? []),
        provideRouter([]),
        // Override DomainsApiService so the appInitializer's bootstrap call
        // is HTTP-free and resolves deterministically with an empty list.
        { provide: DomainsApiService, useValue: { lookup: () => of([]) } },
      ],
    }).compileComponents();
  });

  it('should create the app', () => {
    const fixture = TestBed.createComponent(App);
    const app = fixture.componentInstance;
    expect(app).toBeTruthy();
  });

  it('should render main layout sections', async () => {
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('header')).toBeTruthy();
    expect(compiled.querySelector('main')).toBeTruthy();
    // The base product's build ships no branding, so its footer renders no row.
    expect(compiled.querySelector('app-main-footer')).toBeTruthy();
    expect(compiled.querySelector('footer')).toBeNull();
  });
});
