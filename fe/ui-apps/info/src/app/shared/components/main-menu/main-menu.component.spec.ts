import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NO_ERRORS_SCHEMA } from '@angular/core';
import { provideRouter } from '@angular/router';
import { TranslocoService } from '@jsverse/transloco';
import { MainMenuComponent } from './main-menu.component';
import { AppConfigService } from '../../../core/services/app-config.service';
import {
  provideTestNiordConfig,
  getTranslocoTestingModule,
  TEST_CONFIG,
} from '../../../testing/test-config';
import { FakeResizeObserver } from '../../../testing/fake-resize-observer';

/** The four navigation links, each with the key that has to be its accessible name. */
const MENU = [
  ['/messages/details', 'MENU_DETAILS'],
  ['/messages/table', 'MENU_TABLE'],
  ['/messages/map', 'MENU_MAP'],
  ['/publications', 'MENU_DOWNLOADS'],
] as const;

/** The language buttons, each with the name a screen reader computes for it. */
function languageButtonsOf(fixture: ComponentFixture<MainMenuComponent>) {
  const buttons = Array.from(
    (fixture.nativeElement as HTMLElement).querySelectorAll('nav button'),
  ) as HTMLButtonElement[];
  return buttons.map((button) => ({
    // No aria-label, so the name is the flag's alternative text, or the text of a button with
    // no flag in it.
    ariaLabel: button.getAttribute('aria-label'),
    name: button.querySelector('img')?.getAttribute('alt') ?? button.textContent!.trim(),
    flag: button.querySelector('img')?.getAttribute('src') ?? null,
    lang: button.getAttribute('lang'),
    pressed: button.getAttribute('aria-pressed'),
  }));
}

describe('MainMenuComponent', () => {
  let component: MainMenuComponent;
  let fixture: ComponentFixture<MainMenuComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [MainMenuComponent, getTranslocoTestingModule()],
      providers: [provideTestNiordConfig(), provideRouter([])],
      schemas: [NO_ERRORS_SCHEMA],
    }).compileComponents();
    fixture = TestBed.createComponent(MainMenuComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should expose languages from config', () => {
    expect(component.languages()).toEqual(['en', 'zz']);
  });

  it('should expose current language', () => {
    expect(component.currentLang()).toBe('en');
  });

  it('should name the deployment mode the backend reported', () => {
    const appConfig = TestBed.inject(AppConfigService);
    for (const [mode, badge] of [
      ['development', 'DEVELOPMENT'],
      ['test', 'TEST'],
      ['staging', 'STAGING'],
    ]) {
      appConfig.applyExecutionMode(mode);
      expect(component.modeText()).toBe(badge);
    }
  });

  it('should show no badge on a production deployment or with no mode at all', () => {
    const appConfig = TestBed.inject(AppConfigService);
    for (const mode of ['production', 'PRODUCTION', '', '   ']) {
      appConfig.applyExecutionMode(mode);
      expect(component.modeText()).toBeNull();
    }
  });

  it('names every navigation link by its own menu text', () => {
    const transloco = TestBed.inject(TranslocoService);
    const links = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('nav a'),
    ) as HTMLAnchorElement[];

    expect(links).toHaveLength(MENU.length);
    for (const [index, [path, key]] of MENU.entries()) {
      // The icon is aria-hidden, so the label is the whole of the link's accessible name.
      expect(links[index].getAttribute('href')).toBe(path);
      expect(links[index].textContent!.trim()).toBe(transloco.translate(key));
    }
  });

  // The narrow viewport is where a hidden label would take the name with it: `hidden` is
  // `display: none`, which takes the label out of the accessibility tree as well as out of sight,
  // leaving a link with nothing but a decorative icon in it. `sr-only` hides it from the eye
  // alone. jsdom applies no stylesheet and computes no accessible name, so the class list is what
  // the assertion reaches.
  it('hides each label from the eye and never from a screen reader', () => {
    const labels = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('nav a span'),
    ) as HTMLElement[];

    expect(labels).toHaveLength(MENU.length);
    for (const label of labels) {
      const classes = label.className.split(/\s+/);
      expect(classes).toContain('sr-only');
      expect(classes).toContain('sm:not-sr-only');
      expect(classes).not.toContain('hidden');
    }
  });

  it('should delegate setLanguage to LanguageService', () => {
    component.setLanguage('zz');
    expect(component.currentLang()).toBe('zz');
  });

  function languageButtons() {
    return languageButtonsOf(fixture);
  }

  // A reader who does not read the page's language looks for their own by the way they write
  // it, and a screen reader says it in that language's voice; "Switch to da" gave neither.
  it('names each language in that language, and marks the button as that language', () => {
    expect(languageButtons()).toEqual([
      {
        ariaLabel: null,
        name: 'English',
        flag: 'assets/img/flags/en.png',
        lang: 'en',
        pressed: 'true',
      },
      {
        ariaLabel: null,
        name: 'Pseudo-English',
        flag: null,
        lang: 'zz',
        pressed: 'false',
      },
    ]);
  });

  // The flag of the active language is drawn brighter, which a screen reader cannot see and a
  // reader who cannot tell the two shades apart does not either.
  it('says which language the page is in by the pressed state, and moves it on a switch', () => {
    component.setLanguage('zz');
    TestBed.tick();
    expect(languageButtons().map((button) => button.pressed)).toEqual(['false', 'true']);
  });

  // Whether every control is on top is a question of layout, which jsdom does not do; the
  // layout check (npm run layout:info) answers it in a browser. What is asserted here
  // is the structure that answer rests on: a bar that grows instead of clipping, pinned by the
  // stylesheet rather than fixed, with a host that draws no box to pin it against.
  it('lets the bar grow with its items instead of holding them to one fixed row', () => {
    const host = fixture.nativeElement as HTMLElement;
    const nav = host.querySelector('nav')!;
    const classes = nav.className.split(/\s+/);

    expect(host.classList).toContain('contents');
    expect(classes).toContain('menu-bar');
    for (const pinned of ['fixed', 'h-10', 'max-w-3xl']) {
      expect(classes).not.toContain(pinned);
    }
    expect(nav.querySelector('.max-w-3xl')).toBeNull();
  });

  it('publishes the height of the bar for the filter bar and the scroll padding', () => {
    vi.stubGlobal('ResizeObserver', FakeResizeObserver);
    const root = document.documentElement;
    try {
      const measured = TestBed.createComponent(MainMenuComponent);
      TestBed.tick();
      // Two rows: the flags wrapped under the links.
      FakeResizeObserver.last!.report({ borderBoxSize: [{ blockSize: 80, inlineSize: 320 }] });
      expect(root.style.getPropertyValue('--niord-menu-bar-height')).toBe('80px');

      measured.destroy();
      expect(root.style.getPropertyValue('--niord-menu-bar-height')).toBe('');
    } finally {
      vi.unstubAllGlobals();
      FakeResizeObserver.last = undefined;
      root.style.removeProperty('--niord-menu-bar-height');
    }
  });
});

// A flag path is never made up from a language code: a language the build ships no flag for
// would be a broken image. Its button carries its name as text instead, and one the build names
// no label for, its code.
describe('MainMenuComponent — a language the build ships no flag for', () => {
  it('names the language in text, by its label or else its code, and draws no image', async () => {
    await TestBed.configureTestingModule({
      imports: [MainMenuComponent, getTranslocoTestingModule()],
      providers: [
        provideTestNiordConfig({
          languages: ['en', 'da', 'de'],
          languageOptions: [
            TEST_CONFIG.languageOptions[0],
            { value: 'da', label: 'Dansk', locale: 'da' },
          ],
        }),
        provideRouter([]),
      ],
      schemas: [NO_ERRORS_SCHEMA],
    }).compileComponents();
    const fixture = TestBed.createComponent(MainMenuComponent);
    fixture.detectChanges();

    expect(
      languageButtonsOf(fixture).map(({ name, flag, lang }) => ({ name, flag, lang })),
    ).toEqual([
      { name: 'English', flag: 'assets/img/flags/en.png', lang: 'en' },
      { name: 'Dansk', flag: null, lang: 'da' },
      { name: 'de', flag: null, lang: 'de' },
    ]);
  });
});
