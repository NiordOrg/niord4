/*
 * Danish text in a picture: the rules the guard holds a picture's visible texts to (guard.ts). The
 * guides show the English interface only, so a Danish sentence or a Danish interface word in a
 * picture is an error (the owner's ruling of 2026-11-11), while a Danish name is not: Rønne Havn,
 * Bøgestrøm, a vessel's or a company's name, the base map's labels, a code. The rules are made to
 * tell the two apart:
 *
 * - a sentence: two or more different Danish function words in one text (og, på, af, ikke, ...),
 *   words no English text uses and a name rarely holds two of ("Havnen ved Kolby Kås" holds one);
 * - an interface word: a text that is, whole, the Danish value of a key of the web or message
 *   dictionary ("Kladde", "Verificér"), unless that value is English too (a code, "Status"), or a
 *   listed Danish interface word ("Opret", "Indstillinger"), in any case;
 * - a short sentence: two different Danish words of a short list in a text with no English stop
 *   word ("Skriv en kommentar her", "Vis på kortet").
 * A soft hyphen or a zero-width character inside a word hides nothing. Text drawn on a canvas (a
 * map's labels) cannot be read at all: the copy's rule keeps such data English instead, and a person
 * looks at every picture. The word lists end where the copy's data and dictionaries end: the
 * English interface shows no Danish interface word that is in neither.
 *
 * Measured on the screen audit's 185 captured pages (danish-audit, 2026-11-11); see the rig's
 * README. Pure functions without Playwright, so a spec can test them directly.
 */

/**
 * Danish function words that are no English word. Left out because English or a name uses them:
 * den, man, sin, op, alle, hans, mod, sig, for, i, en, de, over, under, by, et.
 */
export const DANISH_FUNCTION_WORDS: ReadonlySet<string> = new Set([
  'og',
  'på',
  'af',
  'ikke',
  'eller',
  'skal',
  'skulle',
  'bliver',
  'blive',
  'blev',
  'mellem',
  'efter',
  'samt',
  'uden',
  'denne',
  'dette',
  'disse',
  'også',
  'når',
  'være',
  'været',
  'til',
  'fra',
  'med',
  'ved',
  'som',
  'det',
  'er',
  'kan',
  'kunne',
  'har',
  'havde',
  'hvor',
  'hvis',
  'kun',
  'vil',
  'ville',
  'bør',
  'hos',
  'fordi',
  'nogle',
  'deres',
  'ud',
  'jf',
  'iht',
  'vedr',
]);

/**
 * Danish content words no English text and no name uses, in any case: a single one makes a text
 * Danish ('Fyr slukket', 'Geotekniske boringer', 'Indtil 1 January', 'VHF kanal 16' by its
 * neighbour below). Each is a whole word or, ending in `*`, a word's start. Found in the copy's
 * English texts by the second check (2026-11-11) and in the Danish values of its editors.
 */
export const DANISH_CONTENT_WORDS: readonly string[] = [
  'slukket',
  'savnet',
  'forsvundet',
  'tilføjet',
  'manuelt',
  'geotekniske',
  'boringer',
  'periode',
  'perioden',
  'inspektion',
  'lukning',
  'indtil',
  'dybde',
  'vanddybde',
  'mindre',
  'vinter',
  'telefon',
  'dato',
  'inoperativ',
  'platformen',
  'virtuel',
  'bestemmelser',
  'generelle',
  'pkt',
  'lokal',
  'afsluttet',
  'navigationsadvars*',
  'meddelelse*',
  'afventer',
  'godkendelse',
  'verificér',
  'verificer',
  'annullér',
  'annuller',
  'søg',
  'kyst',
  'bøje*',
  'lystønde*',
  'sømærke*',
  'dagsømærke*',
  'tilrådes',
  'udlagt',
  'udføres',
  'sejlads*',
  'skibsfart*',
  'hurtige',
  'formørkelse*',
  'blinkende',
  'oplæsning*',
  'farvandsoplæsning*',
  'mailliste*',
  'opdateringer',
  'ændringer',
  // The third check (2026-11-11): titles, subjects and positions' names of the copy's messages.
  'vrag',
  'kabelarbejde',
  'grundstødt',
  'usikker',
  'planlagt',
  'objekt',
  'observeret',
  'reduceret',
  'undervandshindring',
  'vedligeholdelses*',
  'topbetegnelse',
  'krydstopbetegnelse',
  'midtfarvands*',
  'overgangspunkt*',
  'sundets',
  // The fourth check (2026-11-11): words round 3 found in texts the guard passed.
  'signalmast',
  'trafiksignal',
  'skydeområde',
  'bropille',
  'havbrug',
  'bassin',
  'klapside',
  'sydvestkyst',
  'soemil',
  'isbilag*',
  // The fifth check (2026-11-11): words the guard passed in older versions of live messages (the
  // editor's History page: 'Tlf. 3915 7315', 'Kronløb N-lige, N-enden af Marmorkaj'), and the
  // local realm's group 'MAS-vagt' (vagt: watch, duty), which the Users drawer's Groups tab lists.
  // 'lige' and 'enden' also refuse a List of Lights name that holds them ('Skagen N-lige
  // indermole'): a spec allows such a name by its own allowDanish entry.
  'tlf',
  'lige',
  'enden',
  'vagt',
  // The sixth check (2026-11-11): older versions of published NMs ('Virtual AtoN oprettet',
  // 'Indsejlingen Randers Fjrod', 'Langelandsbæltet. Klapplads.').
  'oprettet',
  'indsejling*',
  'klapplads*',
  // The Publications writer (2026-11-12): a series' Danish name and naming patterns stood in the
  // settings drawer's fields and passed ('Ugentlig EfS', 'EfS uge ${week} - ${year}',
  // 'EfS-Uge-${week}-${year}.pdf'), and the copy's NM report prints 'Uge 36'.
  'ugentlig*',
  'uge',
];

/**
 * Danish words that are also part of names: a single one makes a text Danish only in lower case
 * and not after a capitalised word ('VHF kanal 16', 'grønne formørkelser'), while 'Odense Kanal',
 * 'Skagen Fyr', 'Skagen fyr', 'Kong Christian X's bro', 'Nyt Løb' and 'Hvide Sande' are names.
 */
export const DANISH_NAME_WORDS: readonly string[] = [
  'kanal',
  'fyr',
  'fyret',
  'bro',
  'havn',
  'nyt',
  'farvand*',
  'afmærk*',
  'grønne',
  'røde',
  'hvide',
  'gule',
  // The fifth check (2026-11-11): a position's name 'gul' alone in older versions of a live
  // message ('Gul Rende' would be a name).
  'gul',
  'grøn',
  'rød',
  'hvid',
];

/**
 * Danish interface words that are a whole text (a button, a menu item, a table cell): 'Gem' (save)
 * or 'Luk' (close) is Danish alone, but 'gem' or 'vis' inside an English sentence is English. They
 * count in any case ('kladde' under `text-transform: uppercase`), except a text of one to three
 * capitals, which is a code ('NY', 'JA') unless it is listed so. The words from 'Opret' on are
 * Danish interface words the copy's data does not hold (the third check, 2026-11-11), so the
 * dictionary index cannot know them.
 */
export const DANISH_WHOLE_TEXTS: ReadonlySet<string> = new Set([
  'Gem',
  'Gem ændringer',
  'Slet',
  'Luk',
  'Søg',
  'Ja',
  'Nej',
  'Tilføj',
  'Rediger',
  'Redigér',
  'Fortryd',
  'Annullér',
  'Annuller',
  'Kladde',
  'Verificér',
  'Udgiv',
  'Publicér',
  'Vis',
  'Skjul',
  'Tilbage',
  'Kopiér',
  'Ny',
  'Opret',
  'Fortsæt',
  'Næste',
  'Forrige',
  'Udskriv',
  'Indstillinger',
  'Annulleret',
  'Hent',
  'Vælg',
  'Valgte',
  'Bruger',
  'Brugere',
  'Kilde',
  'Kilder',
  'Udløbet',
  'Slettet',
  'Publiceret',
  'Verificeret',
  'Kladder',
  'Meddelelser',
  'Kort',
  'Liste',
  'Detaljer',
  'Område',
  'Områder',
  'Kategori',
  'Kategorier',
  'Søkort',
  'Mærkat',
  'Mærkater',
  'Log ind',
  'Log ud',
  'Sprog',
  'Hjælp',
  'Ryd',
  'Nulstil',
  'Afbryd',
  'Importér',
  'Eksportér',
  'Udgivet',
  'Aktiv',
  'Inaktiv',
  'Navn',
  'Beskrivelse',
  'Titel',
  'Emne',
  'Dato',
  'Bekræft',
  'Afvis',
  'Godkend',
  // The fourth check (2026-11-11): interface words round 3 found that the guard passed.
  'Afslut',
  'Åbn',
  'Omdøb',
  'Flyt',
  'Udvid',
  'Gem som',
  'Udgaver',
  'Indtast titel',
  'Zoom ind',
  'Zoom ud',
  'Fjern filter',
]);

/**
 * Danish words that make a short text Danish only together, and only where it holds no English
 * stop word: 'Skriv en kommentar her' (a placeholder), 'Vis på kortet' (a title). Each alone is
 * English ('her') or a name's ('Den Helder'); with the function words above, two different ones
 * in a text without 'the', 'of', ... are a Danish sentence.
 */
export const DANISH_SENTENCE_WORDS: ReadonlySet<string> = new Set([
  'her',
  'en',
  'et',
  'den',
  'vis',
  'skriv',
  'kommentar',
  'kortet',
  'listen',
  'ingen',
  'flere',
  'hvad',
  'jeg',
  'du',
  'vi',
  'din',
  'dit',
  'dine',
  'nye',
]);

/** English words that make a text English for the short-sentence rule above. */
const ENGLISH_STOP_WORDS: ReadonlySet<string> = new Set([
  'the',
  'a',
  'an',
  'of',
  'to',
  'in',
  'on',
  'for',
  'and',
  'or',
  'is',
  'are',
  'be',
  'by',
  'with',
  'at',
  'from',
  'as',
  'this',
  'that',
  'it',
  'will',
  'has',
  'have',
  'not',
  'no',
  'all',
  'new',
  'show',
  'your',
  'you',
  'here',
  'see',
]);

/**
 * Characters that are not seen but split a word for a word list: the soft hyphen ('Kla&shy;dde')
 * and the zero-width space, joiners and no-break space ('Verifi&#8203;cér').
 */
const INVISIBLE = /[\u00AD\u200B-\u200D\u2060\uFEFF]/g;

/** A text as the word lists compare it: NFC, invisible characters taken out. */
function visible(text: string): string {
  return text.normalize('NFC').replace(INVISIBLE, '');
}

/** A whole text's key in the case-insensitive sets. */
function folded(text: string): string {
  return text.toLocaleLowerCase('da');
}

/** A text of one to three capitals: a code, compared in its own case only ('NY', 'JA'). */
const CODE = /^\p{Lu}{1,3}$/u;

const WHOLE_TEXTS_FOLDED: ReadonlySet<string> = new Set([...DANISH_WHOLE_TEXTS].map(folded));

/** Whether a whole text is a listed Danish interface word, in any case (see DANISH_WHOLE_TEXTS). */
function isDanishWholeText(whole: string): boolean {
  if (CODE.test(whole)) return DANISH_WHOLE_TEXTS.has(whole);
  return WHOLE_TEXTS_FOLDED.has(folded(whole));
}

function wordMatcher(words: readonly string[]): RegExp {
  const alternatives = words.map((word) =>
    word.endsWith('*') ? `${word.slice(0, -1)}\\p{L}*` : word,
  );
  return new RegExp(`^(?:${alternatives.join('|')})$`, 'u');
}
const CONTENT_WORD = wordMatcher(DANISH_CONTENT_WORDS);
const NAME_WORD = wordMatcher(DANISH_NAME_WORDS);

/**
 * The Danish texts of the copy's data, for the guard: the dictionaries' Danish values that are no
 * English text (an interface word, refused when it is a whole text: "Kladde", "Verificér"), and
 * the Danish values of two or more words of every kind (refused when a text holds one, word for
 * word: "Bøje savnet og fyr slukket", "Aktive navigationsadvarsler - Grønland").
 */
export interface DictionaryIndex {
  /** The interface values, in lower case (normalised as a whole text is). */
  readonly danish: ReadonlySet<string>;
  readonly phrases?: ReadonlySet<string>;
  /** The phrases' first two words, so a text's word runs are looked up only where one may start. */
  readonly starts?: ReadonlySet<string>;
  readonly longest?: number;
}

/** A Danish value of the data, and whether it is an interface word (a dictionary's, a list's). */
export interface DanishRow {
  readonly en: string;
  readonly da: string;
  /** An interface text, refused as a whole text too (default true: a dictionary row). */
  readonly ui?: boolean;
}

/**
 * A text as the rules compare it: invisible characters taken out, whitespace collapsed, a trailing
 * colon or ellipsis dropped.
 */
function normalise(text: string): string {
  return visible(text)
    .replace(/\s+/g, ' ')
    .trim()
    .replace(/\s*(?::|…|\.\.\.)$/, '');
}

/** A text's words in lower case, punctuation and placeholders left out. */
function words(text: string): string[] {
  return (
    visible(text)
      .replace(/\$\{[^}]*\}|\{\{[^}]*\}\}|\{\d+\}/g, ' ')
      .toLowerCase()
      .match(/[\p{L}\p{N}]+/gu) ?? []
  );
}

/** A value made of names only: every word starts with a capital or a digit ('Rønne Havn'). */
function nameLike(text: string): boolean {
  return text
    .split(/\s+/)
    .filter((word) => /\p{L}/u.test(word))
    .every((word) => /^[^\p{L}]*[\p{Lu}\d]/u.test(word));
}

/**
 * The index of the data's Danish values. An interface value (`ui`) counts as a whole text when it
 * differs from its key's English value, is no English text of any row or of `english` (the base
 * product's bundle), in any case, has a lower-case letter (an upper-case value is a code) and no
 * placeholder; it is kept in lower case and found in any case. Any value of two or more words
 * counts as a phrase when, besides, it is not made of names only and its words are no English
 * row's.
 */
export function buildDictionaryIndex(
  rows: readonly DanishRow[],
  english: ReadonlySet<string>,
): DictionaryIndex {
  const englishValues = new Set([...english].map((text) => folded(normalise(text))));
  const englishPhrases = new Set([...english].map((text) => words(text).join(' ')));
  for (const row of rows) {
    englishValues.add(folded(normalise(row.en)));
    englishPhrases.add(words(row.en).join(' '));
  }
  const danish = new Set<string>();
  const phrases = new Set<string>();
  const starts = new Set<string>();
  let longest = 0;
  for (const row of rows) {
    const da = normalise(row.da);
    if (da.length < 3 || englishValues.has(folded(da))) continue;
    if (!/\p{Ll}/u.test(da)) continue;
    if ((row.ui ?? true) && !/\{\{|\$\{/.test(da)) danish.add(folded(da));
    const phrase = words(da);
    const key = phrase.join(' ');
    // Two words of eight letters or more: 'EfS a' would find 'in EfS, a protection zone'.
    if (phrase.length < 2 || phrase.length > 40 || key.length < 9) continue;
    if (nameLike(da) || englishPhrases.has(key)) continue;
    phrases.add(key);
    starts.add(`${phrase[0]} ${phrase[1]}`);
    longest = Math.max(longest, phrase.length);
  }
  return { danish, phrases, starts, longest };
}

/**
 * The first Danish phrase of the index a text holds, word for word, or null. Where the text writes
 * every word of it with a capital ('Aggersund Broen', "Kong Christian X's Bro"), it is a name
 * there.
 */
function heldPhrase(text: string, index: DictionaryIndex): string | null {
  const { phrases, starts, longest } = index;
  if (!phrases?.size || !starts || !longest) return null;
  const original = visible(text).match(/[\p{L}\p{N}]+/gu) ?? [];
  const all = original.map((word) => word.toLowerCase());
  for (let i = 0; i + 1 < all.length; i++) {
    if (!starts.has(`${all[i]} ${all[i + 1]}`)) continue;
    let key = all[i] ?? '';
    for (let j = i + 1; j < Math.min(all.length, i + longest); j++) {
      key = `${key} ${all[j]}`;
      if (!phrases.has(key)) continue;
      // A possessive's 's' ("Kong Christian X's Bro") is part of the name.
      const named = original
        .slice(i, j + 1)
        .every((word) => /^[\p{Lu}\d]/u.test(word) || word === 's');
      if (named) continue;
      return key;
    }
  }
  return null;
}

/**
 * A word that starts a name: a capital followed somewhere by a small letter ('Skagen', "X's"), not
 * an abbreviation ('VHF').
 */
const CAPITALISED = /^\p{Lu}.*\p{Ll}/u;

/** The first Danish content word of a text, or a name word in lower case not after a name. */
function contentWord(text: string): string | null {
  const tokens = visible(text).match(/[\p{L}\p{N}]+(?:['’][\p{L}]+)*/gu) ?? [];
  for (let i = 0; i < tokens.length; i++) {
    const token = tokens[i] ?? '';
    const lower = token.toLowerCase();
    if (CONTENT_WORD.test(lower)) return token;
    if (token === lower && NAME_WORD.test(lower) && !CAPITALISED.test(tokens[i - 1] ?? '')) {
      return token;
    }
  }
  return null;
}

/**
 * Why a text is Danish, or null when it is not: the Danish function words it holds (two or more
 * different ones), a short Danish sentence without an English stop word, a Danish date, a Danish
 * 'og', 'ved' or journal number, a Danish content word, a Danish interface word as a whole text,
 * the dictionary value it is (both in any case), or the Danish value of the data it holds. A soft
 * hyphen or a zero-width character inside a word does not hide it.
 */
export function danishReason(text: string, index?: DictionaryIndex): string | null {
  const nfc = visible(text);
  const all = nfc.toLowerCase().match(/\p{L}+/gu) ?? [];
  const functionWords = new Set(all.filter((word) => DANISH_FUNCTION_WORDS.has(word)));
  if (functionWords.size >= 2) return `Danish words ${[...functionWords].join(', ')}`;
  if (!all.some((word) => ENGLISH_STOP_WORDS.has(word))) {
    const sentence = new Set(
      all.filter((word) => DANISH_FUNCTION_WORDS.has(word) || DANISH_SENTENCE_WORDS.has(word)),
    );
    if (sentence.size >= 2) return `a Danish sentence (${[...sentence].join(', ')})`;
  }
  const date = DANISH_DATE.exec(nfc);
  if (date) return `a Danish date "${date[0].trim()}"`;
  const and = DANISH_AND.exec(nfc) ?? DANISH_AT.exec(nfc) ?? DANISH_JOURNAL.exec(nfc);
  if (and) return `Danish words "${and[0].trim()}"`;
  const word = contentWord(nfc);
  if (word) return `the Danish word "${word}"`;
  const whole = normalise(nfc);
  if (isDanishWholeText(whole)) return 'a Danish interface word';
  if (!CODE.test(whole) && index?.danish.has(folded(whole))) return 'a Danish dictionary value';
  const phrase = index ? heldPhrase(nfc, index) : null;
  if (phrase) return `the Danish text of the data "${phrase}"`;
  return null;
}

/**
 * Why a text speaks of one of the two topics the owner's standing ruling keeps out of the guides
 * (the pattern below names them), or null.
 */
export function forbiddenTopic(text: string): string | null {
  const match = /(?<![\p{L}\p{N}])(?:S-?124|SECOM)(?![\p{L}\p{N}])/iu.exec(text);
  return match ? `"${match[0]}"` : null;
}

// A Danish date: a Danish month name after a day ('5 Juni', '16 oktober'), or any month after a
// day with an ordinal dot ('7. juni', '30. september'). A name holds neither.
const DANISH_DATE =
  /(?:^|[^\p{L}\d])\d{1,2}(?:\.\s*(?:januar|februar|marts|april|maj|juni|juli|august|september|oktober|november|december)|\s+(?:januar|februar|marts|maj|juni|juli|oktober))(?![\p{L}])/iu;

// The Danish 'og' (and) between a number or a lower-case word and the next: '1) - 4) og 5) - 8)'.
// Between capitalised words it is a name's ('Bogense Havn og Marina'), and stays.
const DANISH_AND = /(?:^|[\s(])(?:\p{Ll}+|\d+\)?)\s+og\s+(?:\p{Ll}|\d)/u;

// The Danish 'ved' (at) after a number: 'signal mast 1 ved Ejstrup Strand' ('Havnen ved Kolby Kås'
// is a name); the Danish journal number 'J.nr.' (in English 'J. No.').
const DANISH_AT = /(?:^|\s)\d+\s+ved\s+\p{L}/u;
const DANISH_JOURNAL = /(?:^|[^\p{L}])J\.\s?nr\./u;

/** A Java properties file's keys and values (the backend's dictionary export). */
export function parseProperties(text: string): Record<string, string> {
  const result: Record<string, string> = {};
  const lines = text.split(/\r?\n/);
  for (let i = 0; i < lines.length; i++) {
    let line = lines[i] ?? '';
    if (/^\s*[#!]/.test(line) || line.trim() === '') continue;
    while (/(?:^|[^\\])(?:\\\\)*\\$/.test(line) && i + 1 < lines.length) {
      line = line.slice(0, -1) + (lines[++i] ?? '').replace(/^\s+/, '');
    }
    const match = /^\s*((?:\\.|[^:=\s\\])+)\s*[:=\s]\s*(.*)$/.exec(line);
    if (!match?.[1]) continue;
    const unescape = (value: string): string =>
      value.replace(/\\u([0-9a-fA-F]{4})|\\(.)/g, (_all, hex: string | undefined, char: string) => {
        if (hex) return String.fromCharCode(parseInt(hex, 16));
        return ({ n: '\n', t: '\t', r: '\r', f: '\f' } as Record<string, string>)[char] ?? char;
      });
    result[unescape(match[1])] = unescape(match[2] ?? '');
  }
  return result;
}
