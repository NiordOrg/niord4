import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it } from 'vitest';

import { APP_CONFIG } from '../../../../core/config/app-config.token';
import { LanguageService } from '../../../../core/main-shell/services/language.service';
import type {
  AdminPromulgationType,
  NavtexTransmitter,
  SafetyNetArea,
  TwitterSettings,
} from '../../sections/sysadmin/promulgation/promulgation-admin.model';
import { PromulgationAdminApiService } from './promulgation-admin-api.service';

function setup() {
  TestBed.configureTestingModule({
    providers: [
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
      provideHttpClient(),
      provideHttpClientTesting(),
      { provide: LanguageService, useValue: { selectedLanguage: () => 'en' } },
    ],
  });
  return {
    service: TestBed.inject(PromulgationAdminApiService),
    httpTesting: TestBed.inject(HttpTestingController),
  };
}

const type: AdminPromulgationType = {
  typeId: 'navtex-dk',
  serviceId: 'navtex',
  name: 'NAVTEX',
  priority: 10,
  active: true,
  requirement: 'OPTIONAL',
  domains: [],
  messageTypes: ['COASTAL_WARNING'],
  scriptResourcePaths: ['templates/navtex.ftl'],
};

const transmitter: NavtexTransmitter = {
  promulgationType: { typeId: 'navtex-dk' },
  name: 'Baltico',
  active: true,
  areas: [{ id: 42 }],
};

const area: SafetyNetArea = {
  type: 'CIRCULAR',
  promulgationType: { typeId: 'safetynet-dk' },
  name: 'Kattegat',
  active: true,
  sortOrder: 100,
  center: '56 11',
  radius: '30',
};

const settings: TwitterSettings = {
  promulgationType: { typeId: 'twitter-dk' },
  includeThumbnail: true,
  apiKey: 'key',
  apiSecret: 'secret',
  accessToken: 'token',
  accessTokenSecret: 'token-secret',
};

describe('PromulgationAdminApiService', () => {
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('GETs the services registry', () => {
    const { service, httpTesting } = setup();
    service.getServices().subscribe();
    const req = httpTesting.expectOne('/rest/promulgations/promulgation-services/all');
    expect(req.request.method).toBe('GET');
    req.flush([{ serviceId: 'navtex', serviceName: 'NAVTEX mailing list' }]);
  });

  it('GETs the full type catalog', () => {
    const { service, httpTesting } = setup();
    service.getTypes().subscribe();
    const req = httpTesting.expectOne('/rest/promulgations/promulgation-types/all');
    expect(req.request.method).toBe('GET');
    req.flush([]);
  });

  it('POSTs the new type to the trailing-slash collection path', () => {
    const { service, httpTesting } = setup();
    service.createType(type).subscribe();
    const req = httpTesting.expectOne('/rest/promulgations/promulgation-type/');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(type);
    req.flush(type);
  });

  it('PUTs the type on its own id — path id equals body id by construction', () => {
    const { service, httpTesting } = setup();
    service.updateType(type).subscribe();
    const req = httpTesting.expectOne('/rest/promulgations/promulgation-type/navtex-dk');
    expect(req.request.method).toBe('PUT');
    expect((req.request.body as AdminPromulgationType).typeId).toBe('navtex-dk');
    req.flush(type);
  });

  it('DELETEs on the typeId path', () => {
    const { service, httpTesting } = setup();
    service.deleteType('navtex-dk').subscribe();
    const req = httpTesting.expectOne('/rest/promulgations/promulgation-type/navtex-dk');
    expect(req.request.method).toBe('DELETE');
    req.flush(null);
  });

  it('URL-encodes a free-form typeId in every path (ids may carry reserved chars)', () => {
    const { service, httpTesting } = setup();
    const special = { ...type, typeId: 'dk/navtex #1' };
    service.updateType(special).subscribe();
    expect(
      httpTesting.expectOne('/rest/promulgations/promulgation-type/dk%2Fnavtex%20%231').request
        .method,
    ).toBe('PUT');
    service.deleteType('dk/navtex #1').subscribe();
    expect(
      httpTesting.expectOne('/rest/promulgations/promulgation-type/dk%2Fnavtex%20%231').request
        .method,
    ).toBe('DELETE');
    service.getTransmitters('dk/navtex #1').subscribe();
    httpTesting
      .expectOne((r) => r.url === '/rest/promulgation/navtex/transmitters/dk%2Fnavtex%20%231/all')
      .flush([]);
    service.getSafetyNetAreas('dk/navtex #1').subscribe();
    httpTesting
      .expectOne((r) => r.url === '/rest/promulgation/safetynet/areas/dk%2Fnavtex%20%231/all')
      .flush([]);
    service.getTwitterSettings('dk/navtex #1').subscribe();
    httpTesting.expectOne('/rest/promulgation/twitter-settings/dk%2Fnavtex%20%231').flush(null);
  });

  it('exposes the two list loads as request descriptors on the same endpoints', () => {
    const { service } = setup();
    expect(service.typesRequest()).toEqual({ url: '/rest/promulgations/promulgation-types/all' });
    expect(service.servicesRequest()).toEqual({
      url: '/rest/promulgations/promulgation-services/all',
    });
  });

  it('export URL targets the full type catalog', () => {
    const { service } = setup();
    expect(service.exportUrl()).toBe('/rest/promulgations/promulgation-types/all');
  });

  it('upload URL targets the promulgation-type import endpoint', () => {
    const { service } = setup();
    expect(service.uploadUrl()).toBe('/rest/promulgations/upload-promulgation-types');
  });

  it('GETs the transmitters with the current UI language by default', () => {
    const { service, httpTesting } = setup();
    service.getTransmitters('navtex-dk').subscribe();
    const req = httpTesting.expectOne(
      (r) => r.url === '/rest/promulgation/navtex/transmitters/navtex-dk/all',
    );
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('lang')).toBe('en');
    req.flush([]);
  });

  it('GETs the transmitters with an explicit language override', () => {
    const { service, httpTesting } = setup();
    service.getTransmitters('navtex-dk', 'da').subscribe();
    const req = httpTesting.expectOne(
      (r) => r.url === '/rest/promulgation/navtex/transmitters/navtex-dk/all',
    );
    expect(req.request.params.get('lang')).toBe('da');
    req.flush([]);
  });

  it('POSTs the new transmitter to the trailing-slash collection path with its parent ref', () => {
    const { service, httpTesting } = setup();
    service.createTransmitter('navtex-dk', transmitter).subscribe();
    const req = httpTesting.expectOne(
      '/rest/promulgation/navtex/transmitters/navtex-dk/transmitter/',
    );
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(transmitter);
    expect((req.request.body as NavtexTransmitter).promulgationType.typeId).toBe('navtex-dk');
    req.flush(transmitter);
  });

  it('PUTs the transmitter on its own name — path name equals body name by construction', () => {
    const { service, httpTesting } = setup();
    service.updateTransmitter('navtex-dk', transmitter).subscribe();
    const req = httpTesting.expectOne(
      '/rest/promulgation/navtex/transmitters/navtex-dk/transmitter/Baltico',
    );
    expect(req.request.method).toBe('PUT');
    const body = req.request.body as NavtexTransmitter;
    expect(body.name).toBe('Baltico');
    expect(body.promulgationType.typeId).toBe('navtex-dk');
    req.flush(transmitter);
  });

  it('URL-encodes a free-form transmitter name (names may carry reserved chars)', () => {
    const { service, httpTesting } = setup();
    const special = { ...transmitter, name: 'Rogaland/Bodø #1' };
    service.updateTransmitter('navtex-dk', special).subscribe();
    expect(
      httpTesting.expectOne(
        '/rest/promulgation/navtex/transmitters/navtex-dk/transmitter/Rogaland%2FBod%C3%B8%20%231',
      ).request.method,
    ).toBe('PUT');
    service.deleteTransmitter('navtex-dk', 'Rogaland/Bodø #1').subscribe();
    expect(
      httpTesting.expectOne(
        '/rest/promulgation/navtex/transmitters/navtex-dk/transmitter/Rogaland%2FBod%C3%B8%20%231',
      ).request.method,
    ).toBe('DELETE');
  });

  it('DELETEs the transmitter on its name path', () => {
    const { service, httpTesting } = setup();
    service.deleteTransmitter('navtex-dk', 'Baltico').subscribe();
    const req = httpTesting.expectOne(
      '/rest/promulgation/navtex/transmitters/navtex-dk/transmitter/Baltico',
    );
    expect(req.request.method).toBe('DELETE');
    req.flush(null);
  });

  it('GETs the SafetyNET areas with the current UI language by default', () => {
    const { service, httpTesting } = setup();
    service.getSafetyNetAreas('safetynet-dk').subscribe();
    const req = httpTesting.expectOne(
      (r) => r.url === '/rest/promulgation/safetynet/areas/safetynet-dk/all',
    );
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('lang')).toBe('en');
    req.flush([]);
  });

  it('GETs the SafetyNET areas with an explicit language override', () => {
    const { service, httpTesting } = setup();
    service.getSafetyNetAreas('safetynet-dk', 'da').subscribe();
    const req = httpTesting.expectOne(
      (r) => r.url === '/rest/promulgation/safetynet/areas/safetynet-dk/all',
    );
    expect(req.request.params.get('lang')).toBe('da');
    req.flush([]);
  });

  it('POSTs the new area to the trailing-slash collection path with its parent ref', () => {
    const { service, httpTesting } = setup();
    service.createSafetyNetArea('safetynet-dk', area).subscribe();
    const req = httpTesting.expectOne('/rest/promulgation/safetynet/areas/safetynet-dk/area/');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(area);
    expect((req.request.body as SafetyNetArea).promulgationType.typeId).toBe('safetynet-dk');
    req.flush(area);
  });

  it('PUTs the area on its own name — path name equals body name by construction', () => {
    const { service, httpTesting } = setup();
    service.updateSafetyNetArea('safetynet-dk', area).subscribe();
    const req = httpTesting.expectOne(
      '/rest/promulgation/safetynet/areas/safetynet-dk/area/Kattegat',
    );
    expect(req.request.method).toBe('PUT');
    const body = req.request.body as SafetyNetArea;
    expect(body.name).toBe('Kattegat');
    expect(body.promulgationType.typeId).toBe('safetynet-dk');
    req.flush(area);
  });

  it('URL-encodes a free-form area name (names may carry reserved chars)', () => {
    const { service, httpTesting } = setup();
    const special = { ...area, name: 'Gulf/Øst #2' };
    service.updateSafetyNetArea('safetynet-dk', special).subscribe();
    expect(
      httpTesting.expectOne(
        '/rest/promulgation/safetynet/areas/safetynet-dk/area/Gulf%2F%C3%98st%20%232',
      ).request.method,
    ).toBe('PUT');
    service.deleteSafetyNetArea('safetynet-dk', 'Gulf/Øst #2').subscribe();
    expect(
      httpTesting.expectOne(
        '/rest/promulgation/safetynet/areas/safetynet-dk/area/Gulf%2F%C3%98st%20%232',
      ).request.method,
    ).toBe('DELETE');
  });

  it('DELETEs the area on its name path', () => {
    const { service, httpTesting } = setup();
    service.deleteSafetyNetArea('safetynet-dk', 'Kattegat').subscribe();
    const req = httpTesting.expectOne(
      '/rest/promulgation/safetynet/areas/safetynet-dk/area/Kattegat',
    );
    expect(req.request.method).toBe('DELETE');
    req.flush(null);
  });

  it('GETs the Twitter settings — a null body is a valid "no settings yet" response', () => {
    const { service, httpTesting } = setup();
    let received: TwitterSettings | null | undefined;
    service.getTwitterSettings('twitter-dk').subscribe((value) => (received = value));
    const req = httpTesting.expectOne('/rest/promulgation/twitter-settings/twitter-dk');
    expect(req.request.method).toBe('GET');
    req.flush(null);
    expect(received).toBeNull();
  });

  it('GETs stored Twitter settings', () => {
    const { service, httpTesting } = setup();
    let received: TwitterSettings | null | undefined;
    service.getTwitterSettings('twitter-dk').subscribe((value) => (received = value));
    httpTesting.expectOne('/rest/promulgation/twitter-settings/twitter-dk').flush(settings);
    expect(received).toEqual(settings);
  });

  it('POSTs the new Twitter settings to the trailing-slash collection path with the parent ref', () => {
    const { service, httpTesting } = setup();
    service.createTwitterSettings(settings).subscribe();
    const req = httpTesting.expectOne('/rest/promulgation/twitter-settings/');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(settings);
    expect((req.request.body as TwitterSettings).promulgationType.typeId).toBe('twitter-dk');
    req.flush(settings);
  });

  it('PUTs the Twitter settings keyed on the parent typeId (encoded)', () => {
    const { service, httpTesting } = setup();
    const special = { ...settings, promulgationType: { typeId: 'dk/twitter #1' } };
    service.updateTwitterSettings(special).subscribe();
    const req = httpTesting.expectOne('/rest/promulgation/twitter-settings/dk%2Ftwitter%20%231');
    expect(req.request.method).toBe('PUT');
    expect((req.request.body as TwitterSettings).promulgationType.typeId).toBe('dk/twitter #1');
    req.flush(special);
  });
});
