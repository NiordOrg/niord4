import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';

import en from '../../../../../../../../../languages/en/i18n/admin/en.json';
import { provideCorePromulgationAdminPlugins } from './core-admin-plugins.providers';
import { NavtexTransmittersPlugin } from './navtex-transmitters-plugin';
import { PROMULGATION_ADMIN_PLUGINS } from './promulgation-admin-plugin.token';
import { SafetyNetAreasPlugin } from './safetynet-areas-plugin';
import { TwitterSettingsPlugin } from './twitter-settings-plugin';

@Component({ template: '' })
class CountryStubPlugin {}

describe('promulgation admin plugin registry', () => {
  it('injecting with no providers at all yields null — consumers default to []', () => {
    const plugins = TestBed.inject(PROMULGATION_ADMIN_PLUGINS, null) ?? [];
    expect(plugins).toEqual([]);
  });

  it('the core provider registers the NAVTEX, SafetyNET and Twitter plugins under their serviceIds', () => {
    TestBed.configureTestingModule({
      providers: [provideCorePromulgationAdminPlugins()],
    });
    const plugins = TestBed.inject(PROMULGATION_ADMIN_PLUGINS, null) ?? [];
    expect(plugins.map((plugin) => plugin.serviceId)).toEqual(['navtex', 'safetynet', 'twitter']);
    expect(plugins.find((plugin) => plugin.serviceId === 'navtex')?.component).toBe(
      NavtexTransmittersPlugin,
    );
    expect(plugins.find((plugin) => plugin.serviceId === 'safetynet')?.component).toBe(
      SafetyNetAreasPlugin,
    );
    expect(plugins.find((plugin) => plugin.serviceId === 'twitter')?.component).toBe(
      TwitterSettingsPlugin,
    );
  });

  it('entries registered at the SAME injector level all resolve — the country-extension seam', () => {
    TestBed.configureTestingModule({
      providers: [
        provideCorePromulgationAdminPlugins(),
        {
          provide: PROMULGATION_ADMIN_PLUGINS,
          useValue: { serviceId: 'mailinglist', component: CountryStubPlugin },
          multi: true,
        },
      ],
    });
    const plugins = TestBed.inject(PROMULGATION_ADMIN_PLUGINS);
    expect(plugins.map((plugin) => plugin.serviceId).sort()).toEqual([
      'mailinglist',
      'navtex',
      'safetynet',
      'twitter',
    ]);
    expect(plugins.find((plugin) => plugin.serviceId === 'navtex')?.component).toBe(
      NavtexTransmittersPlugin,
    );
    expect(plugins.find((plugin) => plugin.serviceId === 'mailinglist')?.component).toBe(
      CountryStubPlugin,
    );
  });

  it('the add-mode hint key resolves in the en bundle', () => {
    expect(en.admin.promulgation.plugin.saveTypeFirst).toMatch(/\S/);
  });
});
