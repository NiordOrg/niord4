import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NO_ERRORS_SCHEMA } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ActivatedRoute } from '@angular/router';
import { provideRouter } from '@angular/router';
import { By } from '@angular/platform-browser';
import Attribution from 'ol/control/Attribution';
import TileLayer from 'ol/layer/Tile';
import { toLonLat } from 'ol/proj';
import { OlTileLayer } from '@niord/map/layers/ol-tile-layer';
import { MapShellService } from '@niord/map/map-shell.service';
import type { NiordInfoConfig } from '../../niord-info.config';
import { MessageFeaturesLayer } from '../../shared/components/message-features-layer/message-features-layer';
import { TeaserComponent } from './teaser.component';
import { getTranslocoTestingModule, provideTestNiordConfig } from '../../testing/test-config';
import { settle } from '../../testing/settle';

const ROOT_XA = { areaId: 'urn:mrn:iho:country:xa', latitude: 56, longitude: 11, zoomLevel: 7 };
const ROOT_XB = { areaId: 'urn:mrn:iho:country:xb', latitude: 72, longitude: -40, zoomLevel: 4 };

describe('TeaserComponent', () => {
  let fixture: ComponentFixture<TeaserComponent>;
  let component: TeaserComponent;
  let httpTesting: HttpTestingController;

  function setup(
    queryParams: Record<string, string> = {},
    config: Partial<NiordInfoConfig> = { rootAreas: [ROOT_XA, ROOT_XB] },
  ) {
    TestBed.configureTestingModule({
      imports: [TeaserComponent, getTranslocoTestingModule()],
      providers: [
        provideTestNiordConfig(config),
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: ActivatedRoute, useValue: { snapshot: { queryParams } } },
      ],
      schemas: [NO_ERRORS_SCHEMA],
    });

    fixture = TestBed.createComponent(TeaserComponent);
    component = fixture.componentInstance;
    httpTesting = TestBed.inject(HttpTestingController);
  }

  async function answerList(): Promise<void> {
    await settle();
    httpTesting.expectOne((r) => r.url.includes('/rest/public/v1/messages')).flush([]);
    await settle();
  }

  afterEach(() => {
    httpTesting.verify();
  });

  it('should read both main types on creation', async () => {
    setup();
    await settle();
    const req = httpTesting.expectOne((r) => r.url.includes('/rest/public/v1/messages'));
    expect(req.request.urlWithParams).toContain('mainType=NW');
    expect(req.request.urlWithParams).toContain('mainType=NM');
    expect(req.request.urlWithParams).toContain('externalize=false');
    req.flush([{ id: 'msg-1', mainType: 'NW' }]);
    await settle();
    expect(component.messages()).toHaveLength(1);
  });

  it('should use lang from query params', async () => {
    setup({ lang: 'zz' });
    await settle();
    const req = httpTesting.expectOne((r) => r.url.includes('/rest/public/v1/messages'));
    expect(req.request.params.get('lang')).toBe('zz');
    expect(req.request.params.has('language')).toBe(false);
    req.flush([]);
    await settle();
  });

  // The embedding site chose that language, not the reader, whose next visit to the site itself
  // must not open in it.
  it('does not remember the language the embedding page asks for', async () => {
    localStorage.removeItem('niord-info.language');
    setup({ lang: 'zz' });
    await answerList();
    expect(localStorage.getItem('niord-info.language')).toBeNull();
  });

  /** The notice a failed read leaves on the chart, if there is one. */
  const notice = () =>
    (fixture.nativeElement as HTMLElement).querySelector('[role="status"]')!.textContent!.trim();

  // Embedded on another site, an empty chart reads as "no warnings in this area" — the opposite
  // of what a read that did not arrive means.
  it('says so over the chart when the read fails', async () => {
    setup();
    fixture.detectChanges();
    await settle();
    httpTesting
      .expectOne((r) => r.url.includes('/rest/public/v1/messages'))
      .flush('down', { status: 503, statusText: 'Service Unavailable' });
    await settle();
    fixture.detectChanges();

    expect(component.messages()).toEqual([]);
    expect(notice()).toBe('Failed to load messages');
    // Outside the link, whose name the banner alone gives.
    expect(fixture.nativeElement.querySelector('a [role="status"]')).toBeNull();
  });

  it('says nothing over a chart that simply has no messages on it', async () => {
    setup();
    fixture.detectChanges();
    await answerList();
    fixture.detectChanges();
    expect(notice()).toBe('');
  });

  it('reads the area the embedding page names', async () => {
    setup({ area: ROOT_XB.areaId });
    await settle();
    const req = httpTesting.expectOne((r) => r.url.includes('/rest/public/v1/messages'));
    expect(req.request.params.getAll('areaId')).toEqual([ROOT_XB.areaId]);
    req.flush([]);
    await settle();
  });

  it("reads the country's first root area when the embedding page names none", async () => {
    setup();
    await settle();
    const req = httpTesting.expectOne((r) => r.url.includes('/rest/public/v1/messages'));
    expect(req.request.params.getAll('areaId')).toEqual([ROOT_XA.areaId]);
    req.flush([]);
    await settle();
  });

  it('reads every message in a deployment with no root area', async () => {
    setup({}, { rootAreas: [] });
    await settle();
    const req = httpTesting.expectOne((r) => r.url.includes('/rest/public/v1/messages'));
    expect(req.request.params.has('areaId')).toBe(false);
    req.flush([]);
    await settle();
  });

  it('starts on the root area it shows, and stays there when there is nothing to frame', async () => {
    setup({ area: ROOT_XB.areaId });
    fixture.detectChanges();
    await answerList();
    const view = fixture.debugElement.injector.get(MapShellService).map()!.getView();
    const [lon, lat] = toLonLat(view.getCenter()!);

    expect(view.getZoom()).toBe(ROOT_XB.zoomLevel);
    expect(lon).toBeCloseTo(ROOT_XB.longitude, 6);
    expect(lat).toBeCloseTo(ROOT_XB.latitude, 6);
    const layer = fixture.debugElement.query(By.directive(MessageFeaturesLayer)).componentInstance;
    expect(layer.fallbackView()).toEqual({
      center: [ROOT_XB.longitude, ROOT_XB.latitude],
      zoom: 4,
    });
  });

  it('does not pan: the whole picture is one link', async () => {
    setup();
    fixture.detectChanges();
    const map = fixture.debugElement.injector.get(MapShellService).map();
    expect(map?.getInteractions().getLength()).toBe(0);
    const layer = fixture.debugElement.query(By.directive(MessageFeaturesLayer)).componentInstance;
    expect(layer.interactive()).toBe(false);
    await answerList();
  });

  // The link fills the whole frame, so the browser's own focus outline falls outside the screen, and
  // an outline on the link itself is painted under the map, which is positioned above it. The ring
  // is drawn by a layer over the map, inside the frame, in the site's focus colour; it takes no
  // clicks, so the credit beside the link still does (WCAG 2.4.7).
  it('shows a focus ring inside the frame, over the map, when the link has keyboard focus', async () => {
    setup();
    fixture.detectChanges();
    const link = (fixture.nativeElement as HTMLElement).querySelector<HTMLAnchorElement>(
      'a[aria-labelledby="teaser-banner"]',
    )!;
    const classes = link.className.split(/\s+/);

    // The layer sits on the link's box and above the map and the notes beside the link.
    expect(classes).toEqual(
      expect.arrayContaining([
        'relative',
        'after:absolute',
        'after:inset-0',
        'after:z-50',
        'after:pointer-events-none',
      ]),
    );
    // Its ring shows only on keyboard focus, drawn inward so the frame's edge does not cut it off.
    expect(classes).toEqual(
      expect.arrayContaining([
        'focus-visible:after:outline-2',
        'focus-visible:after:-outline-offset-2',
        'focus-visible:after:outline-primary-500',
      ]),
    );
    await answerList();
  });

  // The teaser draws OpenStreetMap, so it owes OpenStreetMap's credit and its link to the
  // licence. The whole picture is already a link, and a link cannot hold another, so the credit
  // sits beside it and the map's own credit line, which is drawn inside it, stays empty.
  it('draws OpenStreetMap and credits it with a link beside the teaser link, not in it', async () => {
    setup();
    fixture.detectChanges();
    const element = fixture.nativeElement as HTMLElement;
    const tiles = fixture.debugElement.query(By.directive(OlTileLayer)).componentInstance;

    expect(tiles.source()).toBe('OSM');
    expect(tiles.url()).toBeUndefined();
    expect(tiles.attributions()).toEqual([]);
    // What OpenLayers is left to draw, not only what the layer is given: were the empty list taken
    // for no list, the source would keep OpenStreetMap's own credit, whose link would then sit
    // inside the teaser's.
    const shell = fixture.debugElement.injector.get(MapShellService);
    const tileLayer = shell.layers().find((layer) => layer instanceof TileLayer) as TileLayer;
    const credits = tileLayer.getSource()?.getAttributions();
    expect(credits ? credits({} as Parameters<typeof credits>[0]) : []).toEqual([]);

    const licence = element.querySelector<HTMLAnchorElement>(
      'a[href="https://www.openstreetmap.org/copyright"]',
    );
    expect(licence).not.toBeNull();
    expect(licence!.parentElement!.closest('a')).toBeNull();
    expect(licence!.closest('.ol-attribution')!.textContent!.replace(/\s+/g, ' ').trim()).toBe(
      '© OpenStreetMap contributors.',
    );
    // Embedded in a frame on another site, the licence opens in a tab of its own, as the teaser does.
    expect(licence!.target).toBe('_blank');
    expect(licence!.rel).toBe('noopener');
    expect(element.querySelector('a a')).toBeNull();
    // The map keeps its credit control, as every map does; with nothing to show, it hides.
    const map = shell.map();
    expect(
      map
        ?.getControls()
        .getArray()
        .some((c) => c instanceof Attribution),
    ).toBe(true);
    await answerList();
  });
});
