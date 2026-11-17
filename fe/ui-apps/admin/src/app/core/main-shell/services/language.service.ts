import { computed, effect, inject, Injectable, PLATFORM_ID, Signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { TranslocoService } from '@jsverse/transloco';
import { take } from 'rxjs';
import { LanguageSettingsService } from '../../i18n/language-settings.service';
import { DOCUMENT, isPlatformBrowser } from '@angular/common';

@Injectable({ providedIn: 'root' })
export class LanguageService {
  private readonly translocoService = inject(TranslocoService);
  private readonly platformId = inject(PLATFORM_ID);
  private readonly document = inject(DOCUMENT);
  private readonly langKey = 'niord.language';

  private readonly settings = inject(LanguageSettingsService);

  get uiLanguages() {
    return this.settings.uiLanguages();
  }
  get modelLanguages() {
    return this.settings.modelLanguages();
  }
  get defaultLanguage() {
    return this.settings.defaultLanguage();
  }

  /** Applies the backend configuration before consumers load localized data. */
  configure(): void {
    // Only the configured languages: every bundle the build ships is staged complete, so there
    // is no runtime fallback language beside them.
    const configured = [...this.uiLanguages, ...this.modelLanguages].map((l) => l.value);
    this.translocoService.setAvailableLangs([...new Set(configured)]);
    this.translocoService.setDefaultLang(this.defaultLanguage);
    this.translocoService.setActiveLang(this.resolveInitialLanguage());
  }

  // Derived from Transloco so the signal only reflects a language whose bundle has finished
  // loading. Reading it from an effect or template translation is therefore always safe.
  readonly selectedLanguage: Signal<string>;

  readonly activeLocale: Signal<string>;

  constructor() {
    // Apply the persisted language to Transloco BEFORE subscribing via toSignal.
    // langChanges$ is a BehaviorSubject and emits its current value synchronously on
    // subscription, so subscribing first would lock the signal to Transloco's bootstrap
    // default and discard the saved choice.
    this.translocoService.setActiveLang(this.resolveInitialLanguage());
    this.selectedLanguage = toSignal(this.translocoService.langChanges$, {
      requireSync: true,
    });
    this.activeLocale = computed(() => {
      const value = this.selectedLanguage();
      return this.uiLanguages.find((lang) => lang.value === value)?.locale ?? value;
    });

    // Keep <html lang> in sync with the active language so AT pronunciation, hyphenation and
    // browser-translate prompts key off the right language (WCAG 3.1.1). Reads the active value,
    // so it scales to any number of configured languages (runs at bootstrap + on each switch).
    effect(() => {
      this.document.documentElement.lang = this.selectedLanguage();
    });
  }

  selectLanguage(value: string): void {
    const exists = this.uiLanguages.some((language) => language.value === value);

    if (!exists || value === this.selectedLanguage()) {
      return;
    }

    // Load the bundle before flipping the active language. setActiveLang fires langChanges$
    // synchronously without awaiting any load, so any consumer that runs translate() in
    // response would otherwise read against a not-yet-loaded bundle.
    this.translocoService
      .load(value)
      .pipe(take(1))
      .subscribe({
        next: () => {
          this.translocoService.setActiveLang(value);
          this.saveLanguage(value);
        },
        error: (err) => {
          // A failed bundle load leaves the language unchanged. Don't swallow it
          // silently (typescript-guidelines): surface it so the no-op is diagnosable.
          console.error(`Failed to load i18n bundle for language "${value}"`, err);
        },
      });
  }

  private resolveInitialLanguage(): string {
    const savedLanguage = this.readSavedLanguage();
    const matchedLanguage = this.uiLanguages.find((language) => language.value === savedLanguage);

    return (
      matchedLanguage?.value ??
      this.uiLanguages.find((language) => language.value === this.defaultLanguage)?.value ??
      this.uiLanguages[0].value
    );
  }

  private readSavedLanguage(): string | null {
    if (!isPlatformBrowser(this.platformId)) {
      return null;
    }

    try {
      return localStorage.getItem(this.langKey);
    } catch {
      return null; // Ignore storage errors.
    }
  }

  private saveLanguage(value: string): void {
    if (!isPlatformBrowser(this.platformId)) {
      return;
    }

    try {
      localStorage.setItem(this.langKey, value);
    } catch {
      // Ignore storage errors. The selected language still applies for the current session.
    }
  }
}
