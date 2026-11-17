import { describe, expect, it } from 'vitest';

import en from '../../../../../../../../../languages/en/i18n/admin/en.json';
import {
  ISSUE_NOT_OPEN,
  decodePublicationError,
  issueCitationsOf,
  issueDraftWarningKey,
  publicationErrorCode,
  publicationErrorKey,
  publicationFieldErrors,
  seriesFieldLabel,
  seriesNotFound,
  seriesRuleText,
  seriesRuleTexts,
  unacknowledgedWarningsOf,
  warningKey,
} from './publication-errors';

/** A refusal as the mapper actually sends it: a status, and a coded JSON body. */
function refusal(body: unknown, status = 409) {
  return { status, error: body };
}

/**
 * A refusal the admin can act on, said out loud.
 *
 * The envelope is not one shape: a refusal arrives as a coded object from some
 * endpoints and as a message beginning with the same code from others, and a
 * caller should not have to know which. Anything unrecognised keeps the generic
 * message rather than being spelled out raw — a code is the server's word for a
 * rule, not a sentence.
 */
describe('publicationErrorKey', () => {
  const FALLBACK = 'admin.publications.series.toast.saveError';

  it('names the reserved-parameter refusal from a coded envelope', () => {
    expect(publicationErrorKey({ error: { code: 'PRINT_SETTING_NOT_ALLOWED' } }, FALLBACK)).toBe(
      'admin.publications.reportParams.serverRejected',
    );
  });

  it('finds the same code inside a message', () => {
    expect(
      publicationErrorKey(
        { error: { message: 'PRINT_SETTING_NOT_ALLOWED: week is injected' } },
        FALLBACK,
      ),
    ).toBe('admin.publications.reportParams.serverRejected');
  });

  /**
   * The curation refusals, each of which names a mistake with an obvious fix.
   *
   * These land inside the dialog that caused them, so a generic "that did not go
   * through" would leave the admin holding a typed reason and no idea what to
   * change.
   */
  it('names every curation refusal', () => {
    const cases: Readonly<Record<string, string>> = {
      OVERRIDE_REASON_REQUIRED: 'admin.publications.curation.reasonRequired',
      OVERRIDE_MESSAGE_NOT_FOUND: 'admin.publications.curation.messageNotFound',
      OVERRIDE_MESSAGE_NOT_PUBLIC: 'admin.publications.curation.messageNotPublic',
      OVERRIDE_ALREADY_A_MEMBER: 'admin.publications.curation.alreadyMember',
      OVERRIDE_NOT_FOUND: 'admin.publications.curation.overrideNotFound',
      NO_MESSAGES: 'admin.publications.curation.noMessages',
      TOO_MANY_MESSAGES: 'admin.publications.curation.tooMany',
    };
    for (const [code, key] of Object.entries(cases)) {
      expect(publicationErrorKey({ error: { code } }, FALLBACK)).toBe(key);
    }
  });

  it('names every amend and new-edition refusal', () => {
    const cases: Readonly<Record<string, string>> = {
      ISSUE_NOT_PUBLISHED: 'admin.publications.amend.notPublished',
      ISSUE_IMPORTED: 'admin.publications.amend.imported',
      ARCHIVE_FAILED: 'admin.publications.amend.archiveFailed',
      RENDER_FAILED: 'admin.publications.amend.renderFailed',
      PREDECESSOR_NOT_PUBLISHED: 'admin.publications.newEdition.notPublished',
      ISSUE_INTERVAL_OVERLAP: 'admin.publications.newEdition.overlap',
      SERIES_IS_ONE_OFF: 'admin.publications.newEdition.oneOff',
    };
    for (const [code, key] of Object.entries(cases)) {
      expect(publicationErrorKey({ error: { code } }, FALLBACK)).toBe(key);
    }
  });

  /**
   * A second release asked for while the first is still running.
   *
   * Worth its own sentence because nothing failed and nothing is to be fixed:
   * the document is being written right now — in another tab, or by another
   * admin — and the answer is to wait. The generic "could not be published"
   * reads as a failure and invites the press that produced the refusal.
   */
  it('names the refusal that says the issue is already being released', () => {
    expect(publicationErrorKey({ status: 409, error: { code: 'ISSUE_BUSY' } }, FALLBACK)).toBe(
      'admin.publications.error.issueBusy',
    );
  });

  /**
   * An upload to a released issue under a name whose file the issue still keeps.
   *
   * Worth its own sentence because the generic one invites the retry that cannot
   * work: the released file stays for the issue's lifetime, so only another name
   * succeeds. The key must also be one the bundle carries, or the sentence
   * renders as the key.
   */
  it('names the upload refused over a file name a released document keeps', () => {
    const key = publicationErrorKey(
      refusal({ code: 'FILE_NAME_TAKEN', message: 'a released document still lives there' }, 400),
      FALLBACK,
    );
    expect(key).toBe('admin.publications.error.fileNameTaken');
    const errors: Readonly<Record<string, unknown>> = en.admin.publications.error;
    expect(typeof errors['fileNameTaken']).toBe('string');
  });

  /**
   * L-047. A refused category save used to say only "The category could not be
   * saved". The two codes a save can meet have sentences, and the bundle carries
   * them, or they would render as the key.
   */
  it('names the two refusals a category save can meet', () => {
    expect(
      publicationErrorKey(
        refusal({ code: 'CATEGORY_ID_TAKEN', message: "category 'x' exists" }),
        FALLBACK,
      ),
    ).toBe('admin.publications.error.categoryIdTaken');
    expect(
      publicationErrorKey(refusal({ code: 'CATEGORY_INVALID', message: 'nope' }, 400), FALLBACK),
    ).toBe('admin.publications.error.categoryInvalid');
    // The endpoints that still throw plainly put the code at the front of the text.
    expect(publicationErrorKey({ error: 'CATEGORY_INVALID: priority is a number' }, FALLBACK)).toBe(
      'admin.publications.error.categoryInvalid',
    );
    const errors: Readonly<Record<string, unknown>> = en.admin.publications.error;
    expect(typeof errors['categoryIdTaken']).toBe('string');
    expect(typeof errors['categoryInvalid']).toBe('string');
  });

  /**
   * L-046. ISSUE_NOT_OPEN is thrown by edits, curation, uploads, a file clear
   * and a release as well as by the preview, so the shared sentence is a
   * neutral one: it used to say "Only an open issue can be previewed" for all
   * of them. The preview keeps its own sentence where it maps its own refusal.
   */
  it('names an issue that is no longer open without calling it a preview', () => {
    expect(publicationErrorKey(refusal({ code: 'ISSUE_NOT_OPEN' }), FALLBACK)).toBe(
      'admin.publications.error.issueNotOpen',
    );
    expect(publicationErrorCode(refusal({ code: ISSUE_NOT_OPEN }))).toBe('ISSUE_NOT_OPEN');
    const errors: Readonly<Record<string, unknown>> = en.admin.publications.error;
    expect(errors['issueNotOpen']).toBe(
      'This issue is no longer open, so it cannot be changed. Reload it.',
    );
  });

  /**
   * Every code the publication API declares has something to say for itself.
   *
   * The list below is the backend catalogue, transcribed. A code it can return
   * and this section cannot name reaches the reader as an identifier, which is
   * the failure the whole mapping exists to prevent — and it has already
   * happened twice, to the overlap refusal and to the already-published one.
   *
   * Of the CATEGORY_* codes, the two a category save can meet are here (L-047).
   * CATEGORY_IN_USE is absent on purpose: the categories tab words it itself,
   * with the counts it reads off the refusal.
   */
  it('has a sentence for every code the publication API declares', () => {
    const catalogue = [
      'CRITERIA_INVALID',
      'UNKNOWN_TOKEN',
      'EMPTY_OPERAND',
      'UNRESOLVABLE_OPERAND',
      'OVERRIDE_REASON_REQUIRED',
      'OVERRIDE_ALREADY_A_MEMBER',
      'OVERRIDE_MESSAGE_NOT_FOUND',
      'OVERRIDE_MESSAGE_NOT_PUBLIC',
      'FILE_NAME_NOT_DISTINCT',
      // A link that is neither an http(s) address nor a repository path.
      'LINK_INVALID',
      'NO_SUCH_LANGUAGE',
      'RETRO_CREATE_NOT_APPLICABLE',
      'SERIES_INVALID',
      'SERIES_ID_IMMUTABLE',
      'MESSAGE_PUBLICATION_IMMUTABLE',
      'PUBLICATION_UNRESOLVABLE',
      'CITATION_FORMAT_MISSING',
      'INTERVAL_INVERTED',
      'NAME_BLANK',
      'NAME_INVALID',
      'CRITERIA_NOT_APPLICABLE',
      'NO_FILE',
      'TOO_MANY_FILES',
      'NO_FILE_NAME',
      'BAD_FILE_NAME',
      'SERIES_NOT_FOUND',
      'ISSUE_NOT_FOUND',
      'ISSUE_ALREADY_PUBLISHED',
      'ISSUE_NOT_PUBLISHED',
      'ISSUE_NOT_RETIRED',
      'ISSUE_NOT_OPEN',
      'SERIES_HAS_ISSUES',
      'SERIES_IS_COMPILED',
      'SERIES_IS_ONE_OFF',
      'SERIES_NOT_ONE_OFF',
      'PREDECESSOR_NOT_PUBLISHED',
      'WARNING_NOT_ACKNOWLEDGED',
      'CUTOFF_IN_FUTURE',
      'NO_PREVIEW',
      'REPORT_NOT_CONFIGURED',
      'SERIES_ID_TAKEN',
      'ISSUE_INTERVAL_OVERLAP',
      'REASON_REQUIRED',
      'INVALID_STATUS_TRANSITION',
      'INVALID_STATUS',
      'NOT_YET_SUPPORTED',
      'PRINT_SETTING_NOT_ALLOWED',
      'NO_MESSAGES',
      'TOO_MANY_MESSAGES',
      'OVERRIDE_NOT_FOUND',
      'SERIES_NOT_ACTIVE',
      'ISSUE_IMPORTED',
      'ARCHIVE_FAILED',
      'ARCHIVE_ENTRY_NOT_FOUND',
      'FILE_WRITE_FAILED',
      'RENDER_FAILED',
      'TOO_MANY_IDS',
      'NO_SERIES_IDS',
      'TOO_MANY_SERIES_IDS',
      'INVALID_FILTER_VALUE',
      'INTERVAL_INVALID',
      'MISSING_FILE_FOR_LANGUAGE',
      'REFERENCE_FORMAT_MISSING_LANGUAGE',
      'CUTOFF_BEFORE_PREVIOUS',
      'CUTOFF_AFTER_SUCCESSOR',
      'MEMBER_LIMIT_EXCEEDED',
      'NOT_IN_DOMAIN',
      'STALE_VERSION',
      'PUBLIC_WINDOW_CLOSED',
      'PUBLIC_WINDOW_INVALID',
      'NOT_ONE_OFF',
      'EDITION_INVALID',
      // What tailoring one edition can be refused over.
      'NUMBERING_LABEL_INVALID',
      'FILE_NAME_INVALID',
      'FILE_NAME_IMMUTABLE',
      // An upload to a released issue under a name whose file the issue keeps.
      'FILE_NAME_TAKEN',
      'REPORT_NOT_FOUND',
      // Judged against what a request adds or changes, so an issue that already
      // stores one of the four injected names can still be edited elsewhere.
      'REPORT_PARAM_RESERVED',
      // A category save: a duplicate id on create, and the server's own checks
      // of the id, the name and the Order.
      'CATEGORY_INVALID',
      'CATEGORY_ID_TAKEN',
    ];
    const unnamed = catalogue.filter(
      (code) => publicationErrorKey({ error: { code } }, FALLBACK) === FALLBACK,
    );
    expect(unnamed).toEqual([]);
  });

  /**
   * A write aimed at another domain is NOT the same 403 as a missing role.
   *
   * The first is lifted by switching domain and the second cannot be lifted by
   * the caller at all, so they must not share a sentence: offering the wrong
   * remedy sends somebody to ask for a permission they already hold. The server
   * carries the code, and a bare 403 carries none — which is what keeps the two
   * branches apart.
   */
  it('names a write aimed at another domain, apart from a plain forbidden', () => {
    expect(publicationErrorKey({ status: 403, error: { code: 'NOT_IN_DOMAIN' } }, FALLBACK)).toBe(
      'admin.publications.error.notInDomain',
    );
    // The code the screens synthesize for a 403 with no envelope at all.
    expect(publicationErrorKey({ error: { code: 'FORBIDDEN' } }, FALLBACK)).toBe(FALLBACK);
  });

  it('falls back for anything it has nothing better to say about', () => {
    expect(publicationErrorKey({ error: { code: 'NOT_A_REAL_CODE' } }, FALLBACK)).toBe(FALLBACK);
    expect(publicationErrorKey({ error: 'NOT_A_REAL_CODE' }, FALLBACK)).toBe(FALLBACK);
    expect(publicationErrorKey(undefined, FALLBACK)).toBe(FALLBACK);
    expect(publicationErrorKey({}, FALLBACK)).toBe(FALLBACK);
    expect(publicationErrorKey({ error: null }, FALLBACK)).toBe(FALLBACK);
  });
});

/**
 * The warning vocabulary, in the ONE spelling that goes on the wire.
 *
 * The rail names its rows after the QUESTION it asked; the release gate compares
 * the resolver's own codes. The correspondence between them is the SERVER's to
 * state — it travels on the row as `acknowledgeCode` — and this map is keyed on
 * the gate's side of it, so a caller resolves the code first and asks here
 * second. A table of the correspondence kept here instead was one more thing to
 * keep in step with a vocabulary that is not ours.
 */
describe('the warning vocabulary', () => {
  it('has a sentence for every resolution warning the backend can raise', () => {
    for (const code of [
      'CANCELLED_BUT_DATE_ALIVE',
      'TYPE_DRIFT',
      'NULL_PUBLISH_FROM_DROPPED',
      'STALE_OVERRIDE',
      'OVERLAPPING_ISSUE',
      'LIMIT_EXCEEDED',
    ]) {
      expect(warningKey(code)).toBe(`admin.publications.warning.${code}`);
    }
  });

  /**
   * A rail code is NOT looked up here, and that is the point.
   *
   * The row carries the gate's code beside its own; a caller that resolves it
   * first asks this map a question it can answer, and one that does not gets
   * `undefined` and renders the raw code — which is honest, where a hand-kept
   * translation table would have to be right about a vocabulary the server owns.
   */
  it('says nothing about a rail row that has not been resolved to the gate’s code', () => {
    expect(warningKey('CANCELLED_MEMBERS_ALIVE_AT_CUTOFF')).toBeUndefined();
  });

  /** A warning invented after this build renders as itself, never as a key. */
  it('has nothing to say about a code it has never seen', () => {
    expect(warningKey('SOMETHING_NEW')).toBeUndefined();
  });
});

/**
 * A draft warning is not a refusal.
 *
 * The draft still stands and the create is still offered, so each code has to
 * say what an admin would want to know rather than what went wrong — and the
 * server's own English sentence, which travels beside the code, is never shown.
 */
describe('issueDraftWarningKey', () => {
  it('names every warning the draft endpoint raises', () => {
    for (const code of [
      'INTERVAL_NOT_CHAINED',
      'SERIES_NOT_ACTIVE',
      'IN_FORCE_HAS_NO_LOWER_BOUND',
      'NO_NOMINAL_CLOSE',
      'NO_ANCHOR',
      'NO_MEMBERSHIP_CRITERIA',
      'CRITERIA_UNRESOLVABLE',
      'ONE_OFF_ALREADY_HAS_ITS_ISSUE',
    ]) {
      expect(issueDraftWarningKey(code)).toBe(`admin.publications.issueDraft.warning.${code}`);
    }
  });

  /** A warning invented after this build renders as itself, never as a key. */
  it('has nothing to say about a code it has never seen', () => {
    expect(issueDraftWarningKey('SOMETHING_NEW')).toBeUndefined();
  });
});

/**
 * The refusals a caller answers with an ACTION rather than with a sentence.
 *
 * A stale revision is answered with a re-read of the row, a domain mismatch with
 * the server's own words beneath the mapped ones — neither of which a shared
 * key map can choose.
 */
describe('publicationErrorCode', () => {
  it('returns the refusal code the section recognises', () => {
    expect(publicationErrorCode({ error: { code: 'STALE_VERSION' } })).toBe('STALE_VERSION');
  });

  it('finds the code in a message-shaped envelope too', () => {
    expect(publicationErrorCode({ error: { message: 'STALE_VERSION: 4 is not 5' } })).toBe(
      'STALE_VERSION',
    );
  });

  it('has nothing to say about a refusal it does not recognise', () => {
    expect(publicationErrorCode({ error: { code: 'SOMETHING_NEW' } })).toBeUndefined();
  });
});

/** A publication that is not there, told apart from a read that failed (L-174). */
describe('seriesNotFound', () => {
  it('is true for a 404 with SERIES_NOT_FOUND, coded or in the message', () => {
    expect(seriesNotFound(refusal({ code: 'SERIES_NOT_FOUND', message: 'no series' }, 404))).toBe(
      true,
    );
    expect(seriesNotFound(refusal('SERIES_NOT_FOUND: no series with id x', 404))).toBe(true);
  });

  it('is false for the same code on another status, another code, or a bare failure', () => {
    expect(seriesNotFound(refusal({ code: 'SERIES_NOT_FOUND' }, 409))).toBe(false);
    expect(seriesNotFound(refusal({ code: 'ISSUE_NOT_FOUND' }, 404))).toBe(false);
    expect(seriesNotFound(refusal(null, 404))).toBe(false);
    expect(seriesNotFound(refusal(null, 500))).toBe(false);
    expect(seriesNotFound(undefined)).toBe(false);
  });
});

/**
 * One reading of a failed request, for every caller.
 *
 * Two shapes reach this function and a caller should not have to know which: the
 * mapped endpoints answer with a coded JSON envelope, and the ones that still
 * throw plainly answer with a message that begins with the same code. Everything
 * else — a 502 in front of the resource, a dropped connection, an endpoint
 * outside the publication package — is a bare failure, and it is deliberately
 * NOT an envelope with empty fields: "the server refused, and said why" and
 * "something went wrong on the way" are different things to tell somebody.
 */
describe('decodePublicationError', () => {
  it('reads the whole envelope off a coded refusal', () => {
    const decoded = decodePublicationError(
      refusal({
        code: 'ISSUE_INTERVAL_OVERLAP',
        message: 'Issue interval overlaps issue efs-uge-27-2026',
        details: { conflictingPublicId: 'a8e6' },
        fieldErrors: [{ rule: 'S-1', field: 'descs.da.name', message: 'a name is required' }],
      }),
    );

    expect(decoded).toEqual({
      kind: 'publication',
      code: 'ISSUE_INTERVAL_OVERLAP',
      message: 'Issue interval overlaps issue efs-uge-27-2026',
      status: 409,
      details: { conflictingPublicId: 'a8e6' },
      fieldErrors: [{ rule: 'S-1', field: 'descs.da.name', message: 'a name is required' }],
      unacknowledgedWarnings: [],
    });
  });

  /**
   * The extras arrive BESIDE the code, not under `details`.
   *
   * The mapper attaches whatever the refusal knows — the stamp a lost publish
   * race recorded, the unknown token a pattern named — at the top level, so a
   * reader that looked only under `details` would find nothing at all.
   */
  it('collects the extras the mapper attaches beside the code', () => {
    const decoded = decodePublicationError(
      refusal({ code: 'ISSUE_ALREADY_PUBLISHED', message: 'already out', stampedAt: 1_700_000 }),
    );
    expect(decoded.kind === 'publication' && decoded.details).toEqual({ stampedAt: 1_700_000 });
  });

  /** A code the mapper recognises but this build has no sentence for is still a refusal. */
  it('keeps an unrecognised code as a refusal rather than demoting it to a bare failure', () => {
    const decoded = decodePublicationError(refusal({ code: 'CATEGORY_IN_USE', message: 'in use' }));
    expect(decoded.kind).toBe('publication');
    expect(decoded.kind === 'publication' && decoded.code).toBe('CATEGORY_IN_USE');
    // …and it still keeps the caller's own generic sentence rather than the code.
    expect(publicationErrorKey(refusal({ code: 'CATEGORY_IN_USE' }), 'fallback')).toBe('fallback');
  });

  it('finds a catalogue code inside a message-only body', () => {
    const decoded = decodePublicationError(
      refusal({ message: 'SERIES_NOT_ACTIVE: weekly-ntm is a draft' }, 409),
    );
    expect(decoded.kind === 'publication' && decoded.code).toBe('SERIES_NOT_ACTIVE');
  });

  it('finds a catalogue code inside a plain-text body', () => {
    const decoded = decodePublicationError(refusal('CUTOFF_IN_FUTURE: not yet', 400));
    expect(decoded).toEqual({
      kind: 'publication',
      code: 'CUTOFF_IN_FUTURE',
      message: 'CUTOFF_IN_FUTURE: not yet',
      status: 400,
      details: {},
      fieldErrors: [],
      unacknowledgedWarnings: [],
    });
  });

  it('reports a legacy bare failure as one, keeping its status and whatever text came back', () => {
    expect(decodePublicationError(refusal('Internal Server Error', 500))).toEqual({
      kind: 'bare',
      status: 500,
      message: 'Internal Server Error',
    });
    expect(decodePublicationError(refusal({ message: 'no idea' }, 502))).toEqual({
      kind: 'bare',
      status: 502,
      message: 'no idea',
    });
    expect(decodePublicationError(refusal(null, 404))).toEqual({ kind: 'bare', status: 404 });
    expect(decodePublicationError({ status: 0 })).toEqual({ kind: 'bare', status: 0 });
    expect(decodePublicationError(undefined)).toEqual({ kind: 'bare', status: 0 });
  });

  /**
   * A synthesized envelope carries no status, and must not claim one.
   *
   * Several screens re-ask for a sentence with only a code in hand — the code
   * they already stored from the refusal — so `0` is the honest answer rather
   * than a guessed 400.
   */
  it('reports status 0 where the failure was not an HTTP response', () => {
    const decoded = decodePublicationError({ error: { code: 'ISSUE_NOT_OPEN' } });
    expect(decoded.kind === 'publication' && decoded.status).toBe(0);
  });

  /** Three strings or nothing: a half-filled row would render as three blanks. */
  it('drops a field error that is not a complete row', () => {
    const decoded = decodePublicationError(
      refusal({
        code: 'SERIES_INVALID',
        fieldErrors: [
          { rule: 'S-7', field: 'nominalCutoffTime', message: 'required' },
          { rule: 'S-8', field: 'cadence' },
          'not a row',
        ],
      }),
    );
    expect(decoded.kind === 'publication' && decoded.fieldErrors).toEqual([
      { rule: 'S-7', field: 'nominalCutoffTime', message: 'required' },
    ]);
  });
});

/**
 * The two readings every caller takes off a refusal.
 *
 * Both go through the one decoder, so a bare failure answers empty rather than
 * throwing on an optional chain that was written slightly differently at each
 * call site.
 */
describe('what a caller reads off a refusal', () => {
  it('keeps the warning codes a refused publish is still waiting on', () => {
    expect(
      unacknowledgedWarningsOf(
        refusal({
          code: 'WARNING_NOT_ACKNOWLEDGED',
          message: 'not acknowledged',
          unacknowledgedWarnings: ['CANCELLED_BUT_DATE_ALIVE', 'TYPE_DRIFT'],
        }),
      ),
    ).toEqual(['CANCELLED_BUT_DATE_ALIVE', 'TYPE_DRIFT']);
  });

  it('reads no warnings off a refusal that named none, or off a bare failure', () => {
    expect(unacknowledgedWarningsOf(refusal({ code: 'ISSUE_NOT_OPEN' }))).toEqual([]);
    expect(unacknowledgedWarningsOf(refusal('Bad Gateway', 502))).toEqual([]);
    expect(unacknowledgedWarningsOf(undefined)).toEqual([]);
  });

  it('reads the rules a refused save named, for a form to render in place', () => {
    expect(
      publicationFieldErrors(
        refusal({
          code: 'SERIES_INVALID',
          fieldErrors: [{ rule: 'S-1', field: 'criteria', message: 'no criteria' }],
        }),
      ),
    ).toEqual([{ rule: 'S-1', field: 'criteria', message: 'no criteria' }]);
    expect(publicationFieldErrors(refusal('Server Error', 500))).toEqual([]);
  });
});

/**
 * EVERY field a series refusal can name has a label on the screen.
 *
 * The validator names the field by its WIRE path, and the editor turns that path
 * into the label above the control. A path the mapping does not recognise used
 * to be printed as its unresolved key — `admin.publications.series.field.status`
 * — beside a sentence in English, which reads as a broken translation rather
 * than as a rule about the status field.
 *
 * The list is the whole vocabulary of `SeriesValidator` (rules S-1 to S-25, D-7,
 * and the criteria rules C-1 to C-10, which name a position INSIDE the criteria
 * document). A rule added there without a label here is a red test rather than a
 * key path in front of an admin.
 */
describe('the field a series rule refused, as the screen names it', () => {
  /** Every `field` value `SeriesValidator` can put on a SERIES_INVALID row. */
  const FIELDS: readonly string[] = [
    // Single controls.
    'timeRelation',
    'criteria',
    'reportId',
    'sourceSeriesId',
    'aliveAtCutoff',
    'firstIssueStartsAt',
    'nominalCutoffDay',
    'nominalCutoffDayOfMonth',
    'nominalCutoffMonth',
    'nominalCutoffTime',
    'cutoffDefault',
    'nextIssueCreation',
    'numberingScheme',
    'messageSortOrder',
    'languages',
    'descs',
    'categoryId',
    'domainId',
    'availability',
    'availableDomainIds',
    'releaseMode',
    'status',
    'seriesId',
    'messagePublication',
    // Per-language texts.
    'descs.da.name',
    'descs.da.messageReferenceFormat',
    'descs.da.nameSuggestionPattern',
    'descs.da.fileNamePattern',
    'descs.da.linkPattern',
    // A report parameter, named by the key somebody typed.
    'reportParams.week',
    // The criteria document, by JSON pointer into it.
    'criteria/schemaVersion',
    'criteria/match',
    'criteria/criteria',
    'criteria/criteria/0',
    'criteria/criteria/0/operator',
    'criteria/criteria/0/values',
    'criteria/criteria/0/values/0',
  ];

  /** The leaf value at a dotted key path, or `undefined` where there is none. */
  function lookup(path: string): unknown {
    return path
      .split('.')
      .reduce<unknown>(
        (node, part) =>
          node && typeof node === 'object' ? (node as Record<string, unknown>)[part] : undefined,
        en,
      );
  }

  it('resolves every field the validator can name to a key the bundle carries', () => {
    const unlabelled = FIELDS.filter((field) => {
      const label = seriesFieldLabel(field);
      return !label || typeof lookup(label.key) !== 'string';
    });
    expect(unlabelled, `fields with no label key:\n${unlabelled.join('\n')}`).toEqual([]);
  });

  /**
   * A criteria rule names a position inside the criteria DOCUMENT.
   *
   * The form has one criteria control, so the pointer names nothing on the screen
   * and the label is the control's own. The rule's sentence beside it is what
   * says which part of the document was refused.
   */
  it('labels every criteria pointer with the criteria control', () => {
    for (const field of ['criteria', 'criteria/match', 'criteria/criteria/0/values/0']) {
      expect(seriesFieldLabel(field)?.key).toBe('admin.publications.series.field.criteria');
    }
  });

  /** A per-language text says which language it is about. */
  it('carries the language of a per-language text', () => {
    expect(seriesFieldLabel('descs.da.name')).toEqual({
      key: 'admin.publications.series.field.name',
      lang: 'da',
    });
  });

  /**
   * A report parameter is named by the key somebody typed, which no bundle has.
   *
   * The field is labelled and the key travels beside it, so the reader can tell
   * which row of the parameter table the rule refused over.
   */
  it('carries the key of a report parameter beside its label', () => {
    expect(seriesFieldLabel('reportParams.week')).toEqual({
      key: 'admin.publications.series.field.reportParams',
      param: 'week',
    });
  });

  /** A path in none of those shapes is shown raw — ugly and honest. */
  it('resolves nothing for a path it does not recognise', () => {
    expect(seriesFieldLabel('')).toBeUndefined();
    expect(seriesFieldLabel('a.b')).toBeUndefined();
    expect(seriesFieldLabel('descs.da.name.extra')).toBeUndefined();
  });
});

/**
 * The messages a removal was refused over, named the way every other row names a
 * message: by its short id where it has one, and by its title where it has not.
 *
 * A uid promoted into the id slot was PRINTED as the message's name — a uuid in
 * a list whose whole point is telling an admin which messages to go and change.
 */
describe('the messages a refused removal named', () => {
  it('keeps the id, the uid and the title apart', () => {
    const citations = issueCitationsOf(
      refusal({
        code: 'ISSUE_CITED',
        citingMessages: [
          { messageId: 'NM-814-26', uid: 'uid-a', title: 'Buoy off station' },
          { uid: 'uid-b', title: 'Not numbered yet' },
        ],
        citingCount: 31,
      }),
    );

    expect(citations?.messages).toEqual([
      { messageId: 'NM-814-26', uid: 'uid-a', title: 'Buoy off station' },
      { uid: 'uid-b', title: 'Not numbered yet' },
    ]);
    // The list is capped server-side, so the count is the real obstacle.
    expect(citations?.count).toBe(31);
  });

  /** A row that neither names nor addresses a message renders as nothing. */
  it('drops a row carrying neither an id nor a uid', () => {
    const citations = issueCitationsOf(
      refusal({ code: 'ISSUE_CITED', citingMessages: [{ title: 'Nameless' }, 'not a row'] }),
    );
    expect(citations?.messages).toEqual([]);
  });

  /** `undefined` where the refusal was not about citations at all. */
  it('answers undefined for any other refusal', () => {
    expect(issueCitationsOf(refusal({ code: 'ISSUE_NOT_OPEN' }))).toBeUndefined();
  });
});

/**
 * Why a series rule refused, worded once for every screen that lists one.
 *
 * The two editors list a refused rule beside its field, and the series page
 * lists the rules an activation or a reinstatement was refused over (L-044).
 * One function words them, so a rule reads the same in both places.
 */
describe('the sentence a series rule refused with', () => {
  const sentences: Readonly<Record<string, string>> = {
    'admin.publications.rule.S-17': 'An active series must name its report.',
    'admin.publications.rule.S-8': 'A weekly series makes its next issue by itself.',
  };
  /** A bundle that knows two rules, and words the generic line with its id. */
  function translate(key: string, params?: Record<string, unknown>): string {
    if (key === 'admin.publications.series.ruleFailed') {
      return `Refused by rule ${String(params?.['rule'])}`;
    }
    return sentences[key] ?? key;
  }

  it('says the rule in words and keeps its id beside the sentence', () => {
    expect(seriesRuleText(translate, 'S-17')).toBe('An active series must name its report. (S-17)');
  });

  it('falls back to the generic line, which carries the id, for a rule with no sentence', () => {
    expect(seriesRuleText(translate, 'S-99')).toBe('Refused by rule S-99');
  });

  it('lists each refused rule once, in the order the server named them', () => {
    expect(
      seriesRuleTexts(translate, [
        { rule: 'S-8', field: 'nextIssue', message: 'x' },
        { rule: 'S-17', field: 'descs.da.name', message: 'y' },
        { rule: 'S-17', field: 'descs.en.name', message: 'y' },
      ]),
    ).toEqual([
      'A weekly series makes its next issue by itself. (S-8)',
      'An active series must name its report. (S-17)',
    ]);
  });
});
