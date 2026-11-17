import { Injectable, inject, signal, effect, DOCUMENT } from '@angular/core';
import { TranslocoService } from '@jsverse/transloco';
import { AppConfigService } from './app-config.service';
import { PreferencesService } from './preferences.service';

@Injectable({ providedIn: 'root' })
export class LanguageService {
  private readonly transloco = inject(TranslocoService);
  private readonly appConfig = inject(AppConfigService);
  private readonly document = inject(DOCUMENT);
  private readonly preferences = inject(PreferencesService);

  readonly language = signal<string>(this.resolveInitialLanguage());

  constructor() {
    this.transloco.setAvailableLangs(this.appConfig.languages());

    effect(() => {
      const lang = this.language();
      this.transloco.setActiveLang(lang);
      this.document.documentElement.lang = lang;
    });
  }

  /**
   * The reader's own choice, made on the language switcher: the one language that is remembered
   * for their next visit. Only a choice is: a site that remembered the language it happened to
   * open in would keep a returning reader on the default of the day they first came, whatever
   * the deployment has moved it to since.
   */
  setLanguage(lang: string): void {
    if (this.offered(lang)) {
      this.language.set(lang);
      this.preferences.setLanguage(lang);
    } else {
      this.language.set(this.defaultLanguage());
    }
  }

  /**
   * The language an address asks for — the `?lang=` an embedding site or an old link carries.
   * It is this visit's, not the reader's choice, so it is not remembered.
   */
  useLanguage(lang: string): void {
    this.language.set(this.offered(lang) ? lang : this.defaultLanguage());
  }

  /**
   * The address first, then the reader's remembered choice, then the deployment's default, each
   * taken only when the site offers it: an address naming a language the site does not serve
   * falls through to the reader's own choice rather than discarding it.
   */
  private resolveInitialLanguage(): string {
    const params = new URLSearchParams(this.document.defaultView?.location?.search ?? '');
    const candidates = [
      params.get('language'),
      params.get('lang'),
      this.preferences.language(),
      this.appConfig.defaultLanguage(),
    ];
    return candidates.find((lang): lang is string => this.offered(lang)) ?? this.defaultLanguage();
  }

  private offered(lang: string | null | undefined): lang is string {
    if (!lang) return false;
    const languages = this.appConfig.languages();
    return languages.length === 0 || languages.includes(lang);
  }

  /** The deployment's resolved default, which the settings guarantee is one it offers. */
  private defaultLanguage(): string {
    return this.appConfig.defaultLanguage() || this.appConfig.languages()[0] || 'en';
  }
}
