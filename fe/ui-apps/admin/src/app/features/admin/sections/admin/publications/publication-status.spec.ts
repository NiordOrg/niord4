import { describe, expect, it } from 'vitest';

import en from '../../../../../../../../../languages/en/i18n/admin/en.json';
import {
  MEMBER_REASONS,
  TIME_RELATIONS,
  type PublishCheck,
} from '../../../services/api/publication-series-api.service';
import {
  ACTION_KEY_PREFIX,
  ACTOR_KEY_PREFIX,
  AUDIT_ACTIONS,
  AUDIT_ACTOR_KINDS,
  AVAILABILITIES,
  AVAILABILITY_KEY_PREFIX,
  auditActionKey,
  auditActorKey,
  availabilityKey,
  checkDetailText,
  checklistVerdict,
  COMPUTED_ISSUE_STATUSES,
  computedIssueStatusClass,
  computedIssueStatusKey,
  criteriaMissKey,
  ISSUE_STATUSES,
  issueBadgeClass,
  issueStatusKey,
  MEMBER_STATUSES,
  memberStatusKey,
  MISS_CODES,
  ownerTransfer,
  publishedChecklist,
  MISS_KEY_PREFIX,
  SERIES_STATUSES,
  seriesBadgeClass,
  seriesStatusKey,
  TIME_RELATION_KEY_PREFIX,
  UNKNOWN_CODE,
} from './publication-status';

/**
 * Every stored code this section renders has a label in both bundles.
 *
 * The failure this guards is silent: a status added to a vocabulary without a key
 * renders as the key path — `admin.publications.issueStatus.AMENDED` in the
 * middle of a table — and looks like a rendering bug rather than a missing
 * translation. Walking the vocabularies rather than the templates is what makes
 * that catchable before anybody sees it.
 */

type TranslationNode = string | { readonly [key: string]: TranslationNode };

function lookup(bundle: TranslationNode, key: string): TranslationNode | undefined {
  let node: TranslationNode | undefined = bundle;
  for (const segment of key.split('.')) {
    if (node === undefined || typeof node === 'string') {
      return undefined;
    }
    node = node[segment];
  }
  return node;
}

function labelIn(bundle: object, key: string): string | undefined {
  const found = lookup(bundle as TranslationNode, key);
  return typeof found === 'string' ? found : undefined;
}

/** Every code of one vocabulary, plus the fallback every vocabulary falls back to. */
function keysFor(codes: readonly string[], toKey: (code: string) => string): string[] {
  return [...codes, UNKNOWN_CODE].map(toKey);
}

describe('publication status labels', () => {
  const cases: readonly [string, string[]][] = [
    ['series status', keysFor(SERIES_STATUSES, seriesStatusKey)],
    ['issue status', keysFor(ISSUE_STATUSES, issueStatusKey)],
    // The merged vocabulary: three answers about a document and two about its
    // absence, which the dashboard strip renders side by side in one row.
    ['computed issue status', keysFor(COMPUTED_ISSUE_STATUSES, computedIssueStatusKey)],
    ['member frozen status', keysFor(MEMBER_STATUSES, memberStatusKey)],
    [
      'audit actor',
      // USER is deliberately keyless: a person is shown by their own name.
      [...AUDIT_ACTOR_KINDS.filter((kind) => kind !== 'USER'), UNKNOWN_CODE].map(
        (kind) => ACTOR_KEY_PREFIX + kind,
      ),
    ],
    // No UNKNOWN entry: an action outside the vocabulary renders its own code,
    // because "unknown" would delete the one fact the trail line carried.
    ['audit action', AUDIT_ACTIONS.map((action) => ACTION_KEY_PREFIX + action)],
    // Why a candidate was dropped. The omissions panel renders one of these per
    // row, so a code with no label reads as a key path beside a message id.
    ['criteria miss', keysFor(MISS_CODES, criteriaMissKey)],
    // Who else may cite a publication. The editors render the whole vocabulary
    // as radios, so a value with no label is a control nobody can read.
    ['availability', keysFor(AVAILABILITIES, availabilityKey)],
    // Each radio carries a line saying what the answer means. Built from the
    // same closed vocabulary, so a value added without one renders a key path
    // under a label that reads perfectly well.
    [
      'availability hint',
      keysFor(AVAILABILITIES, (code) => `${AVAILABILITY_KEY_PREFIX}hint.${code}`),
    ],
    // The membership regimes, and the line under the select that says what each
    // one selects. No UNKNOWN entry: a regime outside the vocabulary has no key
    // at all and renders as the server's own token, which is deliberate.
    ['time relation', TIME_RELATIONS.map((relation) => TIME_RELATION_KEY_PREFIX + relation)],
    [
      'time relation hint',
      TIME_RELATIONS.map(
        (relation) => `admin.publications.series.timeRelationHint.${relation}` as string,
      ),
    ],
    // Why each member is in its issue. Every member row on the issue screen
    // renders one of these, and the column never disappears — so a reason added
    // on the wire without a label is a key path beside a message id, on the one
    // list somebody reads before releasing a document.
    ['member reason', keysFor(MEMBER_REASONS, (code) => `admin.publications.reason.${code}`)],
  ];

  for (const [vocabulary, keys] of cases) {
    it(`has an English label for every ${vocabulary} code`, () => {
      const missing = keys.filter((key) => labelIn(en, key) === undefined);
      expect(missing, `keys missing in en.json:\n${missing.join('\n')}`).toEqual([]);
    });
  }

  /** A code outside the vocabulary is named as unknown, never rendered raw. */
  it('falls back rather than building a key from an unexpected value', () => {
    expect(seriesStatusKey('RECORDING')).toBe(`admin.publications.seriesStatus.${UNKNOWN_CODE}`);
    expect(issueStatusKey(undefined)).toBe(`admin.publications.issueStatus.${UNKNOWN_CODE}`);
    expect(memberStatusKey('')).toBe(`admin.publications.memberStatus.${UNKNOWN_CODE}`);
    expect(criteriaMissKey('BEFORE_INTERVAL')).toBe(`${MISS_KEY_PREFIX}BEFORE_INTERVAL`);
    expect(criteriaMissKey('SOMETHING_NEW')).toBe(`${MISS_KEY_PREFIX}${UNKNOWN_CODE}`);
    expect(criteriaMissKey(undefined)).toBe(`${MISS_KEY_PREFIX}${UNKNOWN_CODE}`);
    expect(availabilityKey('SELECTED_DOMAINS')).toBe(`${AVAILABILITY_KEY_PREFIX}SELECTED_DOMAINS`);
    expect(availabilityKey('EVERYWHERE')).toBe(`${AVAILABILITY_KEY_PREFIX}${UNKNOWN_CODE}`);
    expect(availabilityKey(undefined)).toBe(`${AVAILABILITY_KEY_PREFIX}${UNKNOWN_CODE}`);
  });

  /**
   * A period with no issue is named for what it is, not for a status it lacks.
   *
   * The two halves of the merged vocabulary live in different key spaces, and a
   * caller choosing between them itself is one that forgets the synthesized half
   * — which is the half a reader scanning a strip is looking for.
   */
  it('names a synthesized period from the row vocabulary and an issue from the status one', () => {
    expect(computedIssueStatusKey('MISSING')).toBe('admin.publications.row.MISSING');
    expect(computedIssueStatusKey('UPCOMING')).toBe('admin.publications.row.UPCOMING');
    expect(computedIssueStatusKey('PUBLISHED')).toBe('admin.publications.issueStatus.PUBLISHED');
    expect(computedIssueStatusKey('WHATEVER')).toBe(
      `admin.publications.issueStatus.${UNKNOWN_CODE}`,
    );
  });

  /**
   * A missing period is tinted as the warning it is, an upcoming one is not.
   *
   * A period still being worked toward is not a problem, and painting the two
   * alike turns every strip into a wall of warnings nobody reads.
   */
  it('tints a period with an existing state class, and an unknown one with none', () => {
    expect(computedIssueStatusClass('MISSING')).toBe('is-gap-MISSING');
    expect(computedIssueStatusClass('UPCOMING')).toBe('is-gap-UPCOMING');
    expect(computedIssueStatusClass('PUBLISHED')).toBe('is-issue-published');
    expect(computedIssueStatusClass('OPEN')).toBe('is-issue-open');
    expect(computedIssueStatusClass('RETIRED')).toBe('is-cancelled');
    expect(computedIssueStatusClass(undefined)).toBe('');
    expect(computedIssueStatusClass('WHATEVER')).toBe('');
  });

  /**
   * The three actor answers stay three.
   *
   * An entry recorded for a person whose name did not survive must not fall
   * through to a machine label: reading "imported" over a change somebody made by
   * hand rewrites what the history says happened.
   */
  /**
   * The three actions the panel could not name are named.
   *
   * `VISIBILITY_CAPPED` is the one that matters: the cap is written inside every
   * routine publish, so it lands on the just-superseded issue of every cadenced
   * series — on the screen an admin opens to ask why a publication left the site.
   */
  it('names the actions a release writes on its neighbours', () => {
    for (const action of ['INTERVAL_CHANGED', 'NAME_CHANGED', 'VISIBILITY_CAPPED']) {
      expect(AUDIT_ACTIONS).toContain(action);
      expect(auditActionKey(action)).toBe(ACTION_KEY_PREFIX + action);
    }
  });

  /**
   * The actions somebody takes by hand are named too.
   *
   * Each of the three is a deliberate act on a document that is already out, and
   * the trail line is the only record of it: an edition retyped, a published file
   * overwritten, a public window opened or closed by a person rather than by the
   * next release. Rendering the bare constant on those three lines leaves the
   * history readable but wordless, in both languages at once.
   */
  it('names the actions somebody takes by hand', () => {
    for (const action of [
      'EDITION_CHANGED',
      'FILE_REPLACED_MANUALLY',
      'VISIBILITY_WINDOW_CHANGED',
    ]) {
      expect(AUDIT_ACTIONS).toContain(action);
      expect(auditActionKey(action)).toBe(ACTION_KEY_PREFIX + action);
    }
  });

  /**
   * An action the vocabulary does not know renders as the code, not as a key.
   *
   * A raw `SOMETHING_NEW` is imperfect English but it still says what happened;
   * `admin.publications.action.SOMETHING_NEW` in the middle of a history panel
   * says nothing and reads as a rendering bug.
   */
  it('leaves an unknown action to be rendered as its own code', () => {
    expect(auditActionKey('SOMETHING_NEW')).toBeUndefined();
    expect(auditActionKey(undefined)).toBeUndefined();
    expect(auditActionKey('')).toBeUndefined();
  });

  it('tells a nameless person apart from the machines', () => {
    expect(auditActorKey({ actorKind: 'IMPORT' })).toBe(`${ACTOR_KEY_PREFIX}IMPORT`);
    expect(auditActorKey({ actorKind: 'SYSTEM' })).toBe(`${ACTOR_KEY_PREFIX}SYSTEM`);
    expect(auditActorKey({ actorKind: 'USER', actorLabel: 'rasmus' })).toBeUndefined();
    expect(auditActorKey({ actorKind: 'USER' })).toBe(`${ACTOR_KEY_PREFIX}${UNKNOWN_CODE}`);
    expect(auditActorKey({})).toBe(`${ACTOR_KEY_PREFIX}${UNKNOWN_CODE}`);
  });
});

/**
 * A transfer's history line says WHERE the publication went.
 *
 * The action name alone says only that it moved, which is the one thing the
 * reader already knows from having opened the trail. The pair travels in the
 * entry's `detail`, which is a sentence on every other action — so it is read
 * defensively rather than trusted to be the shape this one line wants.
 */
describe('ownerTransfer', () => {
  it('reads the two domains out of the detail object', () => {
    expect(
      ownerTransfer({
        action: 'OWNER_TRANSFERRED',
        detail: { from: 'niord-nm', to: 'niord-annex' },
      }),
    ).toEqual({ from: 'niord-nm', to: 'niord-annex' });
  });

  /** An older server writes the object as JSON text; both shapes read the same. */
  it('reads the same pair out of a JSON string', () => {
    expect(
      ownerTransfer({
        action: 'OWNER_TRANSFERRED',
        detail: '{"from":"niord-nm","to":"niord-annex"}',
      }),
    ).toEqual({ from: 'niord-nm', to: 'niord-annex' });
  });

  /** Every other action's detail is prose, and prose is not a pair. */
  it('answers nothing for another action, however its detail is shaped', () => {
    expect(ownerTransfer({ action: 'PUBLISHED', detail: { from: 'a', to: 'b' } })).toBeUndefined();
    expect(
      ownerTransfer({ action: 'AMENDED', detail: 'corrected the chart list' }),
    ).toBeUndefined();
  });

  /**
   * Half an answer is worse than none: "moved from Annexes to" describes a move
   * to nowhere, where the bare action at least says what happened.
   */
  it('answers nothing unless both ends are there', () => {
    expect(
      ownerTransfer({ action: 'OWNER_TRANSFERRED', detail: { from: 'niord-nm' } }),
    ).toBeUndefined();
    expect(
      ownerTransfer({ action: 'OWNER_TRANSFERRED', detail: { to: 'niord-nm' } }),
    ).toBeUndefined();
    expect(ownerTransfer({ action: 'OWNER_TRANSFERRED' })).toBeUndefined();
    expect(ownerTransfer({ action: 'OWNER_TRANSFERRED', detail: 'not json' })).toBeUndefined();
    expect(ownerTransfer({ action: 'OWNER_TRANSFERRED', detail: '[1,2]' })).toBeUndefined();
  });
});

/**
 * The checklist a release preserved, read off the line that recorded it.
 *
 * The record rather than the rail: the rail re-asks its questions of the issue as
 * it stands now, and the answers that were true in the release — including which
 * failing warnings somebody ticked to get past — exist nowhere else.
 */
describe('publishedChecklist', () => {
  const ROWS = [
    {
      code: 'CANCELLED_MEMBERS_ALIVE_AT_CUTOFF',
      severity: 'OK',
      passed: true,
      acknowledged: false,
    },
    { code: 'MEMBER_LIMIT', severity: 'WARN', passed: false, acknowledged: true },
  ];

  it('reads the rows out of the detail object', () => {
    expect(publishedChecklist({ detail: { checklist: ROWS } })).toEqual(ROWS);
  });

  /** An older server writes the object as JSON text; both shapes read the same. */
  it('reads the same rows out of a JSON string', () => {
    expect(publishedChecklist({ detail: JSON.stringify({ checklist: ROWS }) })).toEqual(ROWS);
  });

  /** A line written before the record existed, and prose, both carry nothing. */
  it('answers an empty list where there is no checklist to read', () => {
    expect(publishedChecklist({})).toEqual([]);
    expect(publishedChecklist({ detail: 'corrected the chart list' })).toEqual([]);
    expect(publishedChecklist({ detail: { unacknowledgedWarnings: [] } })).toEqual([]);
    expect(publishedChecklist({ detail: { checklist: 'all of them' } })).toEqual([]);
  });

  /**
   * A row that cannot be identified is dropped; the rest of the list still reads.
   *
   * One unreadable row is not a reason to withhold the record — the reader is
   * checking what a release was taken against, and most of that answer is intact.
   */
  it('drops a row with no code and keeps the ones around it', () => {
    expect(
      publishedChecklist({
        detail: { checklist: [ROWS[0], { severity: 'WARN', passed: false }, 'MEMBER_LIMIT'] },
      }),
    ).toEqual([ROWS[0]]);
  });

  /**
   * An unreadable severity is taken as BLOCK — the stricter reading.
   *
   * It withholds the acknowledgement clause rather than reporting that somebody
   * waved a failing row through when nothing says they were even asked.
   */
  it('reads a missing severity as a blocker, and a missing flag as false', () => {
    expect(publishedChecklist({ detail: { checklist: [{ code: 'MEMBER_LIMIT' }] } })).toEqual([
      { code: 'MEMBER_LIMIT', severity: 'BLOCK', passed: false, acknowledged: false },
    ]);
  });
});

/**
 * The two badge vocabularies, and the one state that wears no badge at all.
 *
 * An issue's lifecycle and a series' state read alike on a screen and mean
 * different things, so they are two functions rather than one with a flag — and
 * the accent never appears in either, because it is what "selected" looks like
 * everywhere else in the application.
 */
describe('badge classes', () => {
  it('tints an issue for its own lifecycle', () => {
    expect(issueBadgeClass('OPEN')).toBe('is-issue-open');
    expect(issueBadgeClass('PUBLISHED')).toBe('is-issue-published');
    expect(issueBadgeClass('RETIRED')).toBe('is-cancelled');
  });

  it('leaves an unrecognised issue status untinted rather than guessing', () => {
    expect(issueBadgeClass(undefined)).toBe('');
    expect(issueBadgeClass('AMENDED')).toBe('');
    expect(issueBadgeClass('')).toBe('');
  });

  /**
   * An active series is the ordinary case and carries no badge.
   *
   * Badging every live row with "active" spends the reader's attention on the
   * one thing that needs none, and leaves the two states that change what they
   * may do competing with it.
   */
  it('badges a series only where the state changes what can be done', () => {
    expect(seriesBadgeClass('DRAFT')).toBe('is-issue-open');
    expect(seriesBadgeClass('RETIRED')).toBe('is-cancelled');
    expect(seriesBadgeClass('ACTIVE')).toBe('');
    expect(seriesBadgeClass(undefined)).toBe('');
    expect(seriesBadgeClass('RECORDING')).toBe('');
  });

  /** The merged vocabulary keeps deferring to the issue one, so they cannot drift. */
  it('tints an issue the same way whether it is read as a period or as a status', () => {
    for (const status of ISSUE_STATUSES) {
      expect(computedIssueStatusClass(status)).toBe(issueBadgeClass(status));
    }
  });
});

/**
 * The sentence above the release rail.
 *
 * It has to count exactly the rows the reader can see: a line saying "ready"
 * over a list with a red row in it, or "3 warnings" over a list showing two, is
 * read as a broken screen rather than as a miscount.
 */
describe('checklistVerdict', () => {
  function check(over: Partial<PublishCheck> = {}): PublishCheck {
    return {
      code: 'MEMBERS_RESOLVED',
      severity: 'OK',
      passed: true,
      acknowledgeable: false,
      ...over,
    };
  }

  it('is ready when everything applicable passed', () => {
    expect(
      checklistVerdict([check(), check({ code: 'CANCELLED_MEMBERS_ALIVE_AT_CUTOFF' })]),
    ).toEqual({
      state: 'ready',
      blocking: 0,
      warnings: 0,
      passed: 2,
      applicable: 2,
    });
  });

  /** A failing WARN row can be acknowledged, so the issue is still publishable. */
  it('counts a failing WARN row as a warning', () => {
    expect(
      checklistVerdict([
        check(),
        check({
          code: 'CANCELLED_MEMBERS_ALIVE_AT_CUTOFF',
          severity: 'WARN',
          passed: false,
          acknowledgeable: true,
        }),
      ]),
    ).toEqual({ state: 'warnings', blocking: 0, warnings: 1, passed: 1, applicable: 2 });
  });

  /** One BLOCK row outranks any number of warnings — the state is what stops the release. */
  it('is blocked whenever a BLOCK row fails, warnings or not', () => {
    expect(
      checklistVerdict([
        check({
          code: 'CANCELLED_MEMBERS_ALIVE_AT_CUTOFF',
          severity: 'WARN',
          passed: false,
          acknowledgeable: true,
        }),
        check({ code: 'REPORT_CONFIGURED', severity: 'BLOCK', passed: false }),
        check(),
      ]),
    ).toEqual({ state: 'blocked', blocking: 1, warnings: 1, passed: 1, applicable: 3 });
  });

  /**
   * A question that was never asked has no answer to show.
   *
   * The row is dropped from every count, so the passed tally under the
   * disclosure matches the rows behind it instead of promising two more.
   */
  it('ignores the rows the server marked as not applicable', () => {
    expect(
      checklistVerdict([
        check(),
        check({ code: 'REPORT_CONFIGURED', passed: false, severity: 'BLOCK', applicable: false }),
        check({ code: 'FILE_PRESENT_PER_LANGUAGE', applicable: false }),
      ]),
    ).toEqual({ state: 'ready', blocking: 0, warnings: 0, passed: 1, applicable: 1 });
  });

  /** Absent is not `false`: a server that did not say leaves the row counted. */
  it('counts a row that says nothing about being applicable', () => {
    expect(checklistVerdict([check({ applicable: true }), check()]).applicable).toBe(2);
  });

  /** No rows at all is not a failure — an empty rail has nothing standing in the way. */
  it('reads an empty checklist as ready', () => {
    expect(checklistVerdict([])).toEqual({
      state: 'ready',
      blocking: 0,
      warnings: 0,
      passed: 0,
      applicable: 0,
    });
  });

  /**
   * A failing row of any other severity still counts.
   *
   * There is no third bucket: a failure that lands nowhere vanishes from the
   * sentence while still sitting in the list under it.
   */
  it('counts a failing row that is neither BLOCK nor WARN as a warning', () => {
    expect(checklistVerdict([check({ passed: false })])).toMatchObject({
      state: 'warnings',
      warnings: 1,
      blocking: 0,
    });
  });
});

/**
 * The small line under a rail row: what the check was measured to be.
 *
 * The server states the answer as a code plus its values rather than only as an
 * English sentence, because that line sat in English under a Danish heading. The
 * values reach the sentence unrendered — an instant as epoch milliseconds — so
 * the moment shows on the screen's own clock instead of in the zone and layout
 * the server happened to format it with.
 */
describe('checkDetailText', () => {
  const at = (value: number) => `formatted:${value}`;

  it('names the sentence for a code this build has words for', () => {
    expect(
      checkDetailText(
        {
          detailCode: 'MEMBER_LIMIT.of',
          detailParams: { count: 214, limit: 1000 },
          detail: '214 of 1000',
        },
        at,
      ),
    ).toEqual({
      key: 'admin.publications.checkDetail.MEMBER_LIMIT.of',
      params: { count: 214, limit: 1000 },
      text: '214 of 1000',
    });
  });

  /** Epoch milliseconds are a number on the wire and a moment on the screen. */
  it('formats an instant through the clock the caller passed', () => {
    const detail = checkDetailText(
      {
        detailCode: 'CUTOFF_NOT_FUTURE.at',
        detailParams: { at: 1757325600000, zone: 'Europe/Copenhagen' },
      },
      at,
    );

    expect(detail?.params).toEqual({ at: 'formatted:1757325600000', zone: 'Europe/Copenhagen' });
  });

  /**
   * The two ends of the uncovered stretches are one list of periods, not two.
   *
   * They arrive as parallel lists that pair by position alone: printed as two,
   * a row naming three gaps reads "from A, B, C to D, E, F" and leaves the
   * reader pairing dates. Both ends go through the screen's own clock, so a
   * renaming on the wire shows as a missing period rather than as epochs in the
   * middle of the sentence.
   */
  it('zips the uncovered stretches into one list of periods on the screen’s clock', () => {
    const detail = checkDetailText(
      {
        detailCode: 'SOURCE_ISSUES_COMPLETE.incomplete',
        detailParams: {
          count: 50,
          openCount: 1,
          missingCount: 2,
          openWeeks: ['51/2023'],
          missingFrom: [1000, 3000],
          missingTo: [2000, 4000],
          zone: 'Europe/Copenhagen',
        },
      },
      at,
    );

    expect(detail?.key).toBe('admin.publications.checkDetail.SOURCE_ISSUES_COMPLETE.incomplete');
    expect(detail?.params).toEqual({
      count: 50,
      openCount: 1,
      missingCount: 2,
      openWeeks: '51/2023',
      missing: 'formatted:1000 – formatted:2000, formatted:3000 – formatted:4000',
      zone: 'Europe/Copenhagen',
    });
  });

  it('names only the side the coverage row failed on', () => {
    const onlyOpen = checkDetailText(
      {
        detailCode: 'SOURCE_ISSUES_COMPLETE.incomplete',
        detailParams: {
          count: 86,
          openCount: 1,
          missingCount: 0,
          openWeeks: ['36/2026'],
          missingFrom: [],
          missingTo: [],
          zone: 'Europe/Copenhagen',
        },
      },
      at,
    );
    expect(onlyOpen?.key).toBe(
      'admin.publications.checkDetail.SOURCE_ISSUES_COMPLETE.incomplete.noGaps',
    );

    const onlyGaps = checkDetailText(
      {
        detailCode: 'SOURCE_ISSUES_COMPLETE.incomplete',
        detailParams: {
          count: 50,
          openCount: 0,
          missingCount: 1,
          openWeeks: [],
          missingFrom: [1000],
          missingTo: [2000],
          zone: 'Europe/Copenhagen',
        },
      },
      at,
    );
    expect(onlyGaps?.key).toBe(
      'admin.publications.checkDetail.SOURCE_ISSUES_COMPLETE.incomplete.noOpen',
    );
    expect(onlyGaps?.params['missing']).toBe('formatted:1000 – formatted:2000');
  });

  /** A list reaches the template as words, never as `[object Object]`. */
  it('joins a list parameter', () => {
    const detail = checkDetailText(
      { detailCode: 'FILE_PRESENT_PER_LANGUAGE.bytes', detailParams: { langs: ['en', 'da'] } },
      at,
    );

    expect(detail?.params['langs']).toBe('en, da');
  });

  /** "1 message(s)" is the shape of a sentence nobody wrote for the number it got. */
  it('takes the singular wording for a count of one', () => {
    expect(
      checkDetailText(
        { detailCode: 'CANCELLED_MEMBERS_ALIVE_AT_CUTOFF.count', detailParams: { count: 1 } },
        at,
      )?.key,
    ).toBe('admin.publications.checkDetail.CANCELLED_MEMBERS_ALIVE_AT_CUTOFF.count.one');
  });

  /** And leaves the plural alone where no singular was written for the code. */
  it('keeps the one wording a code has when its count is one', () => {
    expect(
      checkDetailText(
        { detailCode: 'MEMBER_LIMIT.of', detailParams: { count: 1, limit: 1000 } },
        at,
      )?.key,
    ).toBe('admin.publications.checkDetail.MEMBER_LIMIT.of');
  });

  /**
   * A code this build has no words for reads as the server's own English.
   *
   * A sentence nobody has translated yet is still worth reading; an unresolved
   * key path is not.
   */
  it('falls back to the text the server sent for an unworded code', () => {
    expect(
      checkDetailText({ detailCode: 'SOMETHING.new', detail: 'measured something' }, at),
    ).toEqual({ key: undefined, params: {}, text: 'measured something' });
  });

  /** A server older than the field sends the sentence alone, and it still shows. */
  it('shows a detail that arrives without a code', () => {
    expect(checkDetailText({ detail: 'no document for en' }, at)?.text).toBe('no document for en');
  });

  /** Nothing to say is nothing rendered, not an empty line under the row. */
  it('says nothing for a row with neither a code nor a sentence', () => {
    expect(checkDetailText({}, at)).toBeUndefined();
  });

  /**
   * The boolean the check turns on is the answer, not a value to drop.
   *
   * "Selection regime: PUBLISHED_IN_INTERVAL" says nothing about why the row
   * failed. Whether a content period is SET is the whole of what the check
   * measured, so the wording is picked by it rather than interpolating a bare
   * "true" into a sentence.
   */
  it('picks the wording the boolean makes true', () => {
    expect(
      checkDetailText(
        {
          detailCode: 'INTERVAL_PRESENT.under',
          detailParams: { present: true, timeRelation: 'PUBLISHED_IN_INTERVAL' },
        },
        at,
      )?.key,
    ).toBe('admin.publications.checkDetail.INTERVAL_PRESENT.under.present');

    expect(
      checkDetailText(
        {
          detailCode: 'INTERVAL_PRESENT.under',
          detailParams: { present: false, timeRelation: 'IN_FORCE_AT_CUTOFF' },
        },
        at,
      )?.key,
    ).toBe('admin.publications.checkDetail.INTERVAL_PRESENT.under.absent');
  });

  /** A server that stops sending the boolean still gets the base sentence. */
  it('keeps the unsuffixed wording where no variant applies', () => {
    expect(
      checkDetailText(
        { detailCode: 'INTERVAL_PRESENT.under', detailParams: { timeRelation: 'X' } },
        at,
      )?.key,
    ).toBe('admin.publications.checkDetail.INTERVAL_PRESENT.under');
  });

  /**
   * A stored code is English in every language.
   *
   * `OPEN` and `PUBLISHED_IN_INTERVAL` are the database's words. Interpolated
   * raw they are the one untranslated token in an otherwise translated sentence,
   * and the label already exists elsewhere on the same screen.
   */
  it('names a status and a regime the way the rest of the screen names them', () => {
    const detail = checkDetailText(
      {
        detailCode: 'ISSUE_OPEN.status',
        detailParams: { status: 'PUBLISHED', seriesStatus: 'ACTIVE' },
      },
      at,
      (key) => `said:${key}`,
    );

    expect(detail?.params['status']).toBe('said:admin.publications.issueStatus.PUBLISHED');
    expect(detail?.params['seriesStatus']).toBe('said:admin.publications.seriesStatus.ACTIVE');

    expect(
      checkDetailText(
        {
          detailCode: 'INTERVAL_PRESENT.under',
          detailParams: { present: true, timeRelation: 'IN_FORCE_AT_CUTOFF' },
        },
        at,
        (key) => `said:${key}`,
      )?.params['timeRelation'],
    ).toBe('said:admin.publications.series.timeRelation.IN_FORCE_AT_CUTOFF');
  });

  /**
   * A regime nobody has worded reads as the server's own token.
   *
   * The editors that OFFER the two regimes build their labels from their own
   * option lists, so there is no wording for a third value — and inventing one
   * would put "Unknown" where the operator needs something to quote.
   */
  it('leaves a code outside the vocabulary as it came', () => {
    expect(
      checkDetailText(
        { detailCode: 'INTERVAL_PRESENT.under', detailParams: { timeRelation: 'NEW_REGIME' } },
        at,
        (key) => `said:${key}`,
      )?.params['timeRelation'],
    ).toBe('NEW_REGIME');
  });

  /** Without a translator the codes stand, which is what the English detail does. */
  it('leaves the codes alone for a caller with nothing to translate with', () => {
    expect(
      checkDetailText({ detailCode: 'ISSUE_OPEN.status', detailParams: { status: 'OPEN' } }, at)
        ?.params['status'],
    ).toBe('OPEN');
  });
});
