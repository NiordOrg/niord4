/**
 * init's answers: the handful of values a new country repository is generated from, read and held
 * to one set of rules whichever way they arrive. init asks them one by one, showing each standard
 * default, or reads them from a file (`--answers <file>`, for the prompt, the harness and the
 * tests); both routes hand the same object to readAnswers, and any answer it refuses stops init
 * before a file is written.
 *
 * The answers are held first to their schema (init-answers.schema.json beside the kit, where an
 * unknown answer fails), then to the rules a schema cannot say:
 *
 *   - the texts (the name, the organisation, the domain's name, the short format) are not blank
 *     and have no leading or trailing spaces, which would reach the manifest, the README's
 *     heading and the domain's name as they are;
 *   - English is not among the languages: it is always there;
 *   - the producer code is not another authority's, nor the country guide's placeholder;
 *   - the time zone is one Intl takes, has the case-sensitive shape of the tz database's names
 *     and is not a case-only variant of the name Intl resolves it to. Java's
 *     TimeZone.getTimeZone is case-sensitive and gives GMT, without an error, for a name it does
 *     not know (Domain.java), so a wrong case would put the domain in GMT. The validator asks Intl
 *     to take the zone and never consults Intl.supportedValuesOf: Node's ICU resolves some
 *     current IANA names to their old links and lists only the old ones, while Java's tz database
 *     holds the current names, which must therefore be accepted;
 *   - the domain and series ids are not the base product's own domain (niord-client-master,
 *     country-api.md) nor a client id Keycloak already holds;
 *   - the series' short format uses the backend's tokens only (MessageSeriesService's
 *     FormatResolver), closes every `${`, and has a number token unless the series is not
 *     numbered;
 *   - the map's centre and zoom are in range.
 *
 * Every refusal is `{ answer, message }`: the answer's place (`map.lat`, `owners[1]`) and what is
 * wrong, followed by the answer's rule. readAnswers then applies the standard defaults, so its
 * result is the complete answers object the generator reads.
 *
 * Not answers: the default language is English (DEFAULT_LANGUAGE; another default is the country
 * guide's in-depth recipe), S-124 is always enabled (the producer code is the one S-124 answer),
 * and the registry, the image project and the secrets are a deployment's values, not init's.
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { validate } from './schema.mjs';

const KIT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
export const ANSWERS_SCHEMA_FILE = path.join(KIT, 'init-answers.schema.json');

/** The default language of both applications and of the settings: never an answer. */
export const DEFAULT_LANGUAGE = 'en';

/**
 * The ids no domain or series may take: the base product's own domain, then Keycloak's built-in
 * clients and the shared niord-web client, which a domain's client of the same id would collide
 * with.
 */
export const REFUSED_IDS = Object.freeze([
  'niord-client-master',
  'account',
  'account-console',
  'admin-cli',
  'broker',
  'realm-management',
  'security-admin-console',
  'niord-web',
]);

/** The producer codes no country may answer, with the reason each gives. */
const REFUSED_PRODUCER_REASONS = Object.freeze({
  DK00: "another authority's code",
  DK01: "another authority's code",
  QQ00: "the country guide's placeholder",
});
export const REFUSED_PRODUCER_CODES = Object.freeze(Object.keys(REFUSED_PRODUCER_REASONS));

/** The tokens of a series' short format, as the backend's FormatResolver replaces them. */
export const SHORT_FORMAT_TOKENS = Object.freeze([
  'year-2-digits',
  'year',
  'week',
  'week-2-digits',
  'number',
  'number-3-digits',
  'main-type',
  'main-type-lower',
  'country',
  'id',
  'legacy-id',
  't-or-p',
]);
/** The tokens that number a message: a numbered series' short format holds one. */
export const NUMBER_TOKENS = Object.freeze(['number', 'number-3-digits']);

const MAIN_TYPE_NAMES = Object.freeze({
  NW: 'Navigational Warnings',
  NM: 'Notices to Mariners',
});
const RANGES = Object.freeze({
  'map.lat': [-90, 90],
  'map.lon': [-180, 180],
  'map.zoom': [1, 18],
});
// UTC, or Area/Location with capitals as the tz database writes them: Java reads no other case.
const TIME_ZONE_SHAPE = /^(UTC|[A-Z][A-Za-z_]+(\/[A-Z][A-Za-z0-9_+-]+)+)$/;
const TOKEN = /\$\{([^${}]*)\}/g;

const PLAIN =
  'plain text (no <, > or & and no control character), not blank and without leading or trailing spaces';
/** The texts held to PLAIN, which the schema's pattern says only in part. */
const TEXT_ANSWERS = Object.freeze(['name', 'organisation', 'domain.name', 'series.shortFormat']);
const ID_RULE =
  "lower-case letters a-z, digits and '-', starting with a letter, 2 to 63 characters; " +
  `not ${REFUSED_IDS.join(', ')}`;

/** Each answer's rule, as a refusal states it. */
const RULES = Object.freeze({
  answers: 'the answers are one object',
  country: "the country's ISO 3166-1 alpha-2 code, two letters a-z",
  name: `the country's name, ${PLAIN}, at most 60 characters`,
  organisation: `the authority that runs the deployment, ${PLAIN}, at most 100 characters`,
  languages:
    'the languages besides English, each a 2-3-letter code a-z, named once; ' +
    'English is always there and is not listed',
  producerCode:
    'the IHO S-62 producer code, four upper-case letters A-Z or digits, or empty; ' +
    `not ${REFUSED_PRODUCER_CODES.join(', ')}`,
  timeZone:
    'an IANA time zone that Intl takes, spelt in the case the tz database spells it ' +
    '(UTC, or Area/Location with capitals): Java reads a name it does not know as GMT',
  map: 'the map: lat and lon required, zoom optional, each a whole number',
  'map.lat': 'the latitude, a whole number of degrees from -90 to 90',
  'map.lon': 'the longitude, a whole number of degrees from -180 to 180',
  'map.zoom': 'the zoom level, a whole number from 1 to 18',
  domain: 'the first domain: id, name and mainType, each optional',
  'domain.id': `the domain id: ${ID_RULE}`,
  'domain.name': `the domain's name, ${PLAIN}, at most 100 characters`,
  'domain.mainType': 'NW (navigational warnings) or NM (notices to mariners)',
  series: 'the first series: id, shortFormat and numberSequenceType, each optional',
  'series.id': `the series id: ${ID_RULE}`,
  'series.shortFormat':
    `${PLAIN} with the tokens ${SHORT_FORMAT_TOKENS.map((t) => `\${${t}}`).join(', ')} only, ` +
    `and \${number} or \${number-3-digits} unless numberSequenceType is NONE`,
  'series.numberSequenceType': 'YEARLY, CONTINUOUS, MANUAL or NONE',
  owners: "the repository's owners, one or more, each a GitHub @user or @org/team, named once",
  defaultLanguage:
    "it is not an answer: the default language is English, and another default is the country guide's in-depth recipe",
});

const isObject = (value) => value !== null && typeof value === 'object' && !Array.isArray(value);

let schema;
/** The answers' schema, read once. */
export function answersSchema() {
  schema ??= JSON.parse(fs.readFileSync(ANSWERS_SCHEMA_FILE, 'utf8'));
  return schema;
}

/** The answers an object may hold at a place: the schema's properties there. */
function knownAnswers(at) {
  const properties =
    at === '' ? answersSchema().properties : answersSchema().properties[at]?.properties;
  return Object.keys(properties ?? {}).filter((key) => key !== 'defaultLanguage');
}

const ruleOf = (answer) => {
  const key = answer.replace(/\[\d+\]/g, '');
  return Object.hasOwn(RULES, key) ? RULES[key] : undefined;
};
const withRule = (answer, message) => {
  const rule = ruleOf(answer);
  return { answer, message: rule ? `${message}; ${rule}` : message };
};

/** A schema problem as a refusal: the answer it is about and its message with the rule. */
function fromSchema({ path: at, message }) {
  const missing = /^has no (.+)$/.exec(message);
  if (missing) {
    const answer = at === '$' ? missing[1] : `${at}.${missing[1]}`;
    return withRule(answer, 'is required');
  }
  if (at === '$') return withRule('answers', message);
  if (message === 'is an unknown field') {
    // `map.height`, `map["x-y"]` and `["x-y"]` sit in map, map and the answers themselves.
    const parent = /^(.*?)(?:\.[^.[]+|\[[^\]]*\])$/.exec(at)?.[1] ?? '';
    return {
      answer: at,
      message: `is not an answer init knows (${knownAnswers(parent).join(', ')})`,
    };
  }
  return withRule(at, message);
}

/**
 * The standard defaults, for the answers given: the ones that follow from the country, the name
 * and the domain's main type, and the fixed ones. Every answer but the seven required ones has
 * its default here.
 */
export function standardDefaults(answers) {
  const given = isObject(answers) ? answers : {};
  const cc = typeof given.country === 'string' ? given.country : '';
  const name = typeof given.name === 'string' ? given.name : '';
  const mainType = isObject(given.domain) && given.domain.mainType === 'NM' ? 'NM' : 'NW';
  const id = `${cc}-${mainType.toLowerCase()}`;
  return {
    languages: [],
    producerCode: '',
    map: { zoom: 6 },
    domain: { id, name: `${name} ${MAIN_TYPE_NAMES[mainType]}`, mainType },
    series: {
      id,
      shortFormat: `${cc.toUpperCase()}-${mainType}-\${number-3-digits}-\${year-2-digits}`,
      numberSequenceType: 'YEARLY',
    },
  };
}

/** The complete answers of answers the schema passes: the defaults filled in, the fixed order. */
function completed(input) {
  const defaults = standardDefaults(input);
  const map = input.map;
  const domain = input.domain ?? {};
  const series = input.series ?? {};
  return {
    country: input.country,
    name: input.name,
    organisation: input.organisation,
    languages: [...(input.languages ?? defaults.languages)],
    producerCode: input.producerCode ?? defaults.producerCode,
    timeZone: input.timeZone,
    map: { lat: map.lat, lon: map.lon, zoom: map.zoom ?? defaults.map.zoom },
    domain: {
      id: domain.id ?? defaults.domain.id,
      name: domain.name ?? defaults.domain.name,
      mainType: domain.mainType ?? defaults.domain.mainType,
    },
    series: {
      id: series.id ?? defaults.series.id,
      shortFormat: series.shortFormat ?? defaults.series.shortFormat,
      numberSequenceType: series.numberSequenceType ?? defaults.series.numberSequenceType,
    },
    owners: [...input.owners],
  };
}

/** What is wrong with a time zone the schema passed, or undefined. */
function timeZoneProblem(value) {
  let resolved;
  try {
    resolved = new Intl.DateTimeFormat('en', { timeZone: value }).resolvedOptions().timeZone;
  } catch {
    return `${JSON.stringify(value)} is not a time zone Intl knows`;
  }
  if (!TIME_ZONE_SHAPE.test(value))
    return `${JSON.stringify(value)} does not have the case-sensitive shape of a tz database name`;
  if (resolved !== value && resolved.toLowerCase() === value.toLowerCase())
    return `${JSON.stringify(value)} differs from ${resolved} only in case`;
  return undefined;
}

/** What is wrong with a short format the schema passed. */
function shortFormatProblems(format, numberSequenceType) {
  const problems = [];
  const tokens = [...format.matchAll(TOKEN)].map((match) => match[1]);
  for (const token of new Set(tokens))
    if (!SHORT_FORMAT_TOKENS.includes(token))
      problems.push(`\${${token}} is not a token the backend replaces`);
  if (format.replace(TOKEN, '').includes('${')) problems.push("has an unclosed '${'");
  if (
    numberSequenceType !== undefined &&
    numberSequenceType !== 'NONE' &&
    !tokens.some((token) => NUMBER_TOKENS.includes(token))
  )
    problems.push(
      `has no number token, which a series numbered ${numberSequenceType} needs (only NONE goes without)`,
    );
  return problems;
}

/**
 * The rules a schema cannot say, over answers whose places in `skip` the schema refused. The
 * texts are checked as given (`given`), so that a default made from a refused name, such as the
 * domain's name, is not refused a second time.
 */
function ruleProblems(answers, skip, given) {
  const problems = [];
  const fail = (answer, message) => problems.push(withRule(answer, message));
  const check = (answer, value, test) => {
    if (value !== undefined && !skip(answer)) test(value);
  };

  for (const answer of TEXT_ANSWERS) {
    const [part, key] = answer.includes('.') ? answer.split('.') : [undefined, answer];
    const holder = part === undefined ? given : given[part];
    if (!isObject(holder)) continue;
    check(answer, holder[key], (text) => {
      if (typeof text !== 'string') return;
      if (text.trim() === '') fail(answer, 'is blank');
      else if (text.trim() !== text) fail(answer, 'has leading or trailing spaces');
    });
  }

  if (Array.isArray(answers.languages))
    answers.languages.forEach((lang, i) =>
      check(`languages[${i}]`, lang, () => {
        if (lang === DEFAULT_LANGUAGE) fail(`languages[${i}]`, `is English, which is always there`);
      }),
    );
  check('producerCode', answers.producerCode, (code) => {
    if (Object.hasOwn(REFUSED_PRODUCER_REASONS, code))
      fail('producerCode', `${JSON.stringify(code)} is ${REFUSED_PRODUCER_REASONS[code]}`);
  });
  check('timeZone', answers.timeZone, (zone) => {
    const problem = timeZoneProblem(zone);
    if (problem) fail('timeZone', problem);
  });
  if (isObject(answers.map))
    for (const key of ['lat', 'lon', 'zoom'])
      check(`map.${key}`, answers.map[key], (value) => {
        const [min, max] = RANGES[`map.${key}`];
        if (value < min || value > max) fail(`map.${key}`, `is ${value}, outside ${min} to ${max}`);
      });
  for (const part of ['domain', 'series'])
    if (isObject(answers[part]))
      check(`${part}.id`, answers[part].id, (id) => {
        if (id === REFUSED_IDS[0]) fail(`${part}.id`, `"${id}" is the base product's own domain`);
        else if (REFUSED_IDS.includes(id))
          fail(`${part}.id`, `"${id}" is a client id Keycloak already holds`);
      });
  if (isObject(answers.series))
    check('series.shortFormat', answers.series.shortFormat, (format) => {
      const type = skip('series.numberSequenceType')
        ? undefined
        : (answers.series.numberSequenceType ?? 'YEARLY');
      for (const problem of shortFormatProblems(format, type)) fail('series.shortFormat', problem);
    });
  return problems;
}

/** Whether `at` is `place`, inside it or holds it. */
const overlaps = (at, place) =>
  at === place ||
  at.startsWith(`${place}.`) ||
  at.startsWith(`${place}[`) ||
  place.startsWith(`${at}.`) ||
  place.startsWith(`${at}[`);

/**
 * Every refusal of the answers given, `{ answer, message }` each; an empty list when init can
 * take them. The schema's refusals come first, then the rules beyond it, which are applied to
 * the complete answers (the defaults filled in) when the schema passed and otherwise only to the
 * answers it passed.
 */
export function answerProblems(input) {
  const problems = validate(input, answersSchema()).map(fromSchema);
  if (!isObject(input)) return problems;
  const refused = problems.map((problem) => problem.answer);
  const skip = (answer) => refused.some((at) => overlaps(at, answer));
  if (problems.length) return [...problems, ...ruleProblems(input, skip, input)];
  const answers = completed(input);
  // The defaults derived from valid answers are valid; they are held to the same rules anyway.
  const derived = validate(answers, answersSchema()).map(fromSchema);
  for (const problem of derived) problem.message = `the standard default ${problem.message}`;
  return [...derived, ...ruleProblems(answers, skip, input)];
}

/**
 * The complete answers object: every answer, the standard defaults filled in, in the fixed order
 * country, name, organisation, languages, producerCode, timeZone, map { lat, lon, zoom },
 * domain { id, name, mainType }, series { id, shortFormat, numberSequenceType }, owners. A new
 * object, the input untouched; each value as the person gave it. Throws one Error naming every
 * refusal, a line `<answer>: <message>` each.
 */
export function readAnswers(input) {
  const problems = answerProblems(input);
  if (problems.length)
    throw new Error(
      `the answers are refused:\n${problems.map(({ answer, message }) => `${answer}: ${message}`).join('\n')}`,
    );
  return completed(input);
}
