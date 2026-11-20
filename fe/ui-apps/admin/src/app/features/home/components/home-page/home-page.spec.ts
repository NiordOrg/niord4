import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component, signal, type EnvironmentProviders } from '@angular/core';
import { DeferBlockBehavior, DeferBlockState, TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { AuthFacade } from '../../../../core/auth/facades/auth.facade';
import { APP_CONFIG } from '../../../../core/config/app-config.token';
import type { AppConfig } from '../../../../core/config/app-config.interface';
import { DomainFacade } from '../../../../core/domain/facades/domain.facade';
import { LanguageService } from '../../../../core/main-shell/services/language.service';
import { provideSlot } from '../../../../shared/slots/component-slots';
import { provideTestTransloco } from '../../../../shared/testing/transloco-testing';
import { HomePage } from './home-page';

@Component({ selector: 'app-test-welcome-note', template: 'country note' })
class WelcomeNote {}

const ResizeObserverStub = class {
  observe = (): void => undefined;
  unobserve = (): void => undefined;
  disconnect = (): void => undefined;
} as unknown as typeof ResizeObserver;
globalThis.ResizeObserver ??= ResizeObserverStub;

function setup(opts: {
  readonly isAuthenticated?: boolean;
  readonly domainCenter?: readonly [number, number];
  readonly domainZoom?: number;
  readonly configCenter?: readonly [number, number];
  readonly configZoom?: number;
  readonly language?: string;
  readonly slots?: readonly EnvironmentProviders[];
}) {
  const isAuthenticated = signal(opts.isAuthenticated ?? false);
  const login = vi.fn();
  const auth = { isAuthenticated, login } as unknown as AuthFacade;
  const lang = signal(opts.language ?? 'en');

  const domain = {
    mapDefaults: () => ({
      center: opts.domainCenter,
      zoom: opts.domainZoom,
    }),
  } as unknown as DomainFacade;

  const config: AppConfig = {
    apiBaseUrl: '/api',
    appName: 'Niord',
    helpUrls: {},
    keycloak: { url: '', realm: '', clientId: '' },
    map: {
      mapDefaultLongitude: opts.configCenter?.[0],
      mapDefaultLatitude: opts.configCenter?.[1],
      mapDefaultZoomLevel: opts.configZoom,
    },
  };

  TestBed.configureTestingModule({
    // The map sits behind @defer. Manual makes the block's state explicit rather than depending
    // on whether jsdom ever fires an idle callback, and keeps OpenLayers out of these tests.
    deferBlockBehavior: DeferBlockBehavior.Manual,
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideTestTransloco(),
      { provide: AuthFacade, useValue: auth },
      { provide: DomainFacade, useValue: domain },
      { provide: APP_CONFIG, useValue: config },
      // HomePage keys its landing search on selectedLanguage() so a switch re-localizes
      // the map labels + hover tooltip. Expose the writable signal to tests.
      {
        provide: LanguageService,
        useValue: { selectedLanguage: lang, activeLocale: signal('en') },
      },
      ...(opts.slots ?? []),
    ],
  });

  const fixture = TestBed.createComponent(HomePage);
  return { fixture, auth, login, lang, httpMock: TestBed.inject(HttpTestingController) };
}

describe('HomePage', () => {
  let fixture: ReturnType<typeof setup>['fixture'] | undefined;

  beforeEach(() => {
    fixture = undefined;
  });

  afterEach(() => {
    fixture?.destroy();
  });

  it('shows the welcome panel with title + NIORD acronym + logo', () => {
    const ctx = setup({});
    fixture = ctx.fixture;
    fixture.detectChanges();
    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('front_page.welcome');
    expect(text).toContain('Nautical');
    expect(text).toContain('Directory');
    const logo = fixture.nativeElement.querySelector('img[ngsrc*="niord.png"]');
    expect(logo).toBeTruthy();
  });

  /**
   * The placeholder is deliberately the map's own water tone, so a permanently-failed chunk
   * would otherwise render as a full-viewport flat sea — indistinguishable from a map that
   * loaded fine with no land in view. Angular never retries a failed defer block, so the only
   * way out is the reload this branch offers.
   */
  it('explains the failure and offers a reload when the map chunk cannot load', async () => {
    const ctx = setup({});
    fixture = ctx.fixture;
    fixture.detectChanges();

    const [mapBlock] = await fixture.getDeferBlocks();
    await mapBlock.render(DeferBlockState.Error);
    fixture.detectChanges();

    // Matched by content, not position, so another role="alert" on the page can never stand in
    // for this one.
    const alerts = [...fixture.nativeElement.querySelectorAll('[role="alert"]')] as HTMLElement[];
    const mapAlert = alerts.find((el) => el.textContent?.includes('common.mapLoadError'));
    expect(mapAlert).toBeTruthy();
    expect(mapAlert?.querySelector('button')?.textContent).toContain('common.retry');
  });

  it('renders the login button when the user is not authenticated', () => {
    const ctx = setup({ isAuthenticated: false });
    fixture = ctx.fixture;
    fixture.detectChanges();
    const buttons = fixture.nativeElement.querySelectorAll('button');
    expect(buttons.length).toBeGreaterThan(0);
    const loginButton = Array.from(buttons as NodeListOf<HTMLButtonElement>).find((b) =>
      b.textContent?.includes('menu.login'),
    );
    expect(loginButton).toBeDefined();
  });

  it('hides the login button when the user is authenticated', () => {
    const ctx = setup({ isAuthenticated: true });
    fixture = ctx.fixture;
    fixture.detectChanges();
    const buttons = fixture.nativeElement.querySelectorAll(
      'button',
    ) as NodeListOf<HTMLButtonElement>;
    const loginButton = Array.from(buttons).find((b) => b.textContent?.includes('menu.login'));
    expect(loginButton).toBeUndefined();
  });

  it('clicking the login button calls AuthFacade.login()', () => {
    const ctx = setup({ isAuthenticated: false });
    fixture = ctx.fixture;
    fixture.detectChanges();
    const buttons = fixture.nativeElement.querySelectorAll(
      'button',
    ) as NodeListOf<HTMLButtonElement>;
    const loginButton = Array.from(buttons).find((b) => b.textContent?.includes('menu.login'));
    loginButton?.click();
    expect(ctx.login).toHaveBeenCalledTimes(1);
  });

  it('fetches all published messages on mount via a GET to /messages/search', () => {
    const ctx = setup({});
    fixture = ctx.fixture;
    fixture.detectChanges();
    const req = ctx.httpMock.expectOne(
      (r) => r.method === 'GET' && r.url === '/api/messages/search',
    );
    expect(req.request.params.get('lang')).toBe('en');
    expect(req.request.params.get('page')).toBe('0');
    expect(req.request.params.get('maxSize')).toBe('1000');
    req.flush({ data: [], total: 0 });
  });

  it('says so in the welcome panel when the messages could not be read, and Retry reads them again', async () => {
    const ctx = setup({});
    fixture = ctx.fixture;
    fixture.detectChanges();
    ctx.httpMock
      .expectOne((r) => r.url === '/api/messages/search')
      .flush('down', { status: 503, statusText: 'Service Unavailable' });
    await Promise.resolve();
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelector('[role="alert"]')?.textContent).toContain('common.fetchError');

    host.querySelector<HTMLButtonElement>('[role="alert"] button')?.click();
    fixture.detectChanges();
    ctx.httpMock.expectOne((r) => r.url === '/api/messages/search').flush({ data: [], total: 0 });
    await Promise.resolve();
    fixture.detectChanges();
    expect(host.querySelector('[role="alert"]')).toBeNull();
  });

  it('re-fetches /messages/search in the new language when the language changes', () => {
    const ctx = setup({ language: 'en' });
    fixture = ctx.fixture;
    fixture.detectChanges();
    ctx.httpMock
      .expectOne((r) => r.url === '/api/messages/search' && r.params.get('lang') === 'en')
      .flush({ data: [], total: 0 });

    ctx.lang.set('da');
    fixture.detectChanges();

    const req = ctx.httpMock.expectOne((r) => r.url === '/api/messages/search');
    expect(req.request.params.get('lang')).toBe('da');
    req.flush({ data: [], total: 0 });
  });

  it('falls back to deployment-level defaults when the session domain has no map center/zoom', () => {
    const ctx = setup({ configCenter: [11, 56], configZoom: 6 });
    fixture = ctx.fixture;
    const initialView = (
      ctx.fixture.componentInstance as unknown as {
        initialView: () => { center?: readonly [number, number]; zoom?: number } | undefined;
      }
    ).initialView();
    expect(initialView?.center).toEqual([11, 56]);
    expect(initialView?.zoom).toBe(6);
  });

  it('per-axis cascade: domain center wins over config center, config zoom fills missing domain zoom', () => {
    const ctx = setup({
      domainCenter: [10, 55],
      configCenter: [11, 56],
      configZoom: 6,
      // domainZoom intentionally omitted — should inherit from config
    });
    fixture = ctx.fixture;
    const initialView = (
      ctx.fixture.componentInstance as unknown as {
        initialView: () => { center?: readonly [number, number]; zoom?: number } | undefined;
      }
    ).initialView();
    expect(initialView?.center).toEqual([10, 55]);
    expect(initialView?.zoom).toBe(6);
  });

  it('returns undefined initialView when neither domain nor config supplies center+zoom', () => {
    const ctx = setup({});
    fixture = ctx.fixture;
    const initialView = (
      ctx.fixture.componentInstance as unknown as {
        initialView: () => unknown;
      }
    ).initialView();
    expect(initialView).toBeUndefined();
  });

  it('ends the welcome panel with the home.welcome slot, empty in the base product', () => {
    const ctx = setup({});
    fixture = ctx.fixture;
    fixture.detectChanges();
    const slot = fixture.nativeElement.querySelector('.pointer-events-auto > app-slot');
    expect(slot).toBeTruthy();
    expect(slot.children.length).toBe(0);
  });

  it("renders a country's home.welcome component inside the welcome panel", async () => {
    const ctx = setup({ slots: [provideSlot('home.welcome', () => Promise.resolve(WelcomeNote))] });
    fixture = ctx.fixture;
    fixture.detectChanges();
    // The landing search is answered first: a pending request keeps the fixture from going stable.
    ctx.httpMock.expectOne((r) => r.url === '/api/messages/search').flush({ data: [], total: 0 });
    await fixture.whenStable();
    const note = fixture.nativeElement.querySelector('.pointer-events-auto app-test-welcome-note');
    expect(note?.textContent).toBe('country note');
  });
});
