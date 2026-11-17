import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslocoService } from '@jsverse/transloco';
import { EMPTY, of } from 'rxjs';
import { MessageSourceComponent } from './message-source.component';
import {
  getEmptyTranslocoTestingModule,
  getTranslocoTestingModule,
  provideTestNiordConfig,
  TEST_TRANSLATIONS,
} from '../../../../testing/test-config';
import { Message } from '../../../../core/models/message.model';
import { LanguageService } from '../../../../core/services/language.service';

/** 1 January 2024 at 01:00 UTC: still 31 December in Halifax, already 1 January in Copenhagen. */
const NEW_YEAR_UTC = Date.parse('2024-01-01T01:00:00Z');
/** 15 March 2024 at 10:00 UTC, on no boundary at all — the untranslated-pattern case. */
const MID_MARCH_UTC = Date.parse('2024-03-15T10:00:00Z');

describe('MessageSourceComponent', () => {
  let fixture: ComponentFixture<MessageSourceComponent>;
  let component: MessageSourceComponent;

  /** The shipped bundles, so the spec renders the words the site renders. */
  function setUp(timeZone?: string) {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [MessageSourceComponent, getTranslocoTestingModule()],
      providers: [provideTestNiordConfig(timeZone ? { timeZone } : undefined)],
    });
    // The bundle is on the component's critical path, so it is loaded before the first render:
    // a component that renders no date at all would otherwise pass a zone assertion vacuously.
    TestBed.inject(TranslocoService).load('en').subscribe();
    fixture = TestBed.createComponent(MessageSourceComponent);
    component = fixture.componentInstance;
  }

  /** A service whose bundle has not arrived: every key echoes its own name. */
  function setUpWithoutBundle() {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [MessageSourceComponent],
      providers: [
        provideTestNiordConfig(),
        {
          provide: TranslocoService,
          useValue: {
            translate: vi.fn((k: string) => k),
            selectTranslate: vi.fn((k: string) => of(k)),
            setActiveLang: vi.fn(),
            setAvailableLangs: vi.fn(),
            events$: EMPTY,
          },
        },
      ],
    });
    fixture = TestBed.createComponent(MessageSourceComponent);
    component = fixture.componentInstance;
  }

  function render(msg: Message): string {
    fixture.componentRef.setInput('msg', msg);
    fixture.detectChanges();
    return component.sourceText();
  }

  beforeEach(() => setUp());

  it('should render source text', () => {
    expect(
      render({
        id: '1',
        mainType: 'NW',
        descs: [{ lang: 'en', source: 'Example Maritime Authority' }],
      }),
    ).toContain('Example Maritime Authority');
  });

  // The runner's clock is America/St_Johns, three and a half hours west of UTC, so a renderer
  // reading the browser's zone calls this instant 31 December 2023 on a site set to Copenhagen
  // too. The configured zone is what decides, and the two zones disagree about this very instant.
  it('renders the published date in the configured zone (east of UTC)', () => {
    setUp('Europe/Copenhagen');
    expect(render({ id: '1', mainType: 'NW', publishDateFrom: NEW_YEAR_UTC })).toBe(
      'Published 1 January 2024',
    );
  });

  it('renders the published date in the configured zone (west of UTC)', () => {
    setUp('America/Halifax');
    expect(render({ id: '1', mainType: 'NW', publishDateFrom: NEW_YEAR_UTC })).toBe(
      'Published 31 December 2023',
    );
  });

  it('keeps the source and appends the date after it', () => {
    setUp('Europe/Copenhagen');
    expect(
      render({
        id: '1',
        mainType: 'NW',
        publishDateFrom: NEW_YEAR_UTC,
        descs: [{ lang: 'en', source: 'Example Maritime Authority' }],
      }),
    ).toBe('Example Maritime Authority. Published 1 January 2024');
  });

  // A notice rendered ahead of its bundle — the common case on a slow link, where the messages
  // answer before the translations do. The card has to fill the date in by itself when the words
  // land; nothing re-renders it, and nothing asks it to.
  it('fills the published date in when the bundle arrives after the first render', () => {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [MessageSourceComponent, getEmptyTranslocoTestingModule()],
      providers: [provideTestNiordConfig({ timeZone: 'Europe/Copenhagen' })],
    });
    fixture = TestBed.createComponent(MessageSourceComponent);
    component = fixture.componentInstance;

    expect(render({ id: '1', mainType: 'NW', publishDateFrom: NEW_YEAR_UTC })).toBe('');

    TestBed.inject(TranslocoService).setTranslation(TEST_TRANSLATIONS['en'], 'en');
    expect(component.sourceText()).toBe('Published 1 January 2024');
  });

  // The word before the date is read the way the date's own words are, so a switch re-words the
  // whole line; one taken once would stay English under a date in the other language.
  it('re-words the whole line when the reader switches language', async () => {
    setUp('Europe/Copenhagen');
    TestBed.inject(TranslocoService).load('zz').subscribe();
    expect(render({ id: '1', mainType: 'NW', publishDateFrom: NEW_YEAR_UTC })).toBe(
      'Published 1 January 2024',
    );

    TestBed.inject(LanguageService).useLanguage('zz');
    TestBed.tick();
    await fixture.whenStable();
    expect(component.sourceText()).toBe('[Públíshéd] 1 [Jánúáry] 2024');
  });

  // The word is read as a signal of its own, not as a snapshot taken whenever the date around it
  // happens to change: a bundle replaced in place, as a late bundle replaces a stand-in, re-words
  // the line even where the date's own words stay the same.
  it('re-words the line when the bundle changes the word and not the date', () => {
    setUp('Europe/Copenhagen');
    expect(render({ id: '1', mainType: 'NW', publishDateFrom: NEW_YEAR_UTC })).toBe(
      'Published 1 January 2024',
    );

    TestBed.inject(TranslocoService).setTranslation({ FIELD_PUBLISHED: 'Issued' }, 'en');
    expect(component.sourceText()).toBe('Issued 1 January 2024');
  });

  it('should guard against untranslated format key', () => {
    setUpWithoutBundle();
    const rendered = render({
      id: '1',
      mainType: 'NW',
      publishDateFrom: MID_MARCH_UTC,
    });
    expect(rendered).not.toContain('SOURCE_DATE_FORMAT');
    expect(rendered).not.toContain('2024');
  });
});
