import { describe, expect, it } from 'vitest';

import en from '../../../../../../../../../languages/en/i18n/admin/en.json';
import { seriesRuleKey } from './publication-errors';

/**
 * Every rule the series validator can refuse over has a sentence in both bundles.
 *
 * A refusal arrives as an id and a wire path. The id is the server's name for a
 * rule, so a screen that renders only the id tells an admin that something called
 * S-6 said no — which names nothing they can go and change. The sentence keyed per
 * rule is the reason; this walks the rule set rather than the templates, because
 * the failure is silent: a rule with no sentence still renders, as the generic
 * line, and looks like a rule that simply has nothing more to say.
 *
 * The ids are TRANSCRIBED rather than derived. Nothing on this side of the wire
 * can enumerate a Java validator, so a rule added there without a sentence here
 * can only be caught by somebody adding it to this list — and then the assertion
 * below is what refuses the half-done job.
 */

/**
 * The rules `SeriesValidator` reports on a field error.
 *
 * S-1 to S-25 are the series rules; D-7 is the desc rule it enforces alongside
 * them, and it is easy to miss precisely because it is not in the S range.
 */
const SERIES_RULES = [
  'S-1',
  'S-2',
  'S-3',
  'S-4',
  'S-5',
  'S-6',
  'S-7',
  'S-8',
  'S-9',
  'S-10',
  'S-11',
  'S-12',
  'S-13',
  'S-14',
  'S-15',
  'S-16',
  'S-17',
  'S-18',
  'S-19',
  // S-20 is the timezone half alone. The owner half became S-20a when the owner
  // stopped being optional, and S-20b is the share that names nobody — three
  // separate refusals a form has to be able to place, so three sentences.
  'S-20',
  'S-20a',
  'S-20b',
  'S-21',
  'S-22',
  'S-23',
  // The two rules a compilation is judged by: that it names a source series
  // exactly when it compiles one, and that the source is a query-backed series
  // other than itself and not a compilation in its own right.
  'S-24',
  'S-25',
  'D-7',
] as const;

/**
 * The criteria rules, which reach the same list through the criteria document.
 *
 * C-5 and C-10 are documented rules of the same set and are deliberately absent:
 * C-5 (no selection on message status) has no node kind to arrive as, so the
 * parser refuses it before the validator sees it, and C-10 says canonicalisation
 * normalises rather than rejects — it produces no violation at all. Neither can
 * reach a reader, and a sentence for a refusal that cannot happen is a string a
 * translator has to carry for nothing.
 */
const CRITERIA_RULES = ['C-1', 'C-2', 'C-3', 'C-4', 'C-6', 'C-7', 'C-8', 'C-9'] as const;

const ALL_RULES: readonly string[] = [...SERIES_RULES, ...CRITERIA_RULES];

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

function sentenceIn(bundle: object, key: string): string | undefined {
  const found = lookup(bundle as TranslationNode, key);
  return typeof found === 'string' && found.trim() !== '' ? found : undefined;
}

/** The ids the bundle actually carries a sentence for. */
function ruleIdsIn(bundle: object): string[] {
  const node = lookup(bundle as TranslationNode, 'admin.publications.rule');
  return node === undefined || typeof node === 'string' ? [] : Object.keys(node);
}

describe('publication rule sentences', () => {
  it('builds the key from the rule id', () => {
    expect(seriesRuleKey('S-6')).toBe('admin.publications.rule.S-6');
    expect(seriesRuleKey('C-4')).toBe('admin.publications.rule.C-4');
  });

  it('has an English sentence for every rule the validator can report', () => {
    const missing = ALL_RULES.filter((rule) => sentenceIn(en, seriesRuleKey(rule)) === undefined);
    expect(missing, `rules with no sentence in en.json:\n${missing.join('\n')}`).toEqual([]);
  });

  /**
   * And nothing beyond them.
   *
   * A sentence keyed on an id no validator reports is a sentence nobody will ever
   * read — usually a typo in the id, which reads as the rule simply having no
   * sentence and is otherwise invisible.
   */
  it('carries no sentence for a rule the validator does not report', () => {
    for (const [name, bundle] of [['en.json', en]] as const) {
      const extra = ruleIdsIn(bundle).filter((rule) => !ALL_RULES.includes(rule));
      expect(extra, `unreachable rule sentences in ${name}:\n${extra.join('\n')}`).toEqual([]);
    }
  });
});
