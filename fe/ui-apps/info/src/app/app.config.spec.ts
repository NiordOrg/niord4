import { ApplicationInitStatus, ApplicationRef, Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Location, ViewportScroller } from '@angular/common';
import { provideLocationMocks } from '@angular/common/testing';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { Router, RouterOutlet } from '@angular/router';
import { MAP_TILE_SOURCES } from '@niord/map/tile-sources';
import { appConfig } from './app.config';
import { AppConfigService } from './core/services/app-config.service';
import { WebSettingsService } from './core/services/web-settings.service';
import { LayoutComponent } from './shared/components/layout/layout.component';

@Component({ template: '' })
class Page {}

@Component({ selector: 'app-scroll-root', imports: [RouterOutlet], template: '<router-outlet />' })
class Root {}

/** Lets the router hand its scroll position over, which it does a task after the navigation. */
const nextTask = () => new Promise((resolve) => setTimeout(resolve, 20));

describe('appConfig', () => {
  let host: HTMLElement;

  afterEach(() => host.remove());

  // The router's scroll handling starts with the application, so the application's own providers
  // are bootstrapped here, on two pages of the spec's own. Without it a page opens at the offset
  // the page before was scrolled to, and Back lands at the top of a long list.
  it('opens a new page at its top, and Back returns to where the reader was', async () => {
    let at: [number, number] = [0, 0];
    const scroller = {
      getScrollPosition: vi.fn(() => at),
      scrollToPosition: vi.fn((position: [number, number]) => (at = position)),
      scrollToAnchor: vi.fn(),
      setHistoryScrollRestoration: vi.fn(),
      setOffset: vi.fn(),
    };
    TestBed.configureTestingModule({
      providers: [
        ...appConfig.providers,
        provideHttpClientTesting(),
        provideLocationMocks(),
        { provide: WebSettingsService, useValue: { load: () => Promise.resolve() } },
        { provide: ViewportScroller, useValue: scroller },
      ],
    });
    TestBed.inject(Router).resetConfig([
      { path: '', component: Page },
      { path: 'downloads', component: Page },
    ]);
    host = document.body.appendChild(document.createElement('app-scroll-root'));
    await TestBed.inject(ApplicationInitStatus).donePromise;
    TestBed.inject(ApplicationRef).bootstrap(Root, host);
    await nextTask();
    scroller.scrollToPosition.mockClear();
    at = [0, 4000]; // The reader scrolls down the first page.

    await TestBed.inject(Router).navigateByUrl('/downloads');
    await nextTask();
    expect(scroller.scrollToPosition).toHaveBeenCalledExactlyOnceWith([0, 0]);

    TestBed.inject(Location).back();
    await nextTask();
    expect(scroller.scrollToPosition).toHaveBeenLastCalledWith([0, 4000], { behavior: 'instant' });
  });
});

describe("appConfig — the country's pages", () => {
  // A country build swaps the default file for its own; the base product's has no pages.
  it('ends the layout with the lazily loaded country routes, and the table with the wildcard', async () => {
    TestBed.configureTestingModule({
      providers: [
        ...appConfig.providers,
        provideHttpClientTesting(),
        provideLocationMocks(),
        { provide: WebSettingsService, useValue: { load: () => Promise.resolve() } },
      ],
    });
    const config = TestBed.inject(Router).config;
    expect(config.at(-1)).toEqual({ path: '**', redirectTo: 'messages/details' });

    const country = config.find((route) => route.component === LayoutComponent)!.children!.at(-1)!;
    expect(country.path).toBe('');
    expect(country.component).toBeUndefined();
    expect(await (country.loadChildren as () => Promise<unknown>)()).toEqual(
      expect.objectContaining({ default: [] }),
    );
  });
});

describe('appConfig — the base map', () => {
  it('hands the map kit the tile address the settings resolved, whenever they resolve it', () => {
    TestBed.configureTestingModule({
      providers: [
        ...appConfig.providers,
        provideHttpClientTesting(),
        provideLocationMocks(),
        { provide: WebSettingsService, useValue: { load: () => Promise.resolve() } },
      ],
    });
    const tileSources = TestBed.inject(MAP_TILE_SOURCES);
    expect(tileSources.osmUrl).toBeUndefined();

    TestBed.inject(AppConfigService).applyOsmUrl('https://tiles.example/{z}/{x}/{y}.png');
    expect(tileSources.osmUrl).toBe('https://tiles.example/{z}/{x}/{y}.png');
    // The site draws no OpenSeaMap.
    expect(tileSources.openSeaMapUrl).toBeUndefined();
  });
});
