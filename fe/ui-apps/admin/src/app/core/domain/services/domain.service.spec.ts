import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { of, throwError } from 'rxjs';

import { DomainService } from './domain.service';
import { DomainsApiService } from './domains-api.service';
import { TAG_SELECTION_STORE, TagSelectionStore } from './tag-selection-store.token';
import type { Domain } from '../models/domain.model';
import { LanguageService } from '../../main-shell/services/language.service';

const niordDk: Domain = { domainId: 'niord-dk', name: 'NW' };
const niordDe: Domain = { domainId: 'niord-de', name: 'NW (DE)' };

function setup(
  opts: {
    apiResult?: ReturnType<DomainsApiService['lookup']>;
    persisted?: string | null;
  } = {},
) {
  if (opts.persisted !== undefined) {
    if (opts.persisted === null) {
      localStorage.removeItem('niord.sessionDomainId');
    } else {
      localStorage.setItem('niord.sessionDomainId', opts.persisted);
    }
  }
  const tagStore: TagSelectionStore = {
    clearLastSelection: vi.fn(),
    saveLastSelection: vi.fn(),
    getLastSelection: () => signal(undefined),
  };
  const language = signal('en');
  TestBed.configureTestingModule({
    providers: [
      {
        provide: DomainsApiService,
        useValue: { lookup: () => opts.apiResult ?? of([niordDk, niordDe]) },
      },
      { provide: TAG_SELECTION_STORE, useValue: tagStore },
      { provide: LanguageService, useValue: { selectedLanguage: language } },
    ],
  });
  return {
    service: TestBed.inject(DomainService),
    tagStore,
    language,
  };
}

describe('DomainService', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  afterEach(() => {
    localStorage.clear();
  });

  it('bootstraps with available domains and selects first when nothing persisted', async () => {
    const { service } = setup({ persisted: null });
    await service.bootstrap();
    expect(service.availableDomains()).toEqual([niordDk, niordDe]);
    expect(service.sessionDomain()).toEqual(niordDk);
  });

  it('prefers persisted localStorage value over first available', async () => {
    const { service } = setup({ persisted: 'niord-de' });
    await service.bootstrap();
    expect(service.sessionDomain()?.domainId).toBe('niord-de');
  });

  it('prefers deepLink param over persisted value', async () => {
    const { service } = setup({ persisted: 'niord-dk' });
    await service.bootstrap('niord-de');
    expect(service.sessionDomain()?.domainId).toBe('niord-de');
  });

  it('falls back to empty list on lookup error', async () => {
    const { service } = setup({ apiResult: throwError(() => new Error('boom')) });
    await service.bootstrap();
    expect(service.availableDomains()).toEqual([]);
    expect(service.sessionDomain()).toBeUndefined();
  });

  it('persists selected session domain to localStorage', async () => {
    const { service } = setup();
    await service.bootstrap();
    service.setSessionDomain(niordDe);
    expect(localStorage.getItem('niord.sessionDomainId')).toBe('niord-de');
  });

  it('clears localStorage when session domain set to undefined', async () => {
    const { service } = setup();
    await service.bootstrap();
    service.setSessionDomain(undefined);
    expect(localStorage.getItem('niord.sessionDomainId')).toBeNull();
  });

  // Note: the clear-last-tag-on-domain-switch effect moved to LastTagSelectionService (the root
  // DomainService could only ever resolve the no-op TAG_SELECTION_STORE stub). Covered there.

  it('resolveById returns matching domain or undefined', async () => {
    const { service } = setup();
    await service.bootstrap();
    expect(service.resolveById('niord-de')).toEqual(niordDe);
    expect(service.resolveById('missing')).toBeUndefined();
    expect(service.resolveById(undefined)).toBeUndefined();
  });

  it('refresh() re-fetches at the active language and rebinds session/search domains', async () => {
    const staleDk: Domain = { domainId: 'niord-dk', name: 'NW' };
    const staleDe: Domain = { domainId: 'niord-de', name: 'NW (DE)' };
    const freshDk: Domain = { domainId: 'niord-dk', name: 'NW (renamed)' };
    const freshDe: Domain = { domainId: 'niord-de', name: 'NW (DE renamed)' };
    let calls = 0;
    const lookup = vi.fn(() => of(calls++ === 0 ? [staleDk, staleDe] : [freshDk, freshDe]));
    TestBed.configureTestingModule({
      providers: [
        { provide: DomainsApiService, useValue: { lookup } },
        { provide: LanguageService, useValue: { selectedLanguage: signal('en') } },
      ],
    });
    const service = TestBed.inject(DomainService);
    await service.bootstrap();
    service.setSearchDomain(staleDe);
    expect(service.sessionDomain()?.name).toBe('NW');

    service.refresh();
    expect(lookup).toHaveBeenLastCalledWith({ language: 'en' });
    expect(service.availableDomains()).toEqual([freshDk, freshDe]);
    // Same ids, fresh objects — the rebind re-points both active domains.
    expect(service.sessionDomain()?.name).toBe('NW (renamed)');
    expect(service.searchDomain()?.name).toBe('NW (DE renamed)');
  });

  it('refresh() keeps the current catalog when the re-fetch fails', async () => {
    let calls = 0;
    const lookup = vi.fn(() =>
      calls++ === 0 ? of([niordDk, niordDe]) : throwError(() => new Error('boom')),
    );
    TestBed.configureTestingModule({
      providers: [
        { provide: DomainsApiService, useValue: { lookup } },
        { provide: LanguageService, useValue: { selectedLanguage: signal('en') } },
      ],
    });
    const service = TestBed.inject(DomainService);
    await service.bootstrap();

    service.refresh();
    expect(service.availableDomains()).toEqual([niordDk, niordDe]);
    expect(service.sessionDomain()).toEqual(niordDk);
  });

  it('re-fetches and rebinds domains when the active language changes', async () => {
    const language = signal('en');
    const enDk: Domain = { domainId: 'niord-dk', name: 'NW' };
    const daDk: Domain = { domainId: 'niord-dk', name: 'Efterretninger' };
    const lookup = vi.fn((opts?: { language?: string }) =>
      of(opts?.language === 'da' ? [daDk] : [enDk]),
    );
    TestBed.configureTestingModule({
      providers: [
        { provide: DomainsApiService, useValue: { lookup } },
        {
          provide: TAG_SELECTION_STORE,
          useValue: {
            clearLastSelection: vi.fn(),
            saveLastSelection: vi.fn(),
            getLastSelection: () => signal(undefined),
          },
        },
        { provide: LanguageService, useValue: { selectedLanguage: language } },
      ],
    });
    const service = TestBed.inject(DomainService);
    await service.bootstrap();
    TestBed.tick(); // first language-effect run consumes the bootstrap language ('en')
    expect(service.sessionDomain()?.name).toBe('NW');

    language.set('da');
    TestBed.tick(); // language change -> re-fetch + rebind by domainId
    expect(lookup).toHaveBeenLastCalledWith({ language: 'da' });
    expect(service.availableDomains()).toEqual([daDk]);
    expect(service.sessionDomain()?.name).toBe('Efterretninger');
  });
});
