import {
  MAIN_TYPES,
  MESSAGE_PART_TYPES,
  MESSAGE_REFERENCE_TYPES,
  MESSAGE_STATUSES,
  MESSAGE_TYPES,
  referenceLabelKey,
} from './message.model';
import { TEST_TRANSLATIONS } from '../../testing/test-config';

/**
 * The closed sets and the bundles have to agree in both directions: a value with no key would
 * render as its own name to a mariner, and a key with no value is a label nothing can reach —
 * which is what a backend enum growing looks like from here.
 */
describe('message model closed sets', () => {
  const bundles = Object.entries(TEST_TRANSLATIONS);

  const cases: { name: string; values: readonly string[]; prefix: string }[] = [
    { name: 'MAIN_TYPES', values: MAIN_TYPES, prefix: 'MAIN_TYPE_' },
    { name: 'MESSAGE_TYPES', values: MESSAGE_TYPES, prefix: 'TYPE_' },
    { name: 'MESSAGE_STATUSES', values: MESSAGE_STATUSES, prefix: 'STATUS_' },
    { name: 'MESSAGE_PART_TYPES', values: MESSAGE_PART_TYPES, prefix: 'PART_TYPE_' },
    { name: 'MESSAGE_REFERENCE_TYPES', values: MESSAGE_REFERENCE_TYPES, prefix: 'REF_' },
  ];

  for (const { name, values, prefix } of cases) {
    describe(name, () => {
      for (const [lang, bundle] of bundles) {
        it(`has a ${prefix}* value in ${lang}.json for every member`, () => {
          const missing = values.filter((value) => !bundle[prefix + value]);
          expect(missing).toEqual([]);
        });
      }

      it(`covers every ${prefix}* key the bundles carry`, () => {
        const keys = Object.keys(TEST_TRANSLATIONS['en']).filter((key) => key.startsWith(prefix));
        const unknown = keys.filter((key) => !values.includes(key.slice(prefix.length)));
        expect(unknown).toEqual([]);
      });
    });
  }
});

describe('referenceLabelKey()', () => {
  it('names the four kinds the site has a word for', () => {
    expect(referenceLabelKey('REPETITION')).toBe('REF_REPETITION');
    expect(referenceLabelKey('REPETITION_NEW_TIME')).toBe('REF_REPETITION_NEW_TIME');
    expect(referenceLabelKey('CANCELLATION')).toBe('REF_CANCELLATION');
    expect(referenceLabelKey('UPDATE')).toBe('REF_UPDATE');
  });

  // The backend sends REFERENCE, which has never had a label; the row shows the id alone.
  it('has nothing to say about a kind the bundles have no word for', () => {
    expect(referenceLabelKey('REFERENCE')).toBeUndefined();
    expect(referenceLabelKey(undefined)).toBeUndefined();
    expect(referenceLabelKey('')).toBeUndefined();
  });

  it('does not reach an inherited property', () => {
    expect(referenceLabelKey('toString')).toBeUndefined();
    expect(referenceLabelKey('constructor')).toBeUndefined();
  });
});
