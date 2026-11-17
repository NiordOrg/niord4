import { Injectable, inject, signal } from '@angular/core';
import { RootAreaSpec, NIORD_INFO_CONFIG } from '../../niord-info.config';

@Injectable({ providedIn: 'root' })
export class AppConfigService {
  private readonly config = inject(NIORD_INFO_CONFIG);

  // Seeded from the country build and overwritten once, by the settings initializer, before
  // the first component is created. Nothing else writes them.
  readonly languages = signal<string[]>(this.config.languages);
  readonly defaultLanguage = signal(this.config.defaultLanguage);
  readonly executionMode = signal(this.config.executionMode);
  /**
   * The deployment's OpenStreetMap tile address; unset leaves the map kit's default. The site
   * draws no OpenSeaMap, so it takes no seamark address.
   */
  readonly osmUrl = signal<string | undefined>(undefined);

  readonly timeZone = signal(this.config.timeZone);
  readonly wmsLayer = signal(this.config.wmsLayer);
  readonly rootAreaSpecs = signal<RootAreaSpec[]>(this.config.rootAreas);
  /** Fixed by the build: the languages it ships a bundle for, each with its name and flag. */
  private readonly languageOptions = new Map(
    this.config.languageOptions.map((option) => [option.value, option]),
  );
  /** Fixed by the build: the languages it ships branding for, or `undefined` for every one. */
  private readonly brandingLanguages = this.config.brandingLanguages;
  /** Fixed by the build: the footer and the map's credits link it. */
  readonly accessibilityStatement = this.config.accessibilityStatement;

  applyLanguages(languages: readonly string[], defaultLanguage: string): void {
    this.languages.set([...languages]);
    this.defaultLanguage.set(defaultLanguage);
  }

  applyExecutionMode(mode: string): void {
    this.executionMode.set(mode);
  }

  applyOsmUrl(url: string | undefined): void {
    this.osmUrl.set(url);
  }

  /**
   * Whether the build ships a branding file for a language. A build that names no branding
   * languages ships one for each; one that names an empty list ships none, so asking would be a
   * 404 on every load.
   */
  shipsBranding(lang: string): boolean {
    return this.brandingLanguages?.includes(lang) ?? true;
  }

  /**
   * The flag the build ships for a language, or `undefined` for one it ships none for — text
   * from the backend may be in any language, and a flag path made up from the code would be a
   * broken image. The caller shows the code instead.
   */
  flagSrc(lang: string): string | undefined {
    return this.languageOptions.get(lang)?.flagPath;
  }

  /**
   * A language's name in itself — Dansk, English, Kalaallisut — the same whatever the page is
   * in, or `undefined` for a language the build ships no bundle for.
   */
  languageName(lang: string): string | undefined {
    return this.languageOptions.get(lang)?.label;
  }
}
