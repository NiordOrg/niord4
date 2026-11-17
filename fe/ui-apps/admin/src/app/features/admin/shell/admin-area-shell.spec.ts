import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { afterEach, describe, expect, it } from 'vitest';

import { AppConfig } from '../../../core/config/app-config.interface';
import { APP_CONFIG } from '../../../core/config/app-config.token';
import { provideTestTransloco } from '../../../shared/testing/transloco-testing';
import { buildAdminAreaRoutes } from '../area-routes';
import { ADMIN_AREA, SYSADMIN_AREA } from '../section-descriptors';

const TEST_CONFIG = { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } as unknown as AppConfig;

/** The anonymous map every `settingGate` the sysadmin area declares is read from. */
const WEB_SETTINGS_URL = '/rest/settings/web';

/** The single-setting reads the gates made before they read the web settings. */
const SINGLE_SETTING_GATE_URLS = [
  '/rest/settings/setting/adminIntegrationPageEnabled',
  '/rest/settings/setting/secomEnabled',
];

/** Rail links rendered for the sysadmin area: overview + every UNGATED section. */
const UNGATED_SYSADMIN_LINKS =
  1 + SYSADMIN_AREA.sections.filter((section) => !section.settingGate).length;

interface SetupOptions {
  /** Flushed as the web settings' `adminIntegrationPageEnabled` (true → section visible). */
  readonly integrationEnabled?: boolean;
  /** Flushed as the web settings' computed `secomEnabled` (true → section visible). */
  readonly secomEnabled?: boolean;
}

async function setup(areaPath: 'admin' | 'sysadmin', url: string, options: SetupOptions = {}) {
  const area = areaPath === 'admin' ? ADMIN_AREA : SYSADMIN_AREA;
  TestBed.configureTestingModule({
    providers: [
      { provide: APP_CONFIG, useValue: TEST_CONFIG },
      provideHttpClient(),
      provideHttpClientTesting(),
      provideTestTransloco(),
      provideRouter([{ path: areaPath, children: buildAdminAreaRoutes(area) }]),
    ],
  });
  const harness = await RouterTestingHarness.create(url);
  const httpTesting = TestBed.inject(HttpTestingController);
  // The sysadmin shell starts the batch-status poll on mount; the admin shell must not.
  httpTesting.match('/rest/batch/status').forEach((req) => req.flush({ runningExecutions: 2 }));
  // The sysadmin shell reads the web settings once on mount for its gated sections;
  // resolve them so the gated rail links / overview cards settle to a deterministic state.
  httpTesting.match(WEB_SETTINGS_URL).forEach((req) =>
    req.flush({
      adminIntegrationPageEnabled: options.integrationEnabled ?? false,
      secomEnabled: options.secomEnabled ?? false,
      s124Enabled: true,
    }),
  );
  harness.detectChanges();
  return { doc: harness.routeNativeElement!.ownerDocument, httpTesting };
}

describe('AdminAreaShell', () => {
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('bare /admin redirects to the overview and renders one card per section (none gated)', async () => {
    const { doc } = await setup('admin', '/admin');
    const cards = doc.querySelectorAll('app-admin-overview-page a');
    expect(cards.length).toBe(ADMIN_AREA.sections.length);
  });

  it('the sysadmin rail withholds the gated sections until their setting resolves true', async () => {
    // Both settings resolve FALSE → the gated rail links stay off the rail.
    const { doc } = await setup('sysadmin', '/sysadmin/overview', {
      integrationEnabled: false,
      secomEnabled: false,
    });
    const railLinks = doc.querySelectorAll('app-admin-area-shell nav a');
    // overview + every section EXCEPT the still-hidden gated ones.
    expect(railLinks.length).toBe(UNGATED_SYSADMIN_LINKS);
    expect(doc.querySelector('a[href="/sysadmin/niord-integration"]')).toBeNull();
    expect(doc.querySelector('a[href="/sysadmin/secom"]')).toBeNull();
    expect(doc.querySelector('a[href="/sysadmin/integration"]')).toBeNull();
  });

  it('the sysadmin rail shows a gated section once its setting resolves true', async () => {
    const { doc } = await setup('sysadmin', '/sysadmin/overview', {
      integrationEnabled: true,
      secomEnabled: true,
    });
    const railLinks = doc.querySelectorAll('app-admin-area-shell nav a');
    expect(railLinks.length).toBe(1 + SYSADMIN_AREA.sections.length);
    expect(doc.querySelector('a[href="/sysadmin/niord-integration"]')).not.toBeNull();
    expect(doc.querySelector('a[href="/sysadmin/secom"]')).not.toBeNull();
  });

  it('the gates are independent — one section can be on while the other is off', async () => {
    const { doc } = await setup('sysadmin', '/sysadmin/overview', {
      integrationEnabled: false,
      secomEnabled: true,
    });
    expect(doc.querySelector('a[href="/sysadmin/niord-integration"]')).toBeNull();
    expect(doc.querySelector('a[href="/sysadmin/secom"]')).not.toBeNull();
  });

  it('the gated sections are hidden before the setting fetch resolves (default hidden)', async () => {
    const area = SYSADMIN_AREA;
    TestBed.configureTestingModule({
      providers: [
        { provide: APP_CONFIG, useValue: TEST_CONFIG },
        provideHttpClient(),
        provideHttpClientTesting(),
        provideTestTransloco(),
        provideRouter([{ path: 'sysadmin', children: buildAdminAreaRoutes(area) }]),
      ],
    });
    const harness = await RouterTestingHarness.create('/sysadmin/overview');
    const httpTesting = TestBed.inject(HttpTestingController);
    // Resolve the batch poll but DELIBERATELY leave the gate fetches pending.
    httpTesting.match('/rest/batch/status').forEach((req) => req.flush({ runningExecutions: 0 }));
    harness.detectChanges();
    const doc = harness.routeNativeElement!.ownerDocument;
    // Gates unresolved → sections hidden.
    expect(doc.querySelector('a[href="/sysadmin/niord-integration"]')).toBeNull();
    expect(doc.querySelector('a[href="/sysadmin/secom"]')).toBeNull();
    // Now satisfy the one outstanding gate request so verify() is clean.
    httpTesting
      .expectOne(WEB_SETTINGS_URL)
      .flush({ adminIntegrationPageEnabled: false, secomEnabled: false });
  });

  it('the sysadmin gates read the web settings once and never ask for a single setting', async () => {
    const { httpTesting } = await setup('sysadmin', '/sysadmin/overview', { secomEnabled: true });
    // setup() flushed the one web-settings read; nothing else was asked for.
    for (const url of SINGLE_SETTING_GATE_URLS) {
      httpTesting.expectNone(url);
    }
    httpTesting.expectNone(WEB_SETTINGS_URL);
  });

  it('an unbuilt section URL renders the coming-soon stub with the section label', async () => {
    const { doc } = await setup('sysadmin', '/sysadmin/settings');
    expect(doc.querySelector('app-coming-soon')).not.toBeNull();
    expect(doc.body.textContent).toContain('admin.sections.settings.label');
  });

  it('overview cards link to their sibling section routes', async () => {
    const { doc } = await setup('admin', '/admin/overview');
    const firstCard = doc.querySelector('app-admin-overview-page a');
    expect(firstCard?.getAttribute('href')).toBe('/admin/charts');
  });

  it('the sysadmin Batch Jobs entry shows the running-jobs badge (visible even at 0)', async () => {
    const { doc } = await setup('sysadmin', '/sysadmin/overview');
    const batchLink = doc.querySelector('a[href="/sysadmin/batch"]');
    expect(batchLink?.textContent).toContain('2');
    expect(batchLink?.querySelector('[aria-label]')).not.toBeNull();
  });

  it('the admin area never starts the batch-status poll and never fetches a gate', async () => {
    const { httpTesting } = await setup('admin', '/admin');
    httpTesting.expectNone('/rest/batch/status');
    // The admin area has no gated sections, so no gate is read.
    httpTesting.expectNone(WEB_SETTINGS_URL);
    for (const url of SINGLE_SETTING_GATE_URLS) {
      httpTesting.expectNone(url);
    }
  });
});
