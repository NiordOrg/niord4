/**
 * Tests for country-kit/lib/answers.mjs and country-kit/init-answers.schema.json: init's answers.
 *
 *   npm run test:guards      # from fe/
 *
 * One case per rule an answer is held to, the refused and the accepted values side by side, then
 * the standard defaults that make a complete answers object. An answer that slips through reaches
 * the generated backend, where it fails late or not at all: a time zone Java does not know is
 * read as GMT, a domain id Keycloak already holds takes over its client. The countries here are
 * made up (xx, qq).
 */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { describe, it } from 'node:test';
import {
  ANSWERS_SCHEMA_FILE,
  answerProblems,
  answersSchema,
  DEFAULT_LANGUAGE,
  readAnswers,
  standardDefaults,
} from './lib/answers.mjs';
import { checkSchema } from './lib/schema.mjs';

/** The seven answers init never defaults, and nothing else. */
const minimal = () => ({
  country: 'xx',
  name: 'Example Land',
  organisation: 'Example Maritime Office',
  timeZone: 'UTC',
  map: { lat: 10, lon: 20 },
  owners: ['@example'],
});

/** Every answer given. */
const complete = () => ({
  country: 'qq',
  name: 'Qualia',
  organisation: 'Qualia Maritime Office',
  languages: ['da', 'kl'],
  producerCode: 'AB12',
  timeZone: 'Europe/London',
  map: { lat: 10, lon: 20, zoom: 7 },
  domain: { id: 'qq-warnings', name: 'Qualia Warnings', mainType: 'NW' },
  series: {
    id: 'qq-nw',
    shortFormat: 'QQ-NW-${number-3-digits}-${year-2-digits}',
    numberSequenceType: 'YEARLY',
  },
  owners: ['@qualia/navigational-warnings', '@someone'],
});

/** The answers with one value set (a dotted path; `undefined` removes it). */
function withAnswer(path, value, base = complete()) {
  const answers = structuredClone(base);
  const keys = path.split('.');
  const last = keys.pop();
  let at = answers;
  for (const key of keys) at = at[key] ??= {};
  if (value === undefined) delete at[last];
  else at[last] = value;
  return answers;
}

const show = (problems) =>
  problems.map(({ answer, message }) => `${answer}: ${message}`).join('\n') || '(none)';

/** The answer is refused, by a problem naming it whose message holds each fragment. */
function assertRefused(answers, answer, ...fragments) {
  const problems = answerProblems(answers);
  const named = problems.filter((problem) => problem.answer === answer);
  assert.ok(named.length, `expected a problem naming ${answer}, got:\n${show(problems)}`);
  for (const fragment of fragments)
    assert.ok(
      named.some(({ message }) =>
        fragment instanceof RegExp ? fragment.test(message) : message.includes(fragment),
      ),
      `expected ${answer}'s problem to state ${fragment}, got:\n${show(named)}`,
    );
}

function assertAccepted(answers) {
  const problems = answerProblems(answers);
  assert.deepEqual(problems, [], show(problems));
}

describe('the answers schema', () => {
  it("is the kit's own schema checker's, read from the file the module names", () => {
    const schema = JSON.parse(fs.readFileSync(ANSWERS_SCHEMA_FILE, 'utf8'));
    checkSchema(schema);
    assert.deepEqual(answersSchema(), schema);
    assert.deepEqual([...schema.required].sort(), [
      'country',
      'map',
      'name',
      'organisation',
      'owners',
      'timeZone',
    ]);
    assert.deepEqual([...schema.properties.map.required].sort(), ['lat', 'lon']);
    assert.equal(schema.additionalProperties, false);
  });

  it('passes a complete answers object and the seven required answers alone', () => {
    assertAccepted(complete());
    assertAccepted(minimal());
  });
});

describe('the required answers', () => {
  for (const answer of ['country', 'name', 'organisation', 'timeZone', 'map', 'owners'])
    it(`refuses the answers without ${answer}, naming it once`, () => {
      const problems = answerProblems(withAnswer(answer, undefined, minimal()));
      assert.deepEqual(
        problems.map((problem) => problem.answer),
        [answer],
        show(problems),
      );
    });

  for (const answer of ['map.lat', 'map.lon'])
    it(`refuses the answers without ${answer}, naming it once`, () => {
      const problems = answerProblems(withAnswer(answer, undefined, minimal()));
      assert.deepEqual(
        problems.map((problem) => problem.answer),
        [answer],
        show(problems),
      );
    });

  it('refuses answers that are not an object', () => {
    for (const answers of [[], null, 'xx'])
      assert.equal(answerProblems(answers).length, 1, JSON.stringify(answers));
  });
});

describe('the country and the texts', () => {
  it('refuses a country that is not two letters a-z', () => {
    for (const country of ['DK', 'dnk', 'd1', 'x', ''])
      assertRefused(withAnswer('country', country), 'country', 'a-z');
    assertAccepted(withAnswer('country', 'do'));
  });

  it('holds the name to 60 characters of plain text', () => {
    assertAccepted(withAnswer('name', 'n'.repeat(60)));
    assertRefused(withAnswer('name', 'n'.repeat(61)), 'name', '60');
    for (const name of ['A <b>', 'A & B', 'A\u0007B', 'A\nB', ''])
      assertRefused(withAnswer('name', name), 'name', 'plain text');
  });

  it('holds the organisation to plain text, without markup', () => {
    assertAccepted(withAnswer('organisation', 'The Office of the Sea, North and South'));
    for (const organisation of ['<b>Office</b>', 'Office &amp; Co', 'Office\tCo'])
      assertRefused(withAnswer('organisation', organisation), 'organisation', 'plain text');
    assertRefused(withAnswer('organisation', 'o'.repeat(101)), 'organisation', '100');
  });

  it('refuses a blank text and one with leading or trailing spaces', () => {
    for (const answer of ['name', 'organisation', 'domain.name', 'series.shortFormat']) {
      assertRefused(withAnswer(answer, '   '), answer, 'is blank', 'not blank');
      for (const text of [' Example', 'Example ', ' XX-${number} '])
        assertRefused(withAnswer(answer, text), answer, 'leading or trailing spaces');
    }
    assertAccepted(withAnswer('name', 'Example Land'));
  });

  it("refuses an untrimmed name once, not again in the domain's name made from it", () => {
    const problems = answerProblems(withAnswer('name', ' Example', minimal()));
    assert.deepEqual(
      problems.map((problem) => problem.answer),
      ['name'],
      show(problems),
    );
  });
});

describe('the languages', () => {
  it('refuses English, which is always there', () => {
    assertRefused(withAnswer('languages', ['da', 'en']), 'languages[1]', 'English');
  });

  it('refuses a code that is not 2-3 letters a-z, and a repeat', () => {
    for (const lang of ['EN', 'd', 'dans', 'd1'])
      assertRefused(withAnswer('languages', [lang]), 'languages[0]', '2-3');
    assertRefused(withAnswer('languages', ['da', 'kl', 'da']), 'languages[2]');
  });

  it('accepts other languages, and none', () => {
    assertAccepted(withAnswer('languages', ['da', 'kl']));
    assertAccepted(withAnswer('languages', ['fao']));
    assertAccepted(withAnswer('languages', []));
  });
});

describe('the producer code', () => {
  it('accepts four upper-case letters or digits, empty and absent', () => {
    for (const code of ['AB12', '0000', 'ZZZZ', ''])
      assertAccepted(withAnswer('producerCode', code));
    assertAccepted(withAnswer('producerCode', undefined));
  });

  it('refuses another shape', () => {
    for (const code of ['ab12', 'AB1', 'AB123', 'AB-1'])
      assertRefused(withAnswer('producerCode', code), 'producerCode', 'S-62');
  });

  it("refuses another authority's code and the guide's placeholder", () => {
    for (const code of ['DK00', 'DK01', 'QQ00'])
      assertRefused(withAnswer('producerCode', code), 'producerCode', code);
  });
});

describe('the time zone', () => {
  it('accepts the current IANA names that Node resolves to their old ones', () => {
    // Node 24's ICU resolves these to America/Godthab and Europe/Kiev, and
    // Intl.supportedValuesOf('timeZone') lists only the old names, while Java's tz database holds
    // the current ones: the validator must take what Intl takes, not what it lists.
    for (const timeZone of ['America/Nuuk', 'Europe/Kyiv'])
      assertAccepted(withAnswer('timeZone', timeZone));
  });

  it('accepts UTC, an Etc zone and names with three parts, digits and hyphens', () => {
    for (const timeZone of [
      'UTC',
      'Etc/GMT+1',
      'America/Argentina/Buenos_Aires',
      'America/Port-au-Prince',
      'Europe/London',
    ])
      assertAccepted(withAnswer('timeZone', timeZone));
  });

  it('refuses a spelling of the wrong case, which Java reads as GMT', () => {
    for (const timeZone of ['europe/london', 'Europe/LONDON', 'utc', 'EUROPE/London'])
      assertRefused(withAnswer('timeZone', timeZone), 'timeZone', /case/i);
  });

  it('refuses a zone Intl does not know, and a GMT offset', () => {
    for (const timeZone of ['Nowhere/Land', 'GMT+1', 'Europe/Atlantis', ''])
      assertRefused(withAnswer('timeZone', timeZone), 'timeZone');
  });

  it('keeps the spelling the person gave', () => {
    assert.equal(readAnswers(withAnswer('timeZone', 'Europe/Kyiv')).timeZone, 'Europe/Kyiv');
    assert.equal(readAnswers(withAnswer('timeZone', 'America/Nuuk')).timeZone, 'America/Nuuk');
  });
});

describe('the domain and the series', () => {
  const REFUSED = [
    'niord-client-master',
    'account',
    'account-console',
    'admin-cli',
    'broker',
    'realm-management',
    'security-admin-console',
    'niord-web',
  ];

  for (const answer of ['domain.id', 'series.id']) {
    it(`holds ${answer} to lower-case letters, digits and hyphens, 2 to 63 long`, () => {
      for (const id of ['x', 'Xx-nw', '1nw', 'a_b', '-nw', `a${'b'.repeat(63)}`])
        assertRefused(withAnswer(answer, id), answer, 'a-z');
      for (const id of ['xx', 'xx-nw', 'x1', `a${'b'.repeat(62)}`])
        assertAccepted(withAnswer(answer, id));
    });

    it(`refuses as ${answer} the base product's domain and Keycloak's own client ids`, () => {
      for (const id of REFUSED) assertRefused(withAnswer(answer, id), answer, id);
    });
  }

  it('accepts NW and NM as the main type, nothing else', () => {
    for (const mainType of ['NW', 'NM']) assertAccepted(withAnswer('domain.mainType', mainType));
    for (const mainType of ['nw', 'XX', ''])
      assertRefused(withAnswer('domain.mainType', mainType), 'domain.mainType', 'NM');
  });

  it('accepts the four number sequence types, nothing else', () => {
    for (const type of ['YEARLY', 'CONTINUOUS', 'MANUAL', 'NONE'])
      assertAccepted(withAnswer('series.numberSequenceType', type));
    for (const type of ['yearly', 'WEEKLY', ''])
      assertRefused(withAnswer('series.numberSequenceType', type), 'series.numberSequenceType');
  });
});

describe("the series' short format", () => {
  const TOKENS = [
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
  ];

  it("accepts each of the backend's tokens beside a number token", () => {
    for (const token of TOKENS)
      assertAccepted(withAnswer('series.shortFormat', `QQ-\${${token}}-\${number}`));
  });

  it('refuses an unknown token, naming it', () => {
    assertRefused(
      withAnswer('series.shortFormat', 'QQ-${foo}-${number}'),
      'series.shortFormat',
      '${foo}',
    );
    assertRefused(
      withAnswer('series.shortFormat', 'QQ-${}-${number}'),
      'series.shortFormat',
      '${}',
    );
  });

  it("refuses an unclosed '${'", () => {
    for (const format of ['QQ-${number', 'QQ-${number}-${year', 'QQ-${year${number}'])
      assertRefused(withAnswer('series.shortFormat', format), 'series.shortFormat', '${');
  });

  it('wants a number token unless the series is not numbered', () => {
    assertRefused(withAnswer('series.shortFormat', 'QQ-${year}'), 'series.shortFormat', 'NONE');
    const unnumbered = withAnswer('series.numberSequenceType', 'NONE');
    assertAccepted(withAnswer('series.shortFormat', 'XX-${year}', unnumbered));
    assertAccepted(withAnswer('series.shortFormat', 'QQ-${number}-${year}'));
  });

  it('refuses markup in the short format', () => {
    assertRefused(withAnswer('series.shortFormat', '<b>${number}</b>'), 'series.shortFormat');
  });
});

describe('the owners', () => {
  it('accepts a user, a team and a hyphenated name', () => {
    assertAccepted(withAnswer('owners', ['@user']));
    assertAccepted(withAnswer('owners', ['@org/team', '@a-b']));
  });

  it('refuses none at all', () => {
    assertRefused(withAnswer('owners', []), 'owners', /one/);
  });

  it('refuses what is not @user or @org/team, and a repeat', () => {
    for (const owner of ['user', '@', '@org/', 'a@b.c', '@-x'])
      assertRefused(withAnswer('owners', [owner]), 'owners[0]', '@');
    assertRefused(withAnswer('owners', ['@user', '@user']), 'owners[1]');
  });
});

describe('the map', () => {
  it('refuses a value outside its range', () => {
    for (const [answer, values] of [
      ['map.lat', [91, -91]],
      ['map.lon', [181, -181]],
      ['map.zoom', [0, 19]],
    ])
      for (const value of values) assertRefused(withAnswer(answer, value), answer);
    assertRefused(withAnswer('map.lat', 91), 'map.lat', '-90', '90');
    assertRefused(withAnswer('map.lon', -181), 'map.lon', '-180', '180');
    assertRefused(withAnswer('map.zoom', 19), 'map.zoom', '1', '18');
  });

  it('refuses a fraction and a text', () => {
    assertRefused(withAnswer('map.lat', 55.5), 'map.lat', 'whole');
    assertRefused(withAnswer('map.lat', '55'), 'map.lat', 'whole');
    assertRefused(withAnswer('map.zoom', 6.5), 'map.zoom', 'whole');
  });

  it('accepts the ranges’ ends', () => {
    for (const [answer, values] of [
      ['map.lat', [-90, 90]],
      ['map.lon', [-180, 180]],
      ['map.zoom', [1, 18]],
    ])
      for (const value of values) assertAccepted(withAnswer(answer, value));
  });

  it('refuses an unknown key of the map', () => {
    assertRefused(withAnswer('map.height', 3), 'map.height', 'zoom');
    assertRefused(withAnswer('map.x-y', 3), 'map["x-y"]', 'zoom');
  });
});

describe('what is not an answer', () => {
  it('takes no default language but English', () => {
    assert.equal(DEFAULT_LANGUAGE, 'en');
    assertAccepted(withAnswer('defaultLanguage', 'en'));
    assertRefused(withAnswer('defaultLanguage', 'da'), 'defaultLanguage', 'English', 'recipe');
    assert.equal(
      Object.hasOwn(readAnswers(withAnswer('defaultLanguage', 'en')), 'defaultLanguage'),
      false,
    );
  });

  it('refuses an unknown answer, naming it', () => {
    assertRefused(withAnswer('s124', false), 's124');
    assertRefused(withAnswer('registry', 'harbor.example'), 'registry');
  });
});

describe('the standard defaults', () => {
  it('complete the seven required answers', () => {
    assert.deepEqual(readAnswers(minimal()), {
      country: 'xx',
      name: 'Example Land',
      organisation: 'Example Maritime Office',
      languages: [],
      producerCode: '',
      timeZone: 'UTC',
      map: { lat: 10, lon: 20, zoom: 6 },
      domain: { id: 'xx-nw', name: 'Example Land Navigational Warnings', mainType: 'NW' },
      series: {
        id: 'xx-nw',
        shortFormat: 'XX-NW-${number-3-digits}-${year-2-digits}',
        numberSequenceType: 'YEARLY',
      },
      owners: ['@example'],
    });
  });

  it('follow a domain of notices to mariners', () => {
    const answers = readAnswers(withAnswer('domain', { mainType: 'NM' }, minimal()));
    assert.deepEqual(answers.domain, {
      id: 'xx-nm',
      name: 'Example Land Notices to Mariners',
      mainType: 'NM',
    });
    assert.equal(answers.series.id, 'xx-nm');
    assert.equal(answers.series.shortFormat, 'XX-NM-${number-3-digits}-${year-2-digits}');
    assert.deepEqual(standardDefaults(withAnswer('domain', { mainType: 'NM' }, minimal())).domain, {
      id: 'xx-nm',
      name: 'Example Land Notices to Mariners',
      mainType: 'NM',
    });
  });

  it('never replace an answered value', () => {
    assert.deepEqual(readAnswers(complete()), complete());
    const answers = readAnswers({
      ...minimal(),
      languages: ['da'],
      map: { lat: -5, lon: 7, zoom: 12 },
      domain: { name: 'Coastal Warnings' },
      series: { numberSequenceType: 'CONTINUOUS' },
    });
    assert.deepEqual(answers.languages, ['da']);
    assert.deepEqual(answers.map, { lat: -5, lon: 7, zoom: 12 });
    assert.deepEqual(answers.domain, { id: 'xx-nw', name: 'Coastal Warnings', mainType: 'NW' });
    assert.equal(answers.series.numberSequenceType, 'CONTINUOUS');
    assert.equal(answers.series.id, 'xx-nw');
  });

  it('give a complete object that reads as itself, in the fixed key order', () => {
    const once = readAnswers(minimal());
    assert.deepEqual(readAnswers(once), once);
    assert.deepEqual(Object.keys(once), [
      'country',
      'name',
      'organisation',
      'languages',
      'producerCode',
      'timeZone',
      'map',
      'domain',
      'series',
      'owners',
    ]);
    assert.deepEqual(Object.keys(once.map), ['lat', 'lon', 'zoom']);
    assert.deepEqual(Object.keys(once.domain), ['id', 'name', 'mainType']);
    assert.deepEqual(Object.keys(once.series), ['id', 'shortFormat', 'numberSequenceType']);
    // A complete object given in another key order comes out in the fixed one.
    const shuffled = Object.fromEntries(Object.entries(complete()).reverse());
    assert.deepEqual(Object.keys(readAnswers(shuffled)), Object.keys(once));
  });

  it('leave the input as it was, sharing nothing with it', () => {
    const input = { ...minimal(), languages: ['da'], domain: { mainType: 'NM' } };
    const before = structuredClone(input);
    const answers = readAnswers(input);
    assert.deepEqual(input, before);
    assert.notEqual(answers.languages, input.languages);
    assert.notEqual(answers.owners, input.owners);
    assert.notEqual(answers.map, input.map);
    assert.notEqual(answers.domain, input.domain);
  });
});

describe('readAnswers', () => {
  it('throws one error listing every problem, one line each', () => {
    const answers = {
      ...minimal(),
      country: 'DK',
      timeZone: 'europe/london',
      producerCode: 'QQ00',
      map: { lat: 91, lon: 20 },
    };
    const problems = answerProblems(answers);
    assert.deepEqual(
      problems.map((problem) => problem.answer).sort(),
      ['country', 'map.lat', 'producerCode', 'timeZone'],
      show(problems),
    );
    assert.throws(
      () => readAnswers(answers),
      (error) => {
        assert.equal(error.message, `the answers are refused:\n${show(problems)}`);
        return true;
      },
    );
  });
});
