/** One language this build ships a UI bundle for. */
export interface LanguageOption {
  /** The code the bundle, the address and the backend name it by: `da`, `en`, `kl`. */
  readonly value: string;
  /**
   * The language's name in itself — Dansk, English, Kalaallisut — whatever the page is in, so a
   * reader looking for their own language finds it written the way they write it.
   */
  readonly label: string;
  /**
   * The BCP 47 locale the language is written in (fe-country-split-plan §4.3; a country's
   * manifest can set it, `locales`). The public site does not read it yet: its dates are formatted
   * from the DATE_* keys of the UI bundle and its numbers with fixed locales. It is reserved for
   * the place that formats by the reader's locale once one does, so the field is in the contract
   * and filled for every country already.
   */
  readonly locale: string;
  /** The flag image the build ships for it. Without one, the language is shown by its code. */
  readonly flagPath?: string;
}

/**
 * Language defaults for one country build. The backend web settings
 * (`infoSiteLanguages`, `infoDefaultLanguage`) override the list and the default at bootstrap;
 * this file is what the site runs on when the settings endpoint cannot be reached, and it alone
 * says which languages are possible at all. Deliberately smaller than the administration app's
 * `LanguageConfig`: an anonymous site has no model languages and no editors. A language's name
 * and flag are part of its entry here, so the shared code names no language.
 */
export interface LanguageConfig {
  /**
   * Every language this build ships a UI bundle for. A language with no entry is never offered,
   * whatever the settings ask for, and text in it is shown by its code.
   */
  readonly languages: readonly LanguageOption[];
  /** UI languages, in the order the language switcher renders them. */
  readonly siteLanguages: readonly string[];
  /** The language a first visit starts in. */
  readonly defaultLanguage: string;
  /**
   * The languages this build ships branding for (`assets/branding/<lang>.json`); no other
   * language's branding file is requested. Absent means every language, so a configuration
   * written before the field existed keeps working.
   */
  readonly brandingLanguages?: readonly string[];
}
