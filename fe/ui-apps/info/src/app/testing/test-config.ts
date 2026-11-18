import { computed, Provider, signal } from '@angular/core';
import { TranslocoService } from '@jsverse/transloco';
import { TranslocoTestingModule } from '@jsverse/transloco';
import { EMPTY, of } from 'rxjs';
import { NiordInfoConfig, NIORD_INFO_CONFIG } from '../niord-info.config';
import { Area, Message } from '../core/models/message.model';
import enTranslations from '../../../../../languages/en/i18n/info/en.json';

/**
 * The base product's specs need only English; `zz` is the second language a switch is tested
 * against. It is English with its vowels accented, so it is written in English's locale: a
 * locale is what `Intl` formats with, and `zz` is not one it knows. It has no flag, which is how
 * a language without one is shown.
 */
export const TEST_CONFIG: NiordInfoConfig = {
  languages: ['en', 'zz'],
  defaultLanguage: 'en',
  languageOptions: [
    { value: 'en', label: 'English', locale: 'en-GB', flagPath: 'assets/img/flags/en.png' },
    { value: 'zz', label: 'Pseudo-English', locale: 'en-GB' },
  ],
  timeZone: 'Europe/Copenhagen',
  executionMode: 'DEVELOPMENT',
  wmsLayer: false,
  rootAreas: [{ areaId: 'urn:mrn:iho:country:xa', latitude: 56.0, longitude: 11.0, zoomLevel: 7 }],
};

export function provideTestNiordConfig(overrides?: Partial<NiordInfoConfig>): Provider {
  return { provide: NIORD_INFO_CONFIG, useValue: { ...TEST_CONFIG, ...overrides } };
}

/** Each vowel and the accented letter `zz` writes for it. */
const ACCENTED: Readonly<Record<string, string>> = Object.fromEntries(
  [...'aeiouAEIOU'].map((vowel, index) => [vowel, 'áéíóúÁÉÍÓÚ'[index]]),
);

/** Placeholders, tags and entities, which a translation carries over unchanged. */
const UNTRANSLATED = /(\{\{\s*\w+\s*\}\}|<[^>]*>|&\w+;)/;

function accented(text: string): string {
  return text
    .split(UNTRANSLATED)
    .map((part, index) => (index % 2 ? part : part.replace(/[aeiouAEIOU]/g, (c) => ACCENTED[c])))
    .join('');
}

/**
 * One English value as `zz` writes it: accented and in brackets, so it is never the English
 * text, not even where that has no vowel. A month or weekday list is written entry by entry, so
 * it still names twelve months and seven days; a date pattern keeps its tokens and accents only
 * its quoted words, so it still renders a date.
 */
function pseudoLocalised(key: string, value: string): string {
  if (key.endsWith('_DATE_FORMAT')) {
    return value.replace(/'([^']+)'/g, (_, words: string) => `'${accented(words)}'`);
  }
  const entries = /^DATE_(MONTHS|DAYS)_/.test(key) ? value.split('|') : [value];
  return entries.map((entry) => `[${accented(entry)}]`).join('|');
}

/**
 * The English bundle as the en pack ships it, so a component test renders the words the site
 * renders; the loader fetches the same file at runtime and nothing in the application holds a
 * second copy. Beside it `zz`, made from it here and shipped by no build.
 */
export const TEST_TRANSLATIONS: Record<string, Record<string, string>> = {
  en: enTranslations,
  zz: Object.fromEntries(
    Object.entries(enTranslations).map(([key, value]) => [key, pseudoLocalised(key, value)]),
  ),
};

/**
 * The two bundles, each with the values `branding` gives its language laid over it — a
 * country's branding file, merged in as the loader merges it. A language `branding` names that
 * has no bundle here, a country's own in that country's spec, is English with its branding laid
 * over it, standing in for the complete bundle the build stages for it.
 */
export function getTranslocoTestingModule(
  branding: Readonly<Record<string, Readonly<Record<string, string>>>> = {},
) {
  const languages = [...new Set([...Object.keys(TEST_TRANSLATIONS), ...Object.keys(branding)])];
  return TranslocoTestingModule.forRoot({
    langs: Object.fromEntries(
      languages.map((lang) => [
        lang,
        { ...(TEST_TRANSLATIONS[lang] ?? TEST_TRANSLATIONS['en']), ...branding[lang] },
      ]),
    ),
    translocoConfig: {
      defaultLang: 'en',
      availableLangs: languages,
    },
  });
}

/**
 * The same two languages with no words in them yet — what a site looks like before its bundle
 * has arrived. `setTranslation(TEST_TRANSLATIONS[lang], lang)` is the arrival, and it notifies
 * every reader exactly as a finished load does. The `transloco` pipe re-renders on it as it does
 * in the application, which configures Transloco the same way.
 */
export function getEmptyTranslocoTestingModule() {
  return TranslocoTestingModule.forRoot({
    langs: { en: {}, zz: {} },
    translocoConfig: {
      defaultLang: 'en',
      availableLangs: ['en', 'zz'],
      reRenderOnLangChange: true,
    },
  });
}

/**
 * Provides a lightweight TranslocoService spy for tests that don't render
 * templates with `| transloco` pipe. For template-rendering tests, use
 * `getTranslocoTestingModule()` in the imports array instead.
 *
 * Every key echoes its own name, which is what an unloaded bundle does: a date renderer built
 * on it then has no month words and falls back to the numeric form rather than rendering a key
 * name. `selectTranslate` is the signal-shaped reader the date words arrive through, and
 * `events$` never emits, so nothing here depends on a bundle-arrival notification.
 */
export function provideTranslocoSpy(): Provider {
  return {
    provide: TranslocoService,
    useValue: {
      translate: vi.fn((k: string) => k),
      selectTranslate: vi.fn((k: string) => of(k)),
      setActiveLang: vi.fn(),
      setAvailableLangs: vi.fn(),
      selectTranslation: vi.fn(() => of({})),
      events$: EMPTY,
    },
  };
}

/** What a spec over one of the three message views seeds its store with. */
export interface FakeMessagesStoreSeed {
  messages?: Message[];
  areaHeadings?: Map<string, Area>;
  rootArea?: Area;
  failed?: boolean;
  loading?: boolean;
  empty?: boolean;
  canPrintList?: boolean;
  publicationId?: string;
}

/**
 * A stand-in for the route's `MessagesStore`, for a spec over one of the three views it feeds.
 * Every reader is a signal the spec can write, and every writer is a spy.
 */
export function fakeMessagesStore(seed?: FakeMessagesStoreSeed) {
  const messages = signal<Message[]>(seed?.messages ?? []);
  return {
    messages,
    messageIds: computed(() => messages().map((message) => message.id)),
    areaHeadings: signal(seed?.areaHeadings ?? new Map<string, Area>()),
    areaRoots: signal<Area[]>([]),
    rootArea: signal(seed?.rootArea),
    subAreas: signal<Area[]>([]),
    subAreaSelection: signal<ReadonlySet<string>>(new Set<string>()),
    mainTypes: signal({ NW: true, NM: true }),
    activeNow: signal(false),
    failed: signal(seed?.failed ?? false),
    loading: signal(seed?.loading ?? false),
    empty: signal(seed?.empty ?? false),
    canPrintList: signal(seed?.canPrintList ?? true),
    publicationId: signal<string | undefined>(seed?.publicationId),
    selectRootArea: vi.fn(),
    setMainTypes: vi.fn(),
    toggleActiveNow: vi.fn(),
    toggleSubArea: vi.fn(),
    printPdfUrl: vi.fn(() => ''),
  };
}
