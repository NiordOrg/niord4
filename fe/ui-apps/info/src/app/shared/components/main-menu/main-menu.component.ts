import { Component, ElementRef, inject, computed, viewChild } from '@angular/core';
import { RouterLink, RouterLinkActive } from '@angular/router';
import { TranslocoPipe } from '@jsverse/transloco';
import { LanguageService } from '../../../core/services/language.service';
import { AppConfigService } from '../../../core/services/app-config.service';
import { Icon } from '../../icons/icon';
import { publishHeight } from '../../utils/publish-height';

@Component({
  selector: 'app-main-menu',
  imports: [RouterLink, RouterLinkActive, TranslocoPipe, Icon],
  // The host draws no box of its own: the bar is pinned against the whole page rather than
  // against a wrapper exactly its own height, and the ribbon stays outside the bar's stacking
  // context.
  host: { class: 'contents' },
  template: `
    <!-- A deployment that is not production names itself in a corner ribbon, so nobody mistakes
         a test site for the real one. -->
    @if (modeText(); as mt) {
      <div
        class="execution-mode fixed top-3 -left-6.25 z-9999 h-6.25 w-25 -rotate-45 bg-danger text-center text-xs leading-6.75 font-bold text-fg-on-primary uppercase shadow-niord-sm"
      >
        {{ mt }}
      </div>
    }
    <!--
      The items are a line of text, and a line that does not fit wraps: how wide the items are
      depends on the language and on the screen. The bar grows with a second row instead of
      running on under the filter bar, and publishes its height for what is placed below it.
    -->
    <nav
      #bar
      class="menu-bar app-shell-gutter border-b-3 border-chrome-active bg-chrome text-center leading-10 text-chrome-fg"
      [attr.aria-label]="'ARIA_MAIN_NAV' | transloco"
    >
      <!--
        The icon beside each label is decorative, so the label is the link's whole accessible
        name. Below 640 px it is hidden from the eye alone: sr-only, never the hidden utility,
        which is display:none and takes the name out of the accessibility tree with it.
      -->
      <a
        class="nav-item-states mx-2 my-1 inline-block cursor-pointer rounded-md px-2 leading-8 sm:mx-5"
        routerLink="/messages/details"
        routerLinkActive="bg-white/10 font-semibold"
        ariaCurrentWhenActive="page"
      >
        <app-icon name="lucideLayoutList" class="mr-1 align-middle" />
        <span class="sr-only sm:not-sr-only">{{ 'MENU_DETAILS' | transloco }}</span>
      </a>
      <a
        class="nav-item-states mx-2 my-1 inline-block cursor-pointer rounded-md px-2 leading-8 sm:mx-5"
        routerLink="/messages/table"
        routerLinkActive="bg-white/10 font-semibold"
        ariaCurrentWhenActive="page"
      >
        <app-icon name="lucideList" class="mr-1 align-middle" />
        <span class="sr-only sm:not-sr-only">{{ 'MENU_TABLE' | transloco }}</span>
      </a>
      <a
        class="nav-item-states mx-2 my-1 inline-block cursor-pointer rounded-md px-2 leading-8 sm:mx-5"
        routerLink="/messages/map"
        routerLinkActive="bg-white/10 font-semibold"
        ariaCurrentWhenActive="page"
      >
        <app-icon name="lucideGlobe" class="mr-1 align-middle" />
        <span class="sr-only sm:not-sr-only">{{ 'MENU_MAP' | transloco }}</span>
      </a>
      <a
        class="nav-item-states mx-2 my-1 inline-block cursor-pointer rounded-md px-2 leading-8 sm:mx-5"
        routerLink="/publications"
        routerLinkActive="bg-white/10 font-semibold"
        ariaCurrentWhenActive="page"
      >
        <app-icon name="lucideDownload" class="mr-1 align-middle" />
        <span class="sr-only sm:not-sr-only">{{ 'MENU_DOWNLOADS' | transloco }}</span>
      </a>
      <!--
        Each language is named in itself — Dansk, English, Kalaallisut, as the build's language
        configuration names it — and marked as that language, so a reader finds their own
        whatever the page is in, and a screen reader says it in the right voice. A language the
        build ships a flag for shows the flag with the name as its alternative text; one it ships
        none for shows the name. Which one the page is in is its pressed state, not the flag's
        brightness alone.
      -->
      <span class="mx-2 inline-block px-2 sm:mx-5">
        @for (lang of languages(); track lang) {
          @let name = languageName(lang);
          <button
            type="button"
            class="link-button is-quiet"
            [attr.lang]="lang"
            [attr.aria-pressed]="lang === currentLang()"
            (click)="setLanguage(lang)"
          >
            @if (flagSrc(lang); as src) {
              <img
                [src]="src"
                class="mr-2 inline-block h-4 w-auto align-middle opacity-50 sm:mr-4"
                [class.opacity-90]="lang === currentLang()"
                [alt]="name"
              />
            } @else {
              <span class="mr-2 align-middle sm:mr-4">{{ name }}</span>
            }
          </button>
        }
      </span>
    </nav>
  `,
})
export class MainMenuComponent {
  private readonly languageService = inject(LanguageService);
  private readonly appConfig = inject(AppConfigService);
  private readonly bar = viewChild.required<ElementRef<HTMLElement>>('bar');

  readonly languages = this.appConfig.languages;
  readonly currentLang = this.languageService.language;

  // Any deployment that is not production is named in the bar, so nobody mistakes a test
  // site for the real one. The value is the backend's `mode` web setting.
  readonly modeText = computed(() => {
    const mode = this.appConfig.executionMode().trim();
    return mode && mode.toLowerCase() !== 'production' ? mode.toUpperCase() : null;
  });

  constructor() {
    publishHeight(this.bar, '--niord-menu-bar-height');
  }

  flagSrc(lang: string): string | undefined {
    return this.appConfig.flagSrc(lang);
  }

  /** The language's name in itself, or its code for one the build names none for. */
  languageName(lang: string): string {
    return this.appConfig.languageName(lang) ?? lang;
  }

  setLanguage(lang: string): void {
    this.languageService.setLanguage(lang);
  }
}
