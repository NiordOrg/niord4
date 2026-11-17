import { describe, expect, it } from 'vitest';

import type { Domain } from '../../../../../core/domain/models/domain.model';
import type { AdminPromulgationType } from './promulgation-admin.model';
import {
  buildPromulgationTypePayload,
  resolveFreshDomains,
  seedRestrictChecks,
  serializeMessageTypes,
  supportsMainType,
  type PromulgationTypeFormValue,
} from './promulgation-type-rules';

const NW_DOMAIN: Domain = {
  domainId: 'd-nw',
  name: 'NW only',
  messageSeries: [{ seriesId: 'nw-series', mainType: 'NW' }],
};
const NM_DOMAIN: Domain = {
  domainId: 'd-nm',
  name: 'NM only',
  messageSeries: [{ seriesId: 'nm-series', mainType: 'NM' }],
};
const BOTH_DOMAIN: Domain = {
  domainId: 'd-both',
  name: 'Both',
  messageSeries: [
    { seriesId: 'nw-series-2', mainType: 'NW' },
    { seriesId: 'nm-series-2', mainType: 'NM' },
  ],
};
/** A domain without any message series supports neither main type. */
const BARE_DOMAIN: Domain = { domainId: 'd-bare', name: 'Bare' };

describe('supportsMainType', () => {
  it('treats an empty selection as supporting every main type (no restriction)', () => {
    expect(supportsMainType([], 'NW')).toBe(true);
    expect(supportsMainType([], 'NM')).toBe(true);
  });

  it('a selected domain without message series supports neither main type', () => {
    expect(supportsMainType([BARE_DOMAIN], 'NW')).toBe(false);
    expect(supportsMainType([BARE_DOMAIN], 'NM')).toBe(false);
  });

  it('derives per-main-type support from the selected domains’ series', () => {
    expect(supportsMainType([NW_DOMAIN], 'NW')).toBe(true);
    expect(supportsMainType([NW_DOMAIN], 'NM')).toBe(false);
    expect(supportsMainType([NM_DOMAIN], 'NM')).toBe(true);
    expect(supportsMainType([NM_DOMAIN], 'NW')).toBe(false);
  });

  it('ANY matching domain in a mixed selection is enough', () => {
    expect(supportsMainType([BARE_DOMAIN, NW_DOMAIN], 'NW')).toBe(true);
    expect(supportsMainType([NW_DOMAIN, NM_DOMAIN], 'NM')).toBe(true);
    expect(supportsMainType([BOTH_DOMAIN], 'NW')).toBe(true);
    expect(supportsMainType([BOTH_DOMAIN], 'NM')).toBe(true);
  });
});

describe('resolveFreshDomains', () => {
  it('replaces stored VOs with the catalog match by domainId (fresh messageSeries win)', () => {
    // The stored copy stales the series to NM; the catalog knows d-nw is NW.
    const stale: Domain = {
      domainId: 'd-nw',
      name: 'Stale copy',
      messageSeries: [{ seriesId: 'old', mainType: 'NM' }],
    };
    const resolved = resolveFreshDomains([stale], [NW_DOMAIN, NM_DOMAIN]);
    expect(resolved).toEqual([NW_DOMAIN]);
    expect(supportsMainType(resolved, 'NW')).toBe(true);
    expect(supportsMainType(resolved, 'NM')).toBe(false);
  });

  it('keeps a stored VO whose id is missing from the catalog (still picked, still round-trips)', () => {
    const retired: Domain = {
      domainId: 'd-retired',
      messageSeries: [{ seriesId: 's', mainType: 'NW' }],
    };
    expect(resolveFreshDomains([retired], [NW_DOMAIN])).toEqual([retired]);
  });

  it('maps an absent stored list to an empty selection', () => {
    expect(resolveFreshDomains(undefined, [NW_DOMAIN])).toEqual([]);
  });
});

describe('seedRestrictChecks', () => {
  it('seeds raw membership across all 8 sub-types, ungated by domain support', () => {
    const checks = seedRestrictChecks(['LOCAL_WARNING', 'TEMPORARY_NOTICE']);
    expect(checks.LOCAL_WARNING).toBe(true);
    expect(checks.TEMPORARY_NOTICE).toBe(true);
    expect(checks.COASTAL_WARNING).toBe(false);
    expect(checks.SUBAREA_WARNING).toBe(false);
    expect(checks.NAVAREA_WARNING).toBe(false);
    expect(checks.PRELIMINARY_NOTICE).toBe(false);
    expect(checks.PERMANENT_NOTICE).toBe(false);
    expect(checks.MISCELLANEOUS_NOTICE).toBe(false);
  });

  it('an absent restriction set seeds every check unchecked', () => {
    expect(Object.values(seedRestrictChecks(undefined)).every((checked) => !checked)).toBe(true);
  });
});

describe('serializeMessageTypes — the pruning matrix', () => {
  const checks = seedRestrictChecks(['TEMPORARY_NOTICE', 'LOCAL_WARNING']);

  it('no domains: every check survives, serialized NW block first then NM', () => {
    // Stored order was NM-first; the payload order is NW-then-NM.
    expect(serializeMessageTypes(checks, [])).toEqual(['LOCAL_WARNING', 'TEMPORARY_NOTICE']);
  });

  it('domains supporting both: every check survives', () => {
    expect(serializeMessageTypes(checks, [BOTH_DOMAIN])).toEqual([
      'LOCAL_WARNING',
      'TEMPORARY_NOTICE',
    ]);
    expect(serializeMessageTypes(checks, [NW_DOMAIN, NM_DOMAIN])).toEqual([
      'LOCAL_WARNING',
      'TEMPORARY_NOTICE',
    ]);
  });

  it('NW-only domains: the NM check is silently pruned', () => {
    expect(serializeMessageTypes(checks, [NW_DOMAIN])).toEqual(['LOCAL_WARNING']);
  });

  it('NM-only domains: the NW check is silently pruned', () => {
    expect(serializeMessageTypes(checks, [NM_DOMAIN])).toEqual(['TEMPORARY_NOTICE']);
  });

  it('domains supporting neither: everything is pruned to []', () => {
    expect(serializeMessageTypes(checks, [BARE_DOMAIN])).toEqual([]);
  });

  it('nothing checked stays [] (unrestricted — sent, never omitted)', () => {
    expect(serializeMessageTypes(seedRestrictChecks(undefined), [])).toEqual([]);
    expect(serializeMessageTypes(seedRestrictChecks([]), [BOTH_DOMAIN])).toEqual([]);
  });

  it('keeps the declared order inside each block', () => {
    const all = seedRestrictChecks([
      'MISCELLANEOUS_NOTICE',
      'NAVAREA_WARNING',
      'LOCAL_WARNING',
      'PERMANENT_NOTICE',
      'COASTAL_WARNING',
      'TEMPORARY_NOTICE',
      'SUBAREA_WARNING',
      'PRELIMINARY_NOTICE',
    ]);
    expect(serializeMessageTypes(all, [])).toEqual([
      'LOCAL_WARNING',
      'COASTAL_WARNING',
      'SUBAREA_WARNING',
      'NAVAREA_WARNING',
      'TEMPORARY_NOTICE',
      'PRELIMINARY_NOTICE',
      'PERMANENT_NOTICE',
      'MISCELLANEOUS_NOTICE',
    ]);
  });
});

describe('buildPromulgationTypePayload', () => {
  const opened: AdminPromulgationType = {
    typeId: 'navtex-dk',
    serviceId: 'navtex',
    name: 'NAVTEX',
    priority: 10,
    active: true,
    requirement: 'MANDATORY',
    language: 'da',
    domains: [NW_DOMAIN, NM_DOMAIN],
    messageTypes: ['LOCAL_WARNING', 'TEMPORARY_NOTICE'],
    scriptResourcePaths: ['templates/navtex.ftl'],
  };
  const value: PromulgationTypeFormValue = {
    typeId: 'navtex-dk',
    name: 'NAVTEX renamed',
    active: false,
    requirement: 'OPTIONAL',
    priority: 5,
    language: '',
    domains: [NW_DOMAIN],
    scriptResourcePaths: ['templates/navtex.ftl', '  ', ''],
  };

  it('rebuilds messageTypes from the checks pruned by CURRENT domain support', () => {
    const payload = buildPromulgationTypePayload(
      opened,
      value,
      seedRestrictChecks(opened.messageTypes),
    );
    // NM support was lost with the domain — its saved restriction is pruned.
    expect(payload.messageTypes).toEqual(['LOCAL_WARNING']);
    expect(payload.domains).toEqual([NW_DOMAIN]);
  });

  it('omits a blank language ("any") and keeps a chosen one', () => {
    const checks = seedRestrictChecks(undefined);
    expect(buildPromulgationTypePayload(opened, value, checks).language).toBeUndefined();
    expect(
      buildPromulgationTypePayload(opened, { ...value, language: 'en' }, checks).language,
    ).toBe('en');
  });

  it('drops blank script-path rows (the picker keeps at least one empty row)', () => {
    const payload = buildPromulgationTypePayload(opened, value, seedRestrictChecks(undefined));
    expect(payload.scriptResourcePaths).toEqual(['templates/navtex.ftl']);
  });

  it('carries backend-side fields the form never touches through an edit round-trip', () => {
    const withExtra = { ...opened, extra: 'server-side' } as AdminPromulgationType;
    const payload = buildPromulgationTypePayload(withExtra, value, seedRestrictChecks(undefined));
    expect((payload as unknown as Record<string, unknown>)['extra']).toBe('server-side');
    expect(payload.serviceId).toBe('navtex');
  });

  it('never mutates the opened entity', () => {
    const frozen = Object.freeze({
      ...opened,
      domains: Object.freeze([NW_DOMAIN, NM_DOMAIN]),
      messageTypes: Object.freeze(['LOCAL_WARNING', 'TEMPORARY_NOTICE']),
    }) as AdminPromulgationType;
    const payload = buildPromulgationTypePayload(
      frozen,
      value,
      seedRestrictChecks(frozen.messageTypes),
    );
    expect(payload).not.toBe(frozen);
    expect(frozen.name).toBe('NAVTEX');
    expect(frozen.messageTypes).toEqual(['LOCAL_WARNING', 'TEMPORARY_NOTICE']);
  });
});
