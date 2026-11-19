/**
 * The declared date fields of a publications payload, normalised from ISO 8601 to
 * epoch milliseconds.
 *
 * The internal REST API serialises `java.util.Date` as an ISO 8601 string with an
 * offset (`2019-12-31T22:59:59.999+00:00`), while the publications models type
 * those fields as `number` and compare and subtract them. A string in a numeric
 * comparison is not a type error at runtime — it is an answer, and always the
 * wrong one: `"2019-12-31T22:59:59.999+00:00" <= Date.now()` is false, so a
 * cut-off six years in the past reads as one that has not happened yet. Running
 * the response through this at the API boundary keeps the wrong answer from ever
 * reaching a model.
 *
 * **Only declared date fields are converted.** A value is an instant because the
 * contract says the field is one, never because it looks like one: a report
 * parameter, a name or a criteria operand that happens to be shaped like a
 * timestamp is content, and rewriting it to a number would change what the
 * editor saves back. The field list below is the set of `number`-typed dates the
 * publications models declare; a field the backend adds is a string until it is
 * declared here and in the model together.
 *
 * **An offset is required.** A date-time without one names a wall clock and
 * nothing else, and `Date.parse` resolves it against the BROWSER's zone — which
 * is the one clock this application is never allowed to read a domain instant
 * from. Such a string is left exactly as it arrived rather than converted into an
 * instant that is right in Copenhagen and an hour out everywhere else. The
 * backend always sends the offset, so nothing on the wire is skipped by this.
 */

/** The `number`-typed instants the publications models declare, by field name. */
const DATE_FIELDS: ReadonlySet<string> = new Set([
  'created',
  'updated',
  'cutoff',
  'cutoffStampedAt',
  'effectiveCutoff',
  'expectedPublicFrom',
  'expectedPublicTo',
  'firstIssueStartsAt',
  'frozenPublishDateFrom',
  'frozenPublishDateTo',
  'intervalFrom',
  'intervalTo',
  'lateAfter',
  'publicFrom',
  'publicTo',
  'publishedAt',
  'renderedAt',
  'stampedAt',
  'viewedAt',
  'viewedFrom',
]);

/**
 * Free-form containers whose content belongs to the user or to a rule, not to the
 * wire contract: report parameters, audit and checklist detail, criteria
 * documents, name maps. Nothing inside them is a declared date, so they are
 * handed on exactly as they arrived, whatever their values look like.
 */
const OPAQUE_FIELDS: ReadonlySet<string> = new Set([
  'reportParams',
  'detail',
  'detailParams',
  'criteria',
  'criteriaOverride',
  'criteriaSnapshot',
  'names',
  'params',
  'properties',
]);

/**
 * A whole string, and nothing but: date, `T`, time, optional fraction, offset.
 *
 * Jackson emits three milliseconds of fraction and `+HH:MM`; the fraction is
 * accepted at any length and `Z` beside the numeric offset, so a server that
 * tightens or widens its formatter does not silently stop being understood.
 */
const ISO_DATE_TIME = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?(?:Z|[+-]\d{2}:\d{2})$/;

/**
 * `value` with every declared date field, at any depth, as epoch milliseconds.
 *
 * Walks objects and arrays, skipping the opaque containers. The input is never
 * mutated: a container holding a converted value is rebuilt, and one holding
 * nothing to convert is returned as it came — the workbench payload is eight
 * thousand member rows deep and copying the ones with no date in them buys
 * nothing.
 */
export function withEpochDates<T>(value: T): T {
  return convert(value) as T;
}

function convert(value: unknown): unknown {
  if (Array.isArray(value)) {
    let changed = false;
    const out = value.map((item: unknown) => {
      const converted = convert(item);
      changed ||= converted !== item;
      return converted;
    });
    return changed ? out : value;
  }
  if (isPlainObject(value)) {
    let changed = false;
    const out: Record<string, unknown> = {};
    for (const key of Object.keys(value)) {
      const original = value[key];
      const converted = OPAQUE_FIELDS.has(key)
        ? original
        : DATE_FIELDS.has(key)
          ? instantOf(original)
          : convert(original);
      changed ||= converted !== original;
      out[key] = converted;
    }
    return changed ? out : value;
  }
  return value;
}

/**
 * One instant of a payload as epoch milliseconds, for a model that types a single field `number`
 * while its endpoint writes the `java.util.Date` as ISO 8601 text (the mail queue's). A number
 * passes as it came, an ISO instant with an offset is parsed under the same rule as
 * {@link withEpochDates}, and anything else (absent, null, a date-time with no offset, an
 * impossible date) is undefined: no time is shown rather than one read from the browser's clock.
 */
export function epochMillisOf(value: unknown): number | undefined {
  if (typeof value === 'number') {
    return Number.isFinite(value) ? value : undefined;
  }
  const instant = instantOf(value);
  return typeof instant === 'number' ? instant : undefined;
}

/** A declared date field's value: converted when it is an ISO instant, else as it came. */
function instantOf(value: unknown): unknown {
  if (typeof value !== 'string' || !ISO_DATE_TIME.test(value)) {
    return value;
  }
  const epoch = Date.parse(value);
  // A well-formed string naming an impossible date (month 13) parses to NaN.
  // It stays as it is: NaN is not an instant, and it would erase the one piece
  // of evidence about what the server actually sent.
  return Number.isNaN(epoch) ? value : epoch;
}

/**
 * Plain JSON containers only.
 *
 * A `Date`, a `Blob` or anything else with a prototype of its own is returned
 * untouched rather than shallow-copied key by key, which would hand back an
 * object of the wrong class carrying none of its behaviour.
 */
function isPlainObject(value: unknown): value is Record<string, unknown> {
  if (value === null || typeof value !== 'object') {
    return false;
  }
  const proto: unknown = Object.getPrototypeOf(value);
  return proto === Object.prototype || proto === null;
}
