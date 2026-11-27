import { describe, expect, it } from 'vitest';

import type { PublicationSeries } from '../../../services/api/publication-series-api.service';
import {
  availabilityNeedsDomain,
  buildSeriesPayload,
  cutoffDefaultRefused,
  hasReport,
  isCadenceless,
  isCompilation,
  isQueryBacked,
  isReservedReportParamKey,
  livenessIsForced,
  messageSortByChoice,
  messageSortByOptions,
  nextIssueIsDerived,
  ownerVerdict,
  previewIntervalFrom,
  reservedReportParamKeys,
  selectsByCriteria,
  seriesIdFromName,
  seriesWritable,
  tilesPeriods,
  TIME_RELATIONS,
  toDescModels,
  toFormModel,
  toReportParamRows,
  usesDayOfMonth,
  usesFirstIssueStart,
  usesMonth,
  usesPeriodCutoff,
  usesWeekday,
  type DescModels,
  type SeriesFormModel,
} from './series-rules';

/**
 * The shaping between what an admin typed and what the server is told.
 *
 * Every "exactly when" S-rule has the same failure shape: a field that lingers
 * after the thing it belonged to is switched off. It saves fine as a DRAFT and
 * refuses on activation, naming a rule about a field the admin cannot see because
 * the control that owns it is hidden. So the assertions below are mostly about
 * what is ABSENT.
 */

const BASE: PublicationSeries = {
  kind: 'SCHEDULED',
  seriesId: 'weekly-ntm',
  status: 'DRAFT',
  domainId: 'niord-nm',
  cadence: 'WEEKLY',
  contentMode: 'GENERATED_FROM_QUERY',
  languages: ['da', 'en'],
  descs: [
    { lang: 'da', name: 'Ugentlig EfS', fileNamePattern: 'efs-uge-${week}.pdf' },
    { lang: 'en', name: 'Weekly NtM', fileNamePattern: 'ntm-week-${week}.pdf' },
  ],
};

const LANGUAGES = ['da', 'en'] as const;

function modelOf(overrides: Partial<SeriesFormModel> = {}): SeriesFormModel {
  return { ...toFormModel(BASE), ...overrides };
}

function descsOf(): DescModels {
  return toDescModels(BASE, LANGUAGES);
}

function payload(overrides: Partial<SeriesFormModel> = {}, criteria = CRITERIA) {
  return buildSeriesPayload(BASE, modelOf(overrides), descsOf(), LANGUAGES, criteria, []);
}

const CRITERIA = {
  criteria: [{ kind: 'messageSeries' as const, operator: 'IN' as const, values: ['dma-nm'] }],
};

describe('applicability', () => {
  it('gives a weekday to a weekly cadence and to nothing else', () => {
    expect(usesWeekday({ cadence: 'WEEKLY' })).toBe(true);
    for (const cadence of ['NONE', 'DAILY', 'MONTHLY', 'YEARLY'] as const) {
      expect(usesWeekday({ cadence })).toBe(false);
    }
  });

  it('gives a day of the month to monthly and yearly, and a month to yearly alone', () => {
    expect(usesDayOfMonth({ cadence: 'MONTHLY' })).toBe(true);
    expect(usesDayOfMonth({ cadence: 'YEARLY' })).toBe(true);
    expect(usesDayOfMonth({ cadence: 'WEEKLY' })).toBe(false);

    expect(usesMonth({ cadence: 'YEARLY' })).toBe(true);
    expect(usesMonth({ cadence: 'MONTHLY' })).toBe(false);
  });

  it('treats page setup as belonging to a report, not to a query', () => {
    expect(hasReport({ contentMode: 'GENERATED_FROM_QUERY', reportId: 'fm-report' })).toBe(true);
    expect(hasReport({ contentMode: 'GENERATED_FROM_QUERY', reportId: '   ' })).toBe(false);
    expect(hasReport({ contentMode: 'UPLOADED_FILE', reportId: 'fm-report' })).toBe(false);
  });

  /**
   * The third regime, and what it does to the two settings the other two share.
   *
   * A compilation is query-backed — it generates a document like any other
   * generated series — so a predicate that only asked `isQueryBacked` would go on
   * offering it criteria and a liveness box that S-24 refuses.
   */
  it('knows a compilation from the regime, and only on a query-backed series', () => {
    expect(
      isCompilation({
        contentMode: 'GENERATED_FROM_QUERY',
        timeRelation: 'COMPILED_FROM_SOURCE',
      }),
    ).toBe(true);
    expect(
      isCompilation({
        contentMode: 'GENERATED_FROM_QUERY',
        timeRelation: 'PUBLISHED_IN_INTERVAL',
      }),
    ).toBe(false);
    expect(
      isCompilation({ contentMode: 'UPLOADED_FILE', timeRelation: 'COMPILED_FROM_SOURCE' }),
    ).toBe(false);
  });

  it('applies criteria and liveness to every query-backed series but a compilation', () => {
    expect(
      selectsByCriteria({
        contentMode: 'GENERATED_FROM_QUERY',
        timeRelation: 'PUBLISHED_IN_INTERVAL',
      }),
    ).toBe(true);
    expect(
      selectsByCriteria({
        contentMode: 'GENERATED_FROM_QUERY',
        timeRelation: 'IN_FORCE_AT_CUTOFF',
      }),
    ).toBe(true);
    expect(
      selectsByCriteria({
        contentMode: 'GENERATED_FROM_QUERY',
        timeRelation: 'COMPILED_FROM_SOURCE',
      }),
    ).toBe(false);
    expect(
      selectsByCriteria({ contentMode: 'EXTERNAL_LINK', timeRelation: 'PUBLISHED_IN_INTERVAL' }),
    ).toBe(false);
  });

  /** Two of the three regimes tile; only the in-force one has no lower bound. */
  it('tiles for an interval series and for a compilation, and for nothing else', () => {
    expect(tilesPeriods({ timeRelation: 'PUBLISHED_IN_INTERVAL' })).toBe(true);
    expect(tilesPeriods({ timeRelation: 'COMPILED_FROM_SOURCE' })).toBe(true);
    expect(tilesPeriods({ timeRelation: 'IN_FORCE_AT_CUTOFF' })).toBe(false);
  });

  /** The picker offers all three, because the server accepts all three. */
  it('offers every regime the wire carries', () => {
    expect([...TIME_RELATIONS]).toEqual([
      'PUBLISHED_IN_INTERVAL',
      'IN_FORCE_AT_CUTOFF',
      'COMPILED_FROM_SOURCE',
    ]);
  });

  it('offers a first-interval start only to an interval-based series', () => {
    expect(
      usesFirstIssueStart({
        contentMode: 'GENERATED_FROM_QUERY',
        timeRelation: 'PUBLISHED_IN_INTERVAL',
      }),
    ).toBe(true);
    expect(
      usesFirstIssueStart({
        contentMode: 'GENERATED_FROM_QUERY',
        timeRelation: 'IN_FORCE_AT_CUTOFF',
      }),
    ).toBe(false);
    // A compilation tiles as well: its period runs from the last issue's cut-off
    // to this one's, and the first of them has to start somewhere.
    expect(
      usesFirstIssueStart({
        contentMode: 'GENERATED_FROM_QUERY',
        timeRelation: 'COMPILED_FROM_SOURCE',
      }),
    ).toBe(true);
    expect(
      usesFirstIssueStart({ contentMode: 'UPLOADED_FILE', timeRelation: 'PUBLISHED_IN_INTERVAL' }),
    ).toBe(false);
  });

  it('forces liveness on for an in-force series', () => {
    expect(
      livenessIsForced({ contentMode: 'GENERATED_FROM_QUERY', timeRelation: 'IN_FORCE_AT_CUTOFF' }),
    ).toBe(true);
    expect(
      livenessIsForced({
        contentMode: 'GENERATED_FROM_QUERY',
        timeRelation: 'PUBLISHED_IN_INTERVAL',
      }),
    ).toBe(false);
    // Not forced and not applicable: a compilation never filters on liveness, so
    // a lock here would send a flag S-24 refuses.
    expect(
      livenessIsForced({
        contentMode: 'GENERATED_FROM_QUERY',
        timeRelation: 'COMPILED_FROM_SOURCE',
      }),
    ).toBe(false);
  });

  it('knows a one-off from a cadence', () => {
    expect(isCadenceless({ cadence: 'NONE' })).toBe(true);
    expect(isCadenceless({ cadence: 'DAILY' })).toBe(false);
    expect(isQueryBacked({ contentMode: 'EXTERNAL_LINK' })).toBe(false);
  });

  /** Every cadence says the same thing about the next issue, and NONE says nothing. */
  it('derives the next issue from a cadence and leaves it to be chosen without one', () => {
    expect(nextIssueIsDerived({ cadence: 'WEEKLY' })).toBe(true);
    expect(nextIssueIsDerived({ cadence: 'DAILY' })).toBe(true);
    expect(nextIssueIsDerived({ cadence: 'MONTHLY' })).toBe(true);
    expect(nextIssueIsDerived({ cadence: 'YEARLY' })).toBe(true);
    expect(nextIssueIsDerived({ cadence: 'NONE' })).toBe(false);
  });
});

describe('seeding', () => {
  /**
   * The source survives the round trip, and a series without one seeds blank.
   *
   * `undefined` in the model would reach a `<select>` as the string "undefined"
   * and select nothing, which is how a stored source turns into a cleared one on
   * the next save.
   */
  it('round-trips the source series, and seeds an empty one where there is none', () => {
    const compiled = toFormModel({
      ...BASE,
      timeRelation: 'COMPILED_FROM_SOURCE',
      sourceSeriesId: 'weekly-ntm',
    });
    expect(compiled.sourceSeriesId).toBe('weekly-ntm');
    const out = buildSeriesPayload(BASE, compiled, descsOf(), LANGUAGES, CRITERIA, []);
    expect(out.sourceSeriesId).toBe('weekly-ntm');
    expect(toFormModel(BASE).sourceSeriesId).toBe('');
  });

  it('keeps a language with no desc row typeable', () => {
    const descs = toDescModels(BASE, ['da', 'en', 'de']);
    expect(descs['de']).toEqual({
      name: '',
      nameSuggestionPattern: '',
      fileNamePattern: '',
      messageReferenceFormat: '',
      linkPattern: '',
    });
    expect(descs['da'].name).toBe('Ugentlig EfS');
  });

  it('defaults languageSpecific to true, matching the entity', () => {
    expect(toFormModel(BASE).languageSpecific).toBe(true);
    expect(toFormModel({ ...BASE, languageSpecific: false }).languageSpecific).toBe(false);
  });

  it('reads report parameters in their stored order', () => {
    const rows = toReportParamRows({ ...BASE, reportParams: { volume: '117', issn: '1397-6656' } });
    expect(rows).toEqual([
      { key: 'volume', value: '117' },
      { key: 'issn', value: '1397-6656' },
    ]);
  });
});

describe('the payload', () => {
  it('sends a weekday for a weekly series and no month', () => {
    const out = payload({ cadence: 'WEEKLY', nominalCutoffDay: 'WEDNESDAY' });
    expect(out.nominalCutoffDay).toBe('WEDNESDAY');
    expect(out.nominalCutoffMonth).toBeUndefined();
    expect(out.nominalCutoffDayOfMonth).toBeUndefined();
  });

  it('drops the weekday when the cadence stops being weekly', () => {
    const out = payload({
      cadence: 'YEARLY',
      nominalCutoffDay: 'WEDNESDAY',
      nominalCutoffMonth: 12,
      nominalCutoffDayOfMonth: 31,
    });
    expect(out.nominalCutoffDay).toBeUndefined();
    expect(out.nominalCutoffMonth).toBe(12);
    expect(out.nominalCutoffDayOfMonth).toBe(31);
  });

  it('drops the nominal time for a one-off, and locks its numbering and next issue', () => {
    const out = payload({
      cadence: 'NONE',
      numberingScheme: 'ISO_WEEK_YEAR',
      nextIssueCreation: 'AUTO_ON_PUBLISH',
    });
    expect(out.nominalCutoffTime).toBeUndefined();
    expect(out.numberingScheme).toBe('NONE');
    expect(out.nextIssueCreation).toBe('MANUAL');
  });

  /**
   * The other half of S-8: a cadence IS the automation.
   *
   * A cadenced series opens its next issue when the current one publishes, so
   * the field follows the cadence rather than whatever a form once held — and a
   * stored MANUAL, which is what an imported series and the new-series template
   * both arrive with, heals on the next save instead of travelling back out.
   */
  it('sends automatic creation for a cadenced series, whatever the model holds', () => {
    expect(payload({ cadence: 'WEEKLY', nextIssueCreation: 'MANUAL' }).nextIssueCreation).toBe(
      'AUTO_ON_PUBLISH',
    );
    expect(payload({ cadence: 'YEARLY', nextIssueCreation: 'MANUAL' }).nextIssueCreation).toBe(
      'AUTO_ON_PUBLISH',
    );
  });

  it('strips criteria, time relation and liveness when the series stops being query-backed', () => {
    const out = payload({ contentMode: 'EXTERNAL_LINK' });
    expect(out.criteria).toBeUndefined();
    expect(out.timeRelation).toBeUndefined();
    expect(out.aliveAtCutoff).toBeUndefined();
    expect(out.reportId).toBeUndefined();
  });

  it('forces liveness true for an in-force series even when the box reads false', () => {
    const out = payload({ timeRelation: 'IN_FORCE_AT_CUTOFF', aliveAtCutoff: false });
    expect(out.aliveAtCutoff).toBe(true);
  });

  /**
   * S-24 from both ends: a compilation sends its source and neither of the two
   * settings it replaces, and anything else sends no source at all.
   *
   * The criteria and the liveness flag are stripped rather than erased, exactly
   * as a weekday is when the cadence changes — switching the regime back has to
   * bring the criteria document home.
   */
  it('sends the source series for a compilation, and strips criteria and liveness', () => {
    const out = payload({
      timeRelation: 'COMPILED_FROM_SOURCE',
      sourceSeriesId: 'weekly-ntm',
      aliveAtCutoff: true,
    });
    expect(out.sourceSeriesId).toBe('weekly-ntm');
    expect(out.criteria).toBeUndefined();
    expect(out.aliveAtCutoff).toBeUndefined();
    // The regime itself is still query-backed, so the report and the document it
    // renders are untouched.
    expect(out.timeRelation).toBe('COMPILED_FROM_SOURCE');
    expect(out.contentMode).toBe('GENERATED_FROM_QUERY');
  });

  it('sends no source series under the other regimes, and none for a blank one', () => {
    expect(payload({ sourceSeriesId: 'weekly-ntm' }).sourceSeriesId).toBeUndefined();
    expect(
      payload({ timeRelation: 'IN_FORCE_AT_CUTOFF', sourceSeriesId: 'weekly-ntm' }).sourceSeriesId,
    ).toBeUndefined();
    expect(
      payload({ timeRelation: 'COMPILED_FROM_SOURCE', sourceSeriesId: '  ' }).sourceSeriesId,
    ).toBeUndefined();
  });

  /** A compilation tiles, so its first interval still travels. */
  it('keeps the first-interval start on a compilation', () => {
    const out = payload({
      timeRelation: 'COMPILED_FROM_SOURCE',
      sourceSeriesId: 'weekly-ntm',
      firstIssueStartsAt: 1_700_000_000_000,
    });
    expect(out.firstIssueStartsAt).toBe(1_700_000_000_000);
  });

  it('sends page setup only alongside a report', () => {
    const withReport = payload({ reportId: 'fm-report' });
    expect(withReport.pageSize).toBe('A4');
    expect(withReport.pageOrientation).toBe('PORTRAIT');
    expect(withReport.mapThumbnails).toBe(false);

    const without = payload({ reportId: '' });
    expect(without.pageSize).toBeUndefined();
    expect(without.pageOrientation).toBeUndefined();
    expect(without.mapThumbnails).toBeUndefined();
  });

  it('sends a sort direction only alongside a sort field', () => {
    expect(payload({ messageSortBy: '' }).messageSortOrder).toBeUndefined();
    const sorted = payload({ messageSortBy: 'publishDateFrom', messageSortOrder: 'DESC' });
    expect(sorted.messageSortBy).toBe('publishDateFrom');
    expect(sorted.messageSortOrder).toBe('DESC');
  });

  it('drops a first-interval start that no longer applies', () => {
    const interval = payload({ firstIssueStartsAt: 1_700_000_000_000 });
    expect(interval.firstIssueStartsAt).toBe(1_700_000_000_000);
    const inForce = payload({
      timeRelation: 'IN_FORCE_AT_CUTOFF',
      firstIssueStartsAt: 1_700_000_000_000,
    });
    expect(inForce.firstIssueStartsAt).toBeUndefined();
  });

  it('sends one desc row per declared language, in the declared order', () => {
    const out = buildSeriesPayload(BASE, modelOf(), descsOf(), ['en', 'da'], CRITERIA, []);
    expect(out.descs.map((d) => d.lang)).toEqual(['en', 'da']);
  });

  it('drops a desc row for a language the series no longer declares', () => {
    const out = buildSeriesPayload(BASE, modelOf(), descsOf(), ['da'], CRITERIA, []);
    expect(out.descs.map((d) => d.lang)).toEqual(['da']);
  });

  it('sends a language with no text as an empty row rather than omitting it', () => {
    // S-12 wants a row per declared language; a missing row and a blank one fail
    // different rules, and the blank one names the field the admin has to fill in.
    const out = buildSeriesPayload(BASE, modelOf(), descsOf(), ['da', 'de'], CRITERIA, []);
    expect(out.descs.find((d) => d.lang === 'de')).toEqual({
      lang: 'de',
      name: '',
      nameSuggestionPattern: undefined,
      fileNamePattern: undefined,
      messageReferenceFormat: undefined,
      linkPattern: undefined,
    });
  });

  it('turns blank text into absent, so a cleared field is not sent as a value', () => {
    const descs = descsOf();
    descs['da'].fileNamePattern = '   ';
    const out = buildSeriesPayload(BASE, modelOf(), descs, ['da'], CRITERIA, []);
    expect(out.descs[0].fileNamePattern).toBeUndefined();
  });

  it('folds report parameter rows into an object, skipping blank keys', () => {
    const out = buildSeriesPayload(
      BASE,
      modelOf({ reportId: 'fm-report' }),
      descsOf(),
      LANGUAGES,
      CRITERIA,
      [
        { key: 'volume', value: '117' },
        { key: '  ', value: 'orphan' },
        { key: 'issn', value: '1397-6656' },
      ],
    );
    expect(out.reportParams).toEqual({ volume: '117', issn: '1397-6656' });
  });

  it('never lets the form set the status', () => {
    // The transition is the backend's, validated. A save carrying a status would
    // route around the rules that decide whether a series may be active at all.
    const out = payload();
    expect(out.status).toBe(BASE.status);
  });

  // ------------------------------------------------------------ cut-off default

  /** S-21: only a yearly series has a period whose boundary means anything. */
  it('allows a period boundary on a yearly series and on nothing else', () => {
    expect(usesPeriodCutoff({ cadence: 'YEARLY' })).toBe(true);
    for (const cadence of ['NONE', 'DAILY', 'WEEKLY', 'MONTHLY'] as const) {
      expect(usesPeriodCutoff({ cadence })).toBe(false);
    }
  });

  it('reports a period boundary left behind by a cadence change', () => {
    expect(cutoffDefaultRefused({ cadence: 'WEEKLY', cutoffDefault: 'PERIOD_END' })).toBe(true);
    expect(cutoffDefaultRefused({ cadence: 'WEEKLY', cutoffDefault: 'RELEASE_MOMENT' })).toBe(
      false,
    );
    expect(cutoffDefaultRefused({ cadence: 'YEARLY', cutoffDefault: 'PERIOD_END' })).toBe(false);
  });

  /** Absent behaves as the release moment — the entity default and the safe answer. */
  it('seeds the release moment when the series carries no preference', () => {
    expect(toFormModel(BASE).cutoffDefault).toBe('RELEASE_MOMENT');
    expect(toFormModel({ ...BASE, cutoffDefault: 'PERIOD_END' }).cutoffDefault).toBe('PERIOD_END');
  });

  /**
   * ALWAYS sent — the server refuses a series that does not say where its cut-off
   * falls — but stripped back to the release moment where the rule forbids a
   * boundary, so a cadence change cannot carry a now-illegal value into a save.
   */
  it('always sends a cut-off default, stripped to the release moment where the rule forbids one', () => {
    expect(payload({ cadence: 'YEARLY', cutoffDefault: 'PERIOD_END' }).cutoffDefault).toBe(
      'PERIOD_END',
    );
    expect(payload({ cadence: 'WEEKLY', cutoffDefault: 'PERIOD_END' }).cutoffDefault).toBe(
      'RELEASE_MOMENT',
    );
    expect(payload().cutoffDefault).toBe('RELEASE_MOMENT');
  });
});

/**
 * S-23, pinned key by key.
 *
 * The set is seven, not four, and they are different facts: four strings the
 * issue PRINTS, two numbers the numbering DERIVED and the weeks as the issue's
 * name writes them. All seven arrive by injection, so a typed row is a second answer either way — and the list is the
 * server's, so a key going missing here is a save that fails for a reason no
 * field on the screen ever named.
 */
describe('reserved report parameters', () => {
  it('refuses every key the render injects, and nothing else', () => {
    for (const key of ['week', 'weekTo', 'weeks', 'year', 'edition', 'yearNumber', 'weekNumber']) {
      expect(isReservedReportParamKey(key)).toBe(true);
    }
    for (const key of ['volume', 'issn', 'weekly', 'yearNumbers', '']) {
      expect(isReservedReportParamKey(key)).toBe(false);
    }
  });

  it('reads a key regardless of case and surrounding space', () => {
    expect(isReservedReportParamKey('  WEEKNUMBER ')).toBe(true);
    expect(isReservedReportParamKey('YearNumber')).toBe(true);
    expect(isReservedReportParamKey('Week ')).toBe(true);
    expect(isReservedReportParamKey(' Weeks')).toBe(true);
  });

  it('names the offending rows in the order they appear, as typed', () => {
    expect(
      reservedReportParamKeys([
        { key: 'volume', value: '117' },
        { key: ' yearNumber ', value: '2026' },
        { key: 'Week', value: '36' },
      ]),
    ).toEqual(['yearNumber', 'Week']);
  });
});

/**
 * The preview window.
 *
 * The probe is run on a keystroke debounce against real messages, so the bound
 * it carries is not a nicety: an interval probe with no floor asks the resolver
 * to hydrate every message ever published, once per edit.
 */
describe('the preview window', () => {
  const CUTOFF = Date.UTC(2026, 7, 27, 10, 0, 0);
  const DAY = 24 * 60 * 60 * 1000;

  it('steps one cadence period back for a cadenced series', () => {
    expect(previewIntervalFrom(modelOf({ cadence: 'WEEKLY' }), CUTOFF)).toBe(CUTOFF - 7 * DAY);
    expect(previewIntervalFrom(modelOf({ cadence: 'DAILY' }), CUTOFF)).toBe(CUTOFF - DAY);
  });

  /** A cadence-less series has no period, and previously got no bound at all. */
  it('bounds a cadence-less series rather than probing unbounded', () => {
    const from = previewIntervalFrom(modelOf({ cadence: 'NONE' }), CUTOFF);
    expect(from).toBeDefined();
    expect(from).toBeLessThan(CUTOFF);
  });

  /** Where the series declares its own first interval, that IS the bound. */
  it('uses the declared first-interval start when there is one', () => {
    const start = Date.UTC(2026, 0, 1);
    expect(
      previewIntervalFrom(modelOf({ cadence: 'NONE', firstIssueStartsAt: start }), CUTOFF),
    ).toBe(start);
  });

  /**
   * An in-force series has no lower bound, exactly as its issues have none:
   * passing one would preview a narrower set than the series produces.
   */
  it('leaves an in-force series unbounded', () => {
    expect(
      previewIntervalFrom(modelOf({ cadence: 'NONE', timeRelation: 'IN_FORCE_AT_CUTOFF' }), CUTOFF),
    ).toBeUndefined();
  });
});

/**
 * Whose publication this is — the one owner answer every screen asks.
 *
 * Three values, because two would make a screen decide from an empty hand: a
 * publication that has not been read yet is neither this desk's nor another's,
 * and both a page and a refusal drawn on that guess are wrong half the time.
 */
describe('ownerVerdict', () => {
  it('says nothing about a publication it has not been given', () => {
    expect(ownerVerdict(undefined, 'niord-dk')).toBe('unknown');
    expect(ownerVerdict(undefined, undefined)).toBe('unknown');
  });

  it('answers owned from the desk that owns it', () => {
    expect(ownerVerdict({ domainId: 'niord-dk' }, 'niord-dk')).toBe('owned');
  });

  it('answers foreign from any other desk', () => {
    expect(ownerVerdict({ domainId: 'niord-dk' }, 'niord-de')).toBe('foreign');
  });

  /**
   * A publication with no owner belongs to every desk.
   *
   * There is no desk to switch to in order to reach it, so refusing would make
   * it unreachable from everywhere — and the one save that would give it an
   * owner lives behind the very screen that would be refused.
   */
  it('answers owned for a publication that names no desk, from anywhere', () => {
    expect(ownerVerdict({ domainId: undefined }, 'niord-de')).toBe('owned');
    expect(ownerVerdict({}, undefined)).toBe('owned');
    // Blank is absent: the VOs omit nulls, and a cleared field arrives either way.
    expect(ownerVerdict({ domainId: '  ' }, undefined)).toBe('owned');
  });

  /** A caller sitting at no desk is foreign to a publication that names one. */
  it('answers foreign to a caller in no domain', () => {
    expect(ownerVerdict({ domainId: 'niord-dk' }, undefined)).toBe('foreign');
    expect(ownerVerdict({ domainId: 'niord-dk' }, '')).toBe('foreign');
  });
});

/**
 * Who may WRITE a series: the same verdict, one notch looser.
 *
 * The rule is the server's and the point of having it here is not to duplicate a
 * decision but to avoid a button that always fails. Everything but another
 * desk's publication may be offered — including one still on its way, because
 * the alternative is every action vanishing and coming back while a panel loads.
 */
describe('seriesWritable', () => {
  it('lets the domain that owns a series write it', () => {
    expect(seriesWritable({ domainId: 'niord-dk' }, 'niord-dk')).toBe(true);
  });

  it('refuses a series another domain is accountable for', () => {
    expect(seriesWritable({ domainId: 'niord-dk' }, 'niord-de')).toBe(false);
  });

  /**
   * The half that is deliberate, and not an oversight to be tightened later.
   *
   * A row with no owner predates the owner being mandatory, so there is no
   * domain to switch to in order to gain the right — refusing here would make it
   * uneditable from every screen, where the save is refused on its own terms and
   * says so.
   */
  it('lets any domain write a series that names none', () => {
    expect(seriesWritable({ domainId: undefined }, 'niord-de')).toBe(true);
    expect(seriesWritable({}, undefined)).toBe(true);
    // Blank is absent: the VOs omit nulls, and a cleared field arrives either way.
    expect(seriesWritable({ domainId: '  ' }, undefined)).toBe(true);
  });

  /**
   * A caller sitting at no desk at all is refused rather than waved through.
   *
   * Waving them through would make the whole rule optional for anybody whose
   * client simply sends no domain — which is the one case a client controls.
   */
  it('refuses a domain-owned series to a caller in no domain', () => {
    expect(seriesWritable({ domainId: 'niord-dk' }, undefined)).toBe(false);
    expect(seriesWritable({ domainId: 'niord-dk' }, '')).toBe(false);
  });

  /**
   * A series that is not in hand yet answers yes.
   *
   * The screens hand this whatever series they have loaded, and the issue
   * workbench renders before its series arrives. Answering no there would make
   * every action vanish and come back while the panel loads, which reads as a
   * broken screen — and the refusal is still named if a request goes out.
   */
  it('says nothing about a series it has not been given', () => {
    expect(seriesWritable(undefined, 'niord-dk')).toBe(true);
    expect(seriesWritable(undefined, undefined)).toBe(true);
  });
});

/**
 * "Selected domains", with nothing selected.
 *
 * The one availability state that says less than the option beside it: the
 * payload sends an empty list, which is a publication shared with nobody — what
 * OWNER_ONLY says, under a name that claims otherwise. Both editors gate their
 * save on this and the control names it in place, all three through here.
 */
describe('availabilityNeedsDomain', () => {
  it('refuses selected domains with an empty list', () => {
    expect(availabilityNeedsDomain({ availability: 'SELECTED_DOMAINS' }, [])).toBe(true);
  });

  it('accepts selected domains once one is named', () => {
    expect(availabilityNeedsDomain({ availability: 'SELECTED_DOMAINS' }, ['niord-annex'])).toBe(
      false,
    );
  });

  /**
   * A share with a domain the catalogue no longer carries still counts.
   *
   * An inactive desk is somebody the publication is shared with, and the list is
   * counted as it is HELD — so a publication whose only share is with a
   * deactivated domain is not a publication shared with nobody.
   */
  it('counts a share the catalogue no longer carries', () => {
    expect(availabilityNeedsDomain({ availability: 'SELECTED_DOMAINS' }, ['niord-gone'])).toBe(
      false,
    );
  });

  /** The other two carry no list, so an empty one says nothing about them. */
  it('says nothing about the other two options', () => {
    expect(availabilityNeedsDomain({ availability: 'OWNER_ONLY' }, [])).toBe(false);
    expect(availabilityNeedsDomain({ availability: 'ALL_DOMAINS' }, [])).toBe(false);
  });
});

/**
 * The identifier a name suggests.
 *
 * The id is the import/export key and the handle every stored citation points
 * at, and S-16 freezes it at create — so a proposal has to be narrow enough to
 * survive a URL, a file name and an export file without being escaped
 * differently in each.
 */
describe('seriesIdFromName', () => {
  it('lower-cases and hyphenates a name', () => {
    expect(seriesIdFromName('Weekly Notices to Mariners')).toBe('weekly-notices-to-mariners');
  });

  /**
   * The Danish letters carry no combining mark, so decomposition alone drops
   * them: "Søndag" became "sndag", which is not a word in any language.
   */
  it('spells out letters that have no ASCII form', () => {
    expect(seriesIdFromName('Ugentlig EfS for Søfarende på Øresund')).toBe(
      'ugentlig-efs-for-soefarende-paa-oeresund',
    );
    expect(seriesIdFromName('Årlig oversigt')).toBe('aarlig-oversigt');
  });

  /** An accent is dropped rather than spelled out: the letter underneath is right. */
  it('strips accents from letters that keep their ASCII form', () => {
    expect(seriesIdFromName('Résumé des avis')).toBe('resume-des-avis');
  });

  it('collapses punctuation and trims the ends', () => {
    expect(seriesIdFromName('  EfS 27/2026 — «A & B»  ')).toBe('efs-27-2026-a-b');
  });

  /** Nothing usable in, nothing out: a blank proposal leaves the field empty. */
  it('answers empty for a name with nothing to make an id from', () => {
    expect(seriesIdFromName('')).toBe('');
    expect(seriesIdFromName('—  —')).toBe('');
  });
});

describe('messageSortByOptions', () => {
  /** The closed set the server's parser names, each with a text: no code is shown. */
  it('offers the domain order and the five fields the server sorts by, each named', () => {
    expect(messageSortByOptions('')).toEqual([
      { value: '', labelKey: 'admin.publications.series.option.domainSort' },
      { value: 'AREA', labelKey: 'messages.shell.action.sortBy.area' },
      { value: 'PUBLISH_DATE', labelKey: 'messages.shell.action.sortBy.publishDate' },
      { value: 'EVENT_DATE', labelKey: 'messages.shell.action.sortBy.date' },
      { value: 'FOLLOW_UP_DATE', labelKey: 'messages.shell.action.sortBy.followUpDate' },
      { value: 'ID', labelKey: 'messages.shell.action.sortBy.id' },
    ]);
    expect(messageSortByOptions('EVENT_DATE')).toEqual(messageSortByOptions(''));
  });

  /** A spelling the server also reads is the choice it names, not a code of its own. */
  it('reads a stored value as the server does: trimmed, in any case, and its other spellings', () => {
    expect(messageSortByChoice('publishDate')).toBe('PUBLISH_DATE');
    expect(messageSortByChoice(' area ')).toBe('AREA');
    expect(messageSortByChoice('EVENTDATE')).toBe('EVENT_DATE');
    expect(messageSortByChoice('FollowUpDate')).toBe('FOLLOW_UP_DATE');
    expect(messageSortByChoice('id')).toBe('ID');
    expect(messageSortByChoice('  ')).toBe('');
    expect(messageSortByChoice('TITLE')).toBe('TITLE');
    expect(messageSortByOptions('publishDate')).toEqual(messageSortByOptions(''));
    expect(messageSortByOptions('FOLLOWUPDATE')).toEqual(messageSortByOptions(''));
  });

  /** A value the server would refuse stays the series' value, shown as stored. */
  it('keeps a stored value the server would refuse, without a text, after the domain order', () => {
    const options = messageSortByOptions('TITLE');
    expect(options.map((option) => option.value)).toEqual([
      '',
      'TITLE',
      'AREA',
      'PUBLISH_DATE',
      'EVENT_DATE',
      'FOLLOW_UP_DATE',
      'ID',
    ]);
    expect(options[1].labelKey).toBeUndefined();
  });
});
