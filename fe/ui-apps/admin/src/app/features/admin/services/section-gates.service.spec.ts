import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { APP_CONFIG } from '../../../core/config/app-config.token';
import { AdminSectionDescriptor } from '../section-descriptors';
import { SectionGatesService } from './section-gates.service';

const GATE_KEY = 'adminIntegrationPageEnabled';
const WEB_SETTINGS_URL = '/rest/settings/web';

const gated: AdminSectionDescriptor = {
  path: 'niord-integration',
  labelKey: 'admin.sections.niord-integration.label',
  descriptionKey: 'admin.sections.niord-integration.description',
  icon: 'lucideRefreshCw',
  settingGate: GATE_KEY,
};

const secom: AdminSectionDescriptor = {
  path: 'secom',
  labelKey: 'admin.sections.secom.label',
  descriptionKey: 'admin.sections.secom.description',
  icon: 'lucideShield',
  settingGate: 'secomEnabled',
};

const ungated: AdminSectionDescriptor = {
  path: 'settings',
  labelKey: 'admin.sections.settings.label',
  descriptionKey: 'admin.sections.settings.description',
  icon: 'lucideSettings',
};

describe('SectionGatesService', () => {
  let service: SectionGatesService;
  let httpTesting: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
        provideHttpClient(),
        provideHttpClientTesting(),
        SectionGatesService,
      ],
    });
    service = TestBed.inject(SectionGatesService);
    httpTesting = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpTesting.verify());

  it('an ungated section is always visible without any fetch', () => {
    service.start([ungated]);
    httpTesting.expectNone(WEB_SETTINGS_URL);
    expect(service.isVisible(ungated)).toBe(true);
  });

  it('a gated section is hidden until the web settings resolve', () => {
    service.start([gated]);
    // Before flushing the request, the gate is unresolved → hidden.
    expect(service.isVisible(gated)).toBe(false);
    httpTesting.expectOne(WEB_SETTINGS_URL).flush({ [GATE_KEY]: true });
    expect(service.isVisible(gated)).toBe(true);
  });

  it('shows the section when the value is the boolean true', () => {
    service.start([gated]);
    httpTesting.expectOne(WEB_SETTINGS_URL).flush({ [GATE_KEY]: true });
    expect(service.isVisible(gated)).toBe(true);
  });

  it("shows the section when the value is the string 'true'", () => {
    service.start([gated]);
    httpTesting.expectOne(WEB_SETTINGS_URL).flush({ [GATE_KEY]: 'true' });
    expect(service.isVisible(gated)).toBe(true);
  });

  it('keeps the section hidden when the value is false', () => {
    service.start([gated]);
    httpTesting.expectOne(WEB_SETTINGS_URL).flush({ [GATE_KEY]: false });
    expect(service.isVisible(gated)).toBe(false);
  });

  it("keeps the section hidden when the value is the string 'false'", () => {
    service.start([gated]);
    httpTesting.expectOne(WEB_SETTINGS_URL).flush({ [GATE_KEY]: 'false' });
    expect(service.isVisible(gated)).toBe(false);
  });

  it('keeps the section hidden when the map has no such key', () => {
    service.start([gated]);
    httpTesting.expectOne(WEB_SETTINGS_URL).flush({ mode: 'test' });
    expect(service.isVisible(gated)).toBe(false);
  });

  it('keeps the section hidden when the answer is empty', () => {
    service.start([gated]);
    httpTesting.expectOne(WEB_SETTINGS_URL).flush(null);
    expect(service.isVisible(gated)).toBe(false);
  });

  it('keeps the section hidden on a fetch error', () => {
    service.start([gated]);
    httpTesting
      .expectOne(WEB_SETTINGS_URL)
      .flush('boom', { status: 500, statusText: 'Server Error' });
    expect(service.isVisible(gated)).toBe(false);
  });

  it('reads every gate key from one web-settings request and never asks for a single setting', () => {
    const otherGated: AdminSectionDescriptor = { ...gated, path: 'duplicate' };
    service.start([gated, otherGated, secom, ungated]);
    httpTesting.expectNone((req) => req.url.startsWith('/rest/settings/setting/'));
    httpTesting
      .expectOne(WEB_SETTINGS_URL)
      .flush({ [GATE_KEY]: true, secomEnabled: false, s124Enabled: true });
    expect(service.isVisible(gated)).toBe(true);
    expect(service.isVisible(otherGated)).toBe(true);
    expect(service.isVisible(secom)).toBe(false);
  });

  it('resolves secomEnabled, the value the backend computes from niord.secom.enabled, from the same map', () => {
    service.start([gated, secom]);
    httpTesting.expectOne(WEB_SETTINGS_URL).flush({ [GATE_KEY]: false, secomEnabled: true });
    expect(service.isVisible(secom)).toBe(true);
    expect(service.isVisible(gated)).toBe(false);
  });

  it('an error hides every gated section', () => {
    service.start([gated, secom]);
    httpTesting
      .expectOne(WEB_SETTINGS_URL)
      .flush('boom', { status: 500, statusText: 'Server Error' });
    expect(service.isVisible(gated)).toBe(false);
    expect(service.isVisible(secom)).toBe(false);
  });

  it('is idempotent: a second start() does not refetch', () => {
    service.start([gated]);
    httpTesting.expectOne(WEB_SETTINGS_URL).flush({ [GATE_KEY]: true });
    service.start([gated]);
    httpTesting.expectNone(WEB_SETTINGS_URL);
    expect(service.isVisible(gated)).toBe(true);
  });
});
