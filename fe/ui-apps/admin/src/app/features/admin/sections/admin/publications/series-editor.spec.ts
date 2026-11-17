import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { TranslocoService } from '@jsverse/transloco';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { APP_CONFIG } from '../../../../../core/config/app-config.token';
import { DomainFacade } from '../../../../../core/domain/facades/domain.facade';
import type { Domain } from '../../../../../core/domain/models/domain.model';
import { LanguageService } from '../../../../../core/main-shell/services/language.service';
import { NotificationService } from '../../../../../core/notifications/notification.service';
import { provideNiordIcons } from '../../../../../shared/icons/niord-icons';
import type { MessageReport } from '../../../../../shared/models/print/message-report.model';
import { provideTestTransloco } from '../../../../../shared/testing/transloco-testing';
import { provideTestSessionZone, wallClockIn } from '../../../../../shared/testing/zone-testing';
import type {
  PublicationSeries,
  SeriesFieldError,
} from '../../../services/api/publication-series-api.service';
import { PublicationSeriesEditor, type SeriesEditorModel } from './series-editor';
import type { ReportParamRow, SeriesFormModel } from './series-rules';

@Component({
  imports: [PublicationSeriesEditor],
  template: `
    <app-publication-series-editor
      [model]="model()"
      (saved)="savedCount = savedCount + 1"
      (closed)="model.set(undefined)"
      (hidden)="hiddenCount = hiddenCount + 1"
    />
  `,
})
class Host {
  readonly model = signal<SeriesEditorModel | undefined>(undefined);
  savedCount = 0;
  hiddenCount = 0;
}

/** The protected surface the assertions reach for. */
interface EditorInternals {
  form: PublicationSeriesEditor['form'];
  value: () => SeriesFormModel;
  descs: () => Record<string, { name: string }>;
  languages: () => readonly string[];
  languageOptions: () => readonly { value: string; label: string; configured: boolean }[];
  ownerTimeZoneMissing: () => boolean;
  ownerDomainName: () => string;
  noSessionDomain: () => boolean;
  availableDomainIds: { (): readonly string[]; set: (value: readonly string[]) => void };
  availabilityIncomplete: () => boolean;
  seriesTimeZone: () => string | undefined;
  ownerKnown: () => boolean;
  firstIssueStartsAtValue: () => Date | null;
  setFirstIssueStartsAt: (value: Date | null) => void;
  nominalCutoffTimeValue: () => Date | null;
  setNominalCutoffTime: (value: Date | null) => void;
  reportMissing: () => boolean;
  changed: () => boolean;
  saveDisabled: () => boolean;
  markSeriesIdTyped: () => void;
  writable: () => boolean;
  otherDomainId: () => string | undefined;
  livenessForced: () => boolean;
  compilation: () => boolean;
  showCriteria: () => boolean;
  showFirstIssueStart: () => boolean;
  sourceSeriesOptions: () => readonly PublicationSeries[];
  showWeekday: () => boolean;
  showMonth: () => boolean;
  validation: () => readonly SeriesFieldError[] | undefined;
  validationErrors: () => readonly SeriesFieldError[];
  setField: <K extends keyof SeriesFormModel>(key: K, value: SeriesFormModel[K]) => void;
  setDescField: (lang: string, field: 'name', value: string) => void;
  toggleLanguage: (lang: string) => void;
  errorsFor: (field: string) => readonly SeriesFieldError[];
  validate: () => void;
  save: () => void;
  messagePublicationLocked: () => boolean;
  patternTokens: () => readonly { token: string; label: string }[];
  periodCutoffAllowed: () => boolean;
  cutoffDefaultBroken: () => boolean;
  isCutoffDefaultLocked: (option: string) => boolean;
  reservedParamKeys: () => readonly string[];
  reportParams: () => readonly ReportParamRow[];
  suggestedParamKeys: () => readonly string[];
  addSuggestedParam: (key: string) => void;
  addReportParam: () => void;
  setReportParam: (index: number, field: 'key' | 'value', event: Event) => void;
}

/** An input event carrying what somebody typed. */
function typed(value: string): Event {
  return { target: { value } } as unknown as Event;
}

/**
 * A day cell in the open calendar, by the number printed on it.
 *
 * Body-level: the editor is a drawer and the calendar a popover, so neither is
 * under the fixture's own element. The leading/trailing cells of the
 * neighbouring months carry the same numbers, hence the `is-outside` guard.
 */
function calendarDay(text: string): HTMLButtonElement | undefined {
  return Array.from(document.querySelectorAll<HTMLButtonElement>('.calendar-day')).find(
    (b) => b.textContent?.trim() === text && !b.classList.contains('is-outside'),
  );
}

const WEEKLY: PublicationSeries = {
  kind: 'SCHEDULED',
  seriesId: 'weekly-ntm',
  status: 'DRAFT',
  // S-19: the category column is NOT NULL, so a fixture without one is not a
  // saveable series and every save assertion below would be testing the gate
  // rather than the payload.
  categoryId: 'dk-dma-weekly-nm-publications',
  // S-20: the domain carries the timezone the cut-offs are read in, so a fixture
  // without one is not saveable and every payload assertion below would be
  // testing the gate rather than the payload.
  domainId: 'niord-nm',
  cadence: 'WEEKLY',
  contentMode: 'GENERATED_FROM_QUERY',
  timeRelation: 'PUBLISHED_IN_INTERVAL',
  aliveAtCutoff: false,
  nominalCutoffDay: 'WEDNESDAY',
  nominalCutoffTime: '12:00',
  languages: ['da', 'en'],
  descs: [
    { lang: 'da', name: 'Ugentlig EfS' },
    { lang: 'en', name: 'Weekly NtM' },
  ],
  criteria: {
    schemaVersion: 1,
    match: 'ALL',
    criteria: [{ kind: 'messageSeries', operator: 'IN', values: ['dma-nm'] }],
  },
};

const LANGUAGES = [
  { value: 'da', label: 'Dansk', locale: 'da', flagPath: '' },
  { value: 'en', label: 'English', locale: 'en-GB', flagPath: '' },
];

/** The series as it really is in production: query-backed, with the report that renders it. */
const WEEKLY_WITH_REPORT = { ...WEEKLY, reportId: 'nm-report' } as PublicationSeries;

/**
 * The third regime: a series that compiles the weekly's published issues.
 *
 * Query-backed like the weekly — it renders a document the same way — but with a
 * source series in place of the criteria and the liveness flag.
 */
const COMPILATION: PublicationSeries = {
  ...WEEKLY,
  seriesId: 'accumulated-yearly-ntm',
  timeRelation: 'COMPILED_FROM_SOURCE',
  sourceSeriesId: 'weekly-ntm',
  criteria: undefined,
  descs: [
    { lang: 'da', name: 'Akkumuleret Årlig EfS' },
    { lang: 'en', name: 'Accumulated Yearly NtM' },
  ],
};

/**
 * The estate the source picker reads, one row per thing the narrowing decides.
 *
 * An uploaded series has no member list to compile, a compilation cannot be
 * compiled again (S-25 forbids chains), and the series being edited cannot
 * compile itself.
 */
const SERIES_ESTATE: readonly PublicationSeries[] = [
  {
    ...WEEKLY,
    seriesId: 'in-force-list',
    domainId: 'niord-fe',
    timeRelation: 'IN_FORCE_AT_CUTOFF',
  },
  WEEKLY,
  { ...WEEKLY, seriesId: 'uploaded-annual', contentMode: 'UPLOADED_FILE' },
  COMPILATION,
];

const DOMAINS = [
  { domainId: 'niord-nm', name: 'Notices to Mariners', timeZone: 'Europe/Copenhagen' },
  // No timezone: a real shape in the estate, and the one this picker refuses.
  { domainId: 'niord-fe', name: 'Firing exercises' },
];

/**
 * The desk the admin is sitting at.
 *
 * `niord-nm` by default, which is the domain the fixture series belongs to: the
 * editor is read-only on a series another domain is accountable for, so every
 * test about saving, activating or retiring has to be sitting at the right desk
 * to be about what it says it is about.
 */
const sessionDomain = signal<Domain | undefined>({ domainId: 'niord-nm' } as Domain);

/** The domain catalogue the availability list is drawn from. */
const availableDomains = signal<readonly Domain[]>([
  { domainId: 'niord-nm', name: 'Notices to Mariners' } as Domain,
  { domainId: 'niord-fe', name: 'Firing exercises' } as Domain,
]);

function setup(
  domains: readonly unknown[] = [],
  tokens: readonly string[] = [],
  series: readonly PublicationSeries[] = [],
  reports: readonly MessageReport[] = [],
) {
  sessionDomain.set({ domainId: 'niord-nm' } as Domain);
  TestBed.configureTestingModule({
    providers: [
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
      provideHttpClient(),
      provideHttpClientTesting(),
      provideTestTransloco(),
      provideNiordIcons(),
      // The desk's own zone, deliberately NOT the owner's: every assertion below
      // about the first-interval start would pass in either zone if the two
      // agreed, and the field must follow the owner.
      provideTestSessionZone('America/Halifax'),
      // Stubbed rather than real: the editor's one domain read would otherwise
      // pull the AuthFacade → Keycloak chain into a spec about a form.
      {
        provide: DomainFacade,
        useValue: { sessionDomain, availableDomains } as unknown as DomainFacade,
      },
      {
        provide: LanguageService,
        useValue: {
          selectedLanguage: () => 'en',
          modelLanguages: LANGUAGES,
          // The date+time field formats through LocaleFormatService, which reads
          // this — absent, every render of the Cadence tab throws.
          activeLocale: signal('en-GB'),
        },
      },
      { provide: NotificationService, useValue: { success: vi.fn(), error: vi.fn() } },
    ],
  });
  const fixture = TestBed.createComponent(Host);
  fixture.detectChanges();
  const httpTesting = TestBed.inject(HttpTestingController);
  const lookupUrls = drainLookups(httpTesting, domains, tokens, series, reports);
  return { fixture, host: fixture.componentInstance, httpTesting, lookupUrls };
}

/**
 * Answer the option-list fetches with empty lists, and report what they asked for.
 *
 * They fire from field initializers, so they are in flight before any test has
 * said anything. Draining rather than asserting on them keeps each test about
 * the behaviour it names — and `verify()` in afterEach still fails if one is
 * left outstanding. The URLs come back WITH their query strings, so the tests
 * that are about which endpoint a picker reads, and on what terms, can say so.
 */
function drainLookups(
  httpTesting: HttpTestingController,
  domains: readonly unknown[] = [],
  tokens: readonly string[] = [],
  series: readonly PublicationSeries[] = [],
  reports: readonly MessageReport[] = [],
): string[] {
  const urls: string[] = [];
  for (const req of httpTesting.match(
    (r) =>
      r.method === 'GET' &&
      (r.url.includes('/message-reports/') ||
        r.url.includes('/name-tokens') ||
        r.url.includes('/publication-categories/all') ||
        r.url.includes('/publication-series/search-details') ||
        r.url.includes('/domains/all') ||
        r.url.includes('/message-series')),
  )) {
    urls.push(req.request.urlWithParams);
    if (req.request.url.includes('/domains/all')) {
      req.flush([...domains]);
    } else if (req.request.url.includes('/name-tokens')) {
      req.flush([...tokens]);
    } else if (req.request.url.includes('/publication-series/search-details')) {
      req.flush([...series]);
    } else if (req.request.url.includes('/message-reports/')) {
      req.flush([...reports]);
    } else {
      req.flush([]);
    }
  }
  return urls;
}

/** The drawer's pane — the element the slide-out runs on. */
function pane(): HTMLElement | null {
  return document.querySelector('.niord-drawer-panel');
}

/**
 * The end of the slide-out, as the browser reports it. Built as a plain Event
 * with the property bolted on: `TransitionEvent` is not implemented in the test
 * DOM, and the drawer's listener reads nothing else.
 */
function endSlideOut(element: HTMLElement): void {
  const event = new Event('transitionend');
  Object.defineProperty(event, 'propertyName', { value: 'transform' });
  element.dispatchEvent(event);
}

function open(
  fixture: ReturnType<typeof setup>['fixture'],
  host: Host,
  model: SeriesEditorModel,
  httpTesting: HttpTestingController,
  series: readonly PublicationSeries[] = [],
): EditorInternals {
  host.model.set(model);
  fixture.detectChanges();
  // The estate goes in here rather than in `setup`: it is read from the regime
  // on the form, so nothing asks for it until a compilation is the thing open.
  drainLookups(httpTesting, [], [], series);
  return fixture.debugElement.children[0].componentInstance as unknown as EditorInternals;
}

/**
 * Let a resource's answer arrive.
 *
 * A flush is synchronous and the value it produces is not: a resource resolves
 * on a microtask, so a test that asserts straight after the flush asserts the
 * state before the answer.
 */
async function settled(fixture: ReturnType<typeof setup>['fixture']): Promise<void> {
  await Promise.resolve();
  await Promise.resolve();
  fixture.detectChanges();
}

describe('PublicationSeriesEditor', () => {
  afterEach(() => {
    TestBed.inject(HttpTestingController).verify();
    document.querySelectorAll('.cdk-overlay-container').forEach((el) => el.replaceChildren());
  });

  /**
   * The pattern fields offer the vocabulary they accept.
   *
   * It is finite, nobody remembers it, and a writer who mistypes `${uge}` gets a
   * file name with a literal token in it. The token IS its own label, so what
   * the menu shows is what lands in the field.
   */
  it('offers the token vocabulary as an insert menu on the pattern fields', () => {
    const { fixture, host, httpTesting } = setup(DOMAINS, ['uge', 'år']);

    const editor = open(fixture, host, { mode: 'edit', series: WEEKLY }, httpTesting);
    fixture.detectChanges();

    expect(editor.patternTokens()).toEqual([
      { token: '${uge}', label: '${uge}' },
      { token: '${år}', label: '${år}' },
    ]);
    // The drawer portals to a body-level overlay, so the controls are not under
    // the fixture's own element.
    const inserts = [
      ...document.querySelectorAll<HTMLButtonElement>('.cdk-overlay-container button'),
    ].filter((b) => b.textContent?.includes('shared.forms.formatTokenInput.insert'));
    expect(inserts.length).toBeGreaterThan(0);
  });

  it('fills the report picker from the endpoint a plain admin may read', () => {
    // `/message-reports/all` is sysadmin-only and answers everyone else with a
    // 403, which left an admin with an empty picker and no way past the gate
    // that makes a report a precondition for activating a query-backed series.
    const { lookupUrls } = setup();

    expect(lookupUrls).toContain('/rest/message-reports/reports');
    expect(lookupUrls.some((url) => url.includes('/message-reports/all'))).toBe(false);
  });

  /**
   * The domain catalogue is read for a name and a timezone, and nothing else.
   *
   * The server's default is to compute each domain's Keycloak state, which it
   * does by asking Keycloak once per row — a third of a second the drawer would
   * spend on a field it never renders.
   */
  it('reads the domain catalogue without the per-domain Keycloak lookup', () => {
    const { lookupUrls } = setup();

    const domains = lookupUrls.find((url) => url.includes('/domains/all'));
    expect(domains).toContain('keycloakState=false');
  });

  /**
   * The schema's rules reach the controls that carry them.
   *
   * `required(categoryId)` and `required(domainId)` gated Save while no control
   * reflected them, so an admin who left the category blank got a dead button,
   * no marked field and no message until they pressed Check rules.
   */
  it('reflects the category rule onto its select, and offers no domain picker at all', () => {
    const { fixture, host, httpTesting } = setup(DOMAINS);

    open(fixture, host, { mode: 'add', series: { ...WEEKLY, seriesId: '' } }, httpTesting);
    fixture.detectChanges();

    // The drawer portals to a body-level overlay, so the controls are not under
    // the fixture's own element.
    const category = document.querySelector<HTMLSelectElement>('#series-category');
    expect(category).not.toBeNull();
    expect(category?.required).toBe(true);
    // The owner is the desk being worked at, and there is nothing to choose:
    // a picker here would offer to file the series somewhere the save refuses.
    expect(document.querySelector('#series-domain')).toBeNull();
  });

  /** A blank category is invalid, not merely un-saveable. */
  it('holds a blank category invalid through the form', () => {
    const { fixture, host, httpTesting } = setup(DOMAINS);

    const editor = open(fixture, host, { mode: 'edit', series: WEEKLY_WITH_REPORT }, httpTesting);
    editor.form.categoryId().value.set('');
    fixture.detectChanges();

    expect(editor.form().invalid()).toBe(true);
    expect(editor.saveDisabled()).toBe(true);
  });

  /**
   * The calendar bounds live in the schema, not in a native `min`/`max`.
   *
   * The attribute is a hint the browser draws and `form().invalid()` never sees,
   * so a month of 13 left Save live and the server did the refusing.
   */
  it('refuses a month outside 1-12 through the form gate', () => {
    const { fixture, host, httpTesting } = setup(DOMAINS);

    const editor = open(
      fixture,
      host,
      { mode: 'edit', series: { ...WEEKLY_WITH_REPORT, cadence: 'YEARLY' } },
      httpTesting,
    );
    editor.setField('nominalCutoffMonth', 13);
    fixture.detectChanges();
    expect(editor.form().invalid()).toBe(true);

    editor.setField('nominalCutoffMonth', 12);
    fixture.detectChanges();
    expect(editor.form().invalid()).toBe(false);
  });

  /**
   * A field-bound write clears the previous `/validate` answer.
   *
   * The reset used to hang off the hand-written setters, which a `[formField]`
   * control never calls — so a refusal stayed on screen under the very field
   * that had just been corrected.
   */
  it('drops a stale validate answer when a field-bound control is written', () => {
    const { fixture, host, httpTesting } = setup(DOMAINS);

    const editor = open(fixture, host, { mode: 'edit', series: WEEKLY_WITH_REPORT }, httpTesting);
    editor.validate();
    httpTesting
      .expectOne((r) => r.url.includes('/validate'))
      .flush([{ rule: 'S-19', field: 'categoryId', message: 'a series needs a category' }]);
    fixture.detectChanges();
    expect(editor.errorsFor('categoryId')).toHaveLength(1);

    editor.form.categoryId().value.set('dk-dma-public-publications');
    fixture.detectChanges();
    expect(editor.errorsFor('categoryId')).toHaveLength(0);
  });

  /**
   * The nominal cut-off time round-trips through the shared field.
   *
   * The native `<input type="time">` it replaces drew its popup in the BROWSER's
   * locale, so a Danish admin on an English browser read a 24-hour value on a
   * 12-hour clock.
   */
  it('reads and writes the cut-off time through the shared time field', () => {
    const { fixture, host, httpTesting } = setup(DOMAINS);

    const editor = open(fixture, host, { mode: 'edit', series: WEEKLY_WITH_REPORT }, httpTesting);
    const shown = editor.nominalCutoffTimeValue();
    expect(shown?.getHours()).toBe(12);
    expect(shown?.getMinutes()).toBe(0);

    editor.setNominalCutoffTime(new Date(2000, 0, 1, 9, 5));
    fixture.detectChanges();
    expect(editor.value().nominalCutoffTime).toBe('09:05');

    expect(document.querySelector('input[type="time"]')).toBeNull();
  });

  /**
   * A refusal reads as a sentence, never as the server's English prose.
   *
   * `/validate` answers with a rule id, a WIRE path and an English message. The
   * path is not a thing an admin has seen on the screen and the message is not
   * in their language, so the summary names the field the way the form does and
   * carries the rule id as the reference an operator quotes.
   */
  it('renders a validate refusal without the wire path or the server sentence', () => {
    const { fixture, host, httpTesting } = setup(DOMAINS);

    const editor = open(fixture, host, { mode: 'edit', series: WEEKLY_WITH_REPORT }, httpTesting);
    editor.validate();
    httpTesting
      .expectOne((r) => r.url.includes('/validate'))
      .flush([{ rule: 'S-6', field: 'descs.da.name', message: 'a yearly cadence needs a month' }]);
    fixture.detectChanges();

    const text = document.body.textContent ?? '';
    expect(text).toContain('admin.publications.series.ruleFailed');
    expect(text).not.toContain('descs.da.name');
    expect(text).not.toContain('a yearly cadence needs a month');
  });

  /**
   * And the refusal SAYS something, rather than only naming the rule.
   *
   * "Refused by rule S-21" tells an admin that something called S-21 said no,
   * which is not a thing they can go and change. The bundle carries the reason
   * per rule; the id stays beside it as the reference an operator quotes, and a
   * rule with no sentence yet still gets the generic line — with the id in it,
   * so it is never lost and never printed twice.
   */
  it('renders the rule sentence with the id beside it, and the generic line without one', () => {
    const { fixture, host, httpTesting } = setup(DOMAINS);
    TestBed.inject(TranslocoService).setTranslation(
      { 'admin.publications.rule.S-21': 'Only a yearly series is cut off at a period boundary.' },
      'en',
      { merge: true },
    );

    const editor = open(fixture, host, { mode: 'edit', series: WEEKLY_WITH_REPORT }, httpTesting);
    editor.validate();
    httpTesting
      .expectOne((r) => r.url.includes('/validate'))
      .flush([
        { rule: 'S-21', field: 'cutoffDefault', message: 'only a yearly series is cut off' },
        // No sentence for this one: the fallback still names the rule.
        { rule: 'Z-9', field: 'categoryId', message: 'something new the server grew' },
      ]);
    fixture.detectChanges();

    const text = document.body.textContent ?? '';
    expect(text).toContain('Only a yearly series is cut off at a period boundary. (S-21)');
    expect(text).toContain('admin.publications.series.ruleFailed');
    expect(text).not.toContain('admin.publications.rule.S-21');
    expect(text).not.toContain('admin.publications.rule.Z-9');
  });

  /**
   * EVERY field a rule can refuse over is named the way the form names it.
   *
   * The summary resolves the wire path to the label above the control. Four
   * shapes reached it without one and printed their unresolved key — a line
   * reading `admin.publications.series.field.status` beside an English sentence,
   * which looks like a broken translation rather than a rule about the status.
   * A criteria rule additionally names a position INSIDE the criteria document,
   * and the form has one criteria control, so the pointer says nothing here.
   */
  it('names the status, category, criteria and report-parameter fields', () => {
    const { fixture, host, httpTesting } = setup(DOMAINS);
    TestBed.inject(TranslocoService).setTranslation(
      {
        'admin.publications.series.field.status': 'Status',
        'admin.publications.series.field.categoryId': 'Category',
        'admin.publications.series.field.criteria': 'Criteria',
        'admin.publications.series.field.descs': 'Texts per language',
        'admin.publications.series.field.reportParams': 'Report parameters',
      },
      'en',
      { merge: true },
    );

    const editor = open(fixture, host, { mode: 'edit', series: WEEKLY_WITH_REPORT }, httpTesting);
    editor.validate();
    httpTesting
      .expectOne((r) => r.url.includes('/validate'))
      .flush([
        { rule: 'S-17', field: 'status', message: 'a series cannot be ACTIVE while rules fail' },
        { rule: 'S-19', field: 'categoryId', message: 'no such category' },
        { rule: 'S-12', field: 'descs', message: 'no desc row for configured language' },
        { rule: 'C-1', field: 'criteria/criteria/0/operator', message: 'operator is required' },
        { rule: 'S-23', field: 'reportParams.week', message: 'week is taken from the issue' },
      ]);
    fixture.detectChanges();

    const summary = [...document.querySelectorAll('[role="alert"]')].find((el) =>
      el.textContent?.includes('admin.publications.series.editor.rulesFail'),
    )!;
    const text = summary.textContent ?? '';
    expect(text).toContain('Status');
    expect(text).toContain('Category');
    expect(text).toContain('Texts per language');
    expect(text).toContain('Criteria');
    // The key somebody typed, which is the only thing that says WHICH parameter.
    expect(text).toContain('Report parameters (week)');
    // No unresolved key path, and no JSON pointer into the criteria document.
    expect(text).not.toContain('admin.publications.series.field.');
    expect(text).not.toContain('criteria/criteria/0/operator');
  });

  /** The per-language text is the admin kit's flagged field, not a raw language code. */
  it('renders the per-language name through the localized kit, with no raw code legend', () => {
    const { fixture, host, httpTesting } = setup(DOMAINS);

    open(fixture, host, { mode: 'edit', series: WEEKLY_WITH_REPORT }, httpTesting);
    fixture.detectChanges();

    expect(document.querySelectorAll('app-localized-text-input').length).toBeGreaterThan(0);
    expect(document.querySelector('#desc-name-da')).not.toBeNull();
    expect(
      [...document.querySelectorAll('legend')].some((l) => l.textContent?.trim() === 'da'),
    ).toBe(false);
  });

  it('locks the seriesId on an existing series and leaves it editable on a new one', () => {
    const { fixture, host, httpTesting } = setup();

    let editor = open(fixture, host, { mode: 'edit', series: WEEKLY }, httpTesting);
    expect(editor.form.seriesId().disabled()).toBe(true);

    editor = open(fixture, host, { mode: 'add', series: { ...WEEKLY, seriesId: '' } }, httpTesting);
    expect(editor.form.seriesId().disabled()).toBe(false);
    // Pristine and invalid (no id), so there is nothing to save yet.
    expect(editor.saveDisabled()).toBe(true);
  });

  it('will not save a series with no category', () => {
    // S-19. The column is NOT NULL, so leaving the dropdown alone used to buy a
    // 500 about a transient value. The gate says so before the server has to.
    const { fixture, host, httpTesting } = setup();
    const editor = open(
      fixture,
      host,
      { mode: 'edit', series: { ...WEEKLY, categoryId: undefined } },
      httpTesting,
    );

    editor.setField('cadence', 'MONTHLY');
    expect(editor.changed()).toBe(true);
    expect(editor.saveDisabled()).toBe(true);

    editor.setField('categoryId', 'dk-dma-publications');
    expect(editor.saveDisabled()).toBe(false);
  });

  it('will not save a series with no owner', () => {
    // The owner is the desk the series is listed and administered in, and the
    // timezone its cut-offs are read in comes from there and nowhere else. It is
    // no longer a picker -- there is nothing on this screen that can supply one,
    // which is exactly why the gate has to hold rather than leaving a save the
    // server refuses for a field the admin never saw.
    const { fixture, host, httpTesting } = setup();
    const editor = open(
      fixture,
      host,
      { mode: 'edit', series: { ...WEEKLY, domainId: '' } },
      httpTesting,
    );

    editor.setField('cadence', 'MONTHLY');
    expect(editor.changed()).toBe(true);
    expect(editor.saveDisabled()).toBe(true);
  });

  /**
   * A NEW series belongs to the desk it is created at.
   *
   * There is no picker — offering one would offer to file the series somewhere
   * the save refuses — so the owner is seeded from the session domain, and the
   * same seeding is what makes a COPY belong to whoever took it rather than to
   * the domain they took it from.
   */
  it('seeds a new series with the session domain as its owner', () => {
    const { fixture, host, httpTesting } = setup(DOMAINS);
    const editor = open(
      fixture,
      host,
      // The server's copy template carries the source's owner; the drawer takes
      // it over, because a copy is a new series belonging to this desk.
      { mode: 'add', series: { ...WEEKLY, seriesId: '', domainId: 'niord-annex' } },
      httpTesting,
    );

    expect(editor.value().domainId).toBe('niord-nm');
    expect(editor.noSessionDomain()).toBe(false);
  });

  /** With no desk there is nothing to create a series into, and Save says so. */
  it('refuses to create a series when the admin is sitting at no desk', () => {
    const { fixture, host, httpTesting } = setup(DOMAINS);
    // After the setup, which seeds the desk every other test sits at.
    sessionDomain.set(undefined);
    const editor = open(
      fixture,
      host,
      { mode: 'add', series: { ...WEEKLY, seriesId: '' } },
      httpTesting,
    );

    expect(editor.value().domainId).toBe('');
    expect(editor.noSessionDomain()).toBe(true);
    editor.setField('seriesId', 'brand-new');
    expect(editor.saveDisabled()).toBe(true);
  });

  /**
   * Availability follows the CONTENT MODE while a series is being created.
   *
   * A generated series is a desk's own product and starts closed; a link or an
   * uploaded document exists to be cited and starts open, which is what those
   * publications were before anybody had the choice.
   */
  it('re-applies the availability default when the content mode changes on create', () => {
    const { fixture, host, httpTesting } = setup(DOMAINS);
    const editor = open(
      fixture,
      host,
      { mode: 'add', series: { ...WEEKLY, seriesId: '' } },
      httpTesting,
    );

    expect(editor.value().availability).toBe('OWNER_ONLY');

    editor.setField('contentMode', 'EXTERNAL_LINK');
    expect(editor.value().availability).toBe('ALL_DOMAINS');

    editor.setField('contentMode', 'GENERATED_FROM_QUERY');
    expect(editor.value().availability).toBe('OWNER_ONLY');
  });

  /** On an existing series somebody chose the answer, so the content mode leaves it alone. */
  it('leaves a stored availability alone when the content mode changes on an edit', () => {
    const { fixture, host, httpTesting } = setup(DOMAINS);
    const editor = open(
      fixture,
      host,
      { mode: 'edit', series: { ...WEEKLY, availability: 'ALL_DOMAINS' } },
      httpTesting,
    );

    expect(editor.value().availability).toBe('ALL_DOMAINS');
    editor.setField('contentMode', 'GENERATED_FROM_QUERY');
    expect(editor.value().availability).toBe('ALL_DOMAINS');
  });

  /**
   * The sharing list travels, and never names the owner.
   *
   * The owner is already the owner; a stored list naming it would render as a
   * tickbox saying the desk has shared the series with itself.
   */
  it('sends the availability and its list, owner excluded', () => {
    const { fixture, host, httpTesting } = setup(DOMAINS);
    const editor = open(fixture, host, { mode: 'edit', series: WEEKLY }, httpTesting);

    editor.setField('availability', 'SELECTED_DOMAINS');
    editor.availableDomainIds.set(['niord-fe', 'niord-nm']);
    editor.save();

    const req = httpTesting.expectOne(
      (r) => r.method === 'PUT' && r.url === '/rest/publication-series/series/weekly-ntm',
    );
    const body = req.request.body as PublicationSeries;
    expect(body.domainId).toBe('niord-nm');
    expect(body.availability).toBe('SELECTED_DOMAINS');
    expect(body.availableDomainIds).toEqual(['niord-fe']);
    req.flush(WEEKLY);
  });

  /**
   * "Selected domains" with nothing selected is a hard rule, not a warning.
   *
   * The payload would send an empty list, which says what OWNER_ONLY says under
   * a name that claims otherwise — so the save gate refuses it, and the control
   * names it through the same predicate.
   */
  it('refuses to save selected-domains with nothing selected', () => {
    const { fixture, host, httpTesting } = setup(DOMAINS);
    const editor = open(fixture, host, { mode: 'edit', series: WEEKLY }, httpTesting);

    editor.setField('availability', 'SELECTED_DOMAINS');

    expect(editor.changed()).toBe(true);
    expect(editor.availabilityIncomplete()).toBe(true);
    expect(editor.saveDisabled()).toBe(true);

    editor.availableDomainIds.set(['niord-fe']);
    expect(editor.availabilityIncomplete()).toBe(false);
    expect(editor.saveDisabled()).toBe(false);
  });

  /** A share the catalogue no longer carries is still a share, so the gate opens. */
  it('accepts a lone share with a domain the catalogue has dropped', () => {
    const { fixture, host, httpTesting } = setup(DOMAINS);
    const editor = open(
      fixture,
      host,
      {
        mode: 'edit',
        series: { ...WEEKLY, availability: 'SELECTED_DOMAINS', availableDomainIds: ['niord-gone'] },
      },
      httpTesting,
    );

    expect(editor.availableDomainIds()).toEqual(['niord-gone']);
    expect(editor.availabilityIncomplete()).toBe(false);
  });

  /** Narrowing back to the desk clears the list rather than leaving a stale share. */
  it('sends an empty list when the series is narrowed to its own desk', () => {
    const { fixture, host, httpTesting } = setup(DOMAINS);
    const editor = open(
      fixture,
      host,
      {
        mode: 'edit',
        series: { ...WEEKLY, availability: 'SELECTED_DOMAINS', availableDomainIds: ['niord-fe'] },
      },
      httpTesting,
    );

    expect(editor.availableDomainIds()).toEqual(['niord-fe']);
    editor.setField('availability', 'OWNER_ONLY');
    editor.save();

    const req = httpTesting.expectOne(
      (r) => r.method === 'PUT' && r.url === '/rest/publication-series/series/weekly-ntm',
    );
    const body = req.request.body as PublicationSeries;
    expect(body.availability).toBe('OWNER_ONLY');
    expect(body.availableDomainIds).toEqual([]);
    req.flush(WEEKLY);
  });

  it('stops sending the weekday when the cadence stops being weekly', () => {
    const { fixture, host, httpTesting } = setup();
    const editor = open(fixture, host, { mode: 'edit', series: WEEKLY }, httpTesting);

    expect(editor.showWeekday()).toBe(true);
    expect(editor.showMonth()).toBe(false);

    editor.setField('cadence', 'YEARLY');
    editor.setField('nominalCutoffMonth', 12);
    editor.setField('nominalCutoffDayOfMonth', 31);
    expect(editor.showWeekday()).toBe(false);
    expect(editor.showMonth()).toBe(true);

    editor.save();
    const req = httpTesting.expectOne(
      (r) => r.method === 'PUT' && r.url === '/rest/publication-series/series/weekly-ntm',
    );
    const body = req.request.body as PublicationSeries;
    expect(body.nominalCutoffDay).toBeUndefined();
    expect(body.nominalCutoffMonth).toBe(12);
    expect(body.nominalCutoffDayOfMonth).toBe(31);
    req.flush(WEEKLY);
  });

  it('keeps the weekday the admin typed, so switching back restores it', () => {
    // The control is hidden, not cleared: a round trip through another cadence
    // must not silently discard work.
    const { fixture, host, httpTesting } = setup();
    const editor = open(fixture, host, { mode: 'edit', series: WEEKLY }, httpTesting);

    editor.setField('cadence', 'YEARLY');
    editor.setField('cadence', 'WEEKLY');
    expect(editor.value().nominalCutoffDay).toBe('WEDNESDAY');
  });

  it('forces the liveness flag on for an in-force series', () => {
    const { fixture, host, httpTesting } = setup();
    const editor = open(fixture, host, { mode: 'edit', series: WEEKLY }, httpTesting);

    expect(editor.livenessForced()).toBe(false);
    editor.setField('timeRelation', 'IN_FORCE_AT_CUTOFF');
    expect(editor.livenessForced()).toBe(true);

    editor.save();
    const req = httpTesting.expectOne((r) => r.method === 'PUT');
    // S-3: "in force at the cut-off" IS the liveness test; false would empty it.
    expect((req.request.body as PublicationSeries).aliveAtCutoff).toBe(true);
    req.flush(WEEKLY);
  });

  /**
   * Three regimes, each with its own line under the select.
   *
   * The names alone do not say what the third one does — "compiled from another
   * series" is a sentence about where the members come from, not about which
   * messages match — so the help line is part of the control rather than
   * documentation somewhere else.
   */
  it('offers all three regimes on the criteria tab, each with its own help line', () => {
    const { fixture, host, httpTesting } = setup(DOMAINS);
    const editor = open(fixture, host, { mode: 'edit', series: WEEKLY }, httpTesting);
    editor.setField('timeRelation', 'COMPILED_FROM_SOURCE');
    fixture.detectChanges();
    // The regime brings the source picker with it, and the picker's list with that.
    drainLookups(httpTesting, [], [], SERIES_ESTATE);

    const select = document.querySelector<HTMLSelectElement>('#series-time-relation');
    expect([...(select?.options ?? [])].map((option) => option.value)).toEqual([
      'PUBLISHED_IN_INTERVAL',
      'IN_FORCE_AT_CUTOFF',
      'COMPILED_FROM_SOURCE',
    ]);
    expect(document.body.textContent ?? '').toContain(
      'admin.publications.series.timeRelationHint.COMPILED_FROM_SOURCE',
    );
  });

  /**
   * The compilation's tab asks which publication, not which messages.
   *
   * There is no predicate to probe and no liveness to judge — each source issue
   * settled both at its own cut-off — so both controls come off the screen
   * rather than sitting there refusing to apply. The first-interval start stays:
   * a compilation tiles like an interval series.
   */
  it('swaps the liveness box and the criteria editor for a source picker on a compilation', async () => {
    const { fixture, host, httpTesting } = setup(DOMAINS);
    const editor = open(
      fixture,
      host,
      { mode: 'edit', series: COMPILATION },
      httpTesting,
      SERIES_ESTATE,
    );
    await settled(fixture);

    expect(editor.compilation()).toBe(true);
    expect(editor.showCriteria()).toBe(false);
    expect(editor.showFirstIssueStart()).toBe(true);
    expect(document.querySelector('#series-source')).not.toBeNull();
    expect(document.querySelector('app-series-criteria-editor')).toBeNull();

    // And back: the criteria editor returns, and the picker goes.
    editor.setField('timeRelation', 'PUBLISHED_IN_INTERVAL');
    fixture.detectChanges();
    expect(editor.showCriteria()).toBe(true);
    expect(document.querySelector('#series-source')).toBeNull();
    expect(document.querySelector('app-series-criteria-editor')).not.toBeNull();
  });

  /**
   * The picker offers what S-25 allows, this desk's own series first.
   *
   * An uploaded series has no member list to compile, a compilation cannot be
   * compiled again, and a series cannot compile itself. Narrowed here so the
   * list does not offer choices the save refuses; the server stays the
   * authority, because the list is a moment old.
   */
  it('offers only the series a compilation may name, the owner domain first', async () => {
    const { fixture, host, httpTesting } = setup(DOMAINS);
    const editor = open(
      fixture,
      host,
      { mode: 'edit', series: COMPILATION },
      httpTesting,
      SERIES_ESTATE,
    );
    await settled(fixture);

    expect(editor.sourceSeriesOptions().map((series) => series.seriesId)).toEqual([
      'weekly-ntm',
      'in-force-list',
    ]);
    const options = [...document.querySelectorAll<HTMLOptionElement>('#series-source option')];
    expect(options.map((option) => option.value)).toEqual(['', 'weekly-ntm', 'in-force-list']);
  });

  /**
   * The estate is read for the picker, and only for the picker.
   *
   * It is every active series in the editor shape — every configured language,
   * every criterion, every availability row — and one series in the estate is a
   * compilation. Read on every open of this drawer it would be an estate-wide
   * list fetched for a control almost nobody is shown, so the request is keyed
   * on the regime the form is on.
   */
  it('reads the estate only once the compiled regime is chosen', () => {
    const { fixture, host, httpTesting } = setup(DOMAINS);
    const editor = open(fixture, host, { mode: 'edit', series: WEEKLY }, httpTesting);

    httpTesting.expectNone((r) => r.url.includes('/publication-series/search-details'));

    editor.setField('timeRelation', 'COMPILED_FROM_SOURCE');
    fixture.detectChanges();

    const req = httpTesting.expectOne((r) => r.url.includes('/publication-series/search-details'));
    expect(req.request.params.get('status')).toBe('ACTIVE');
    req.flush([...SERIES_ESTATE]);
  });

  /** S-24 on the wire: the source travels, and the two settings it replaces do not. */
  it('sends the source series and neither criteria nor liveness for a compilation', () => {
    const { fixture, host, httpTesting } = setup(DOMAINS);
    const editor = open(fixture, host, { mode: 'edit', series: WEEKLY }, httpTesting);

    editor.setField('timeRelation', 'COMPILED_FROM_SOURCE');
    editor.setField('sourceSeriesId', 'weekly-ntm');
    fixture.detectChanges();
    drainLookups(httpTesting, [], [], SERIES_ESTATE);
    editor.save();

    const req = httpTesting.expectOne((r) => r.method === 'PUT');
    const body = req.request.body as PublicationSeries;
    expect(body.sourceSeriesId).toBe('weekly-ntm');
    expect(body.timeRelation).toBe('COMPILED_FROM_SOURCE');
    expect(body.criteria).toBeUndefined();
    expect(body.aliveAtCutoff).toBeUndefined();
    req.flush(WEEKLY);
  });

  /**
   * The server's answer about the source lands on the source field.
   *
   * S-24 and S-25 are judged where the series is saved, not here: the picker is
   * a moment old, and a source series can change regime or be retired while the
   * drawer is open. So the refusal has to read beside the control it is about.
   */
  it('renders a refusal about the source series beneath the picker', () => {
    const { fixture, host, httpTesting } = setup(DOMAINS);
    const editor = open(
      fixture,
      host,
      { mode: 'edit', series: COMPILATION },
      httpTesting,
      SERIES_ESTATE,
    );

    editor.validate();
    httpTesting
      .expectOne((r) => r.url.includes('/validate'))
      .flush([
        { rule: 'S-25', field: 'sourceSeriesId', message: 'the source is itself a compilation' },
      ]);
    fixture.detectChanges();

    expect(editor.errorsFor('sourceSeriesId').map((error) => error.rule)).toEqual(['S-25']);
    // The rendered line is the rule's own sentence where the bundle has one, and
    // the generic line carrying the id where it does not — which is what the
    // stub loader leaves this spec with.
    const picker = document.querySelector('#series-source')?.parentElement;
    expect(picker?.textContent ?? '').toContain('admin.publications.series.ruleFailed');
    expect(picker?.textContent ?? '').not.toContain('the source is itself a compilation');
  });

  it('posts to create rather than update when the series is new', () => {
    const { fixture, host, httpTesting } = setup();
    const editor = open(
      fixture,
      host,
      { mode: 'add', series: { ...WEEKLY, seriesId: '' } },
      httpTesting,
    );

    editor.setField('seriesId', 'brand-new');
    editor.save();

    const req = httpTesting.expectOne(
      (r) => r.method === 'POST' && r.url === '/rest/publication-series/series/',
    );
    expect((req.request.body as PublicationSeries).seriesId).toBe('brand-new');
    req.flush(WEEKLY);
    expect(host.savedCount).toBe(1);
  });

  /**
   * PUBCAT2. The id is the last segment of every address that reads or changes
   * the series, and the server's firewall refuses one that holds a slash, a
   * semicolon and the like. A series created under it could never be opened,
   * activated or deleted, so the editor holds it back and says why.
   */
  it('will not create a series under an id the server could not serve, and says why', () => {
    const { fixture, host, httpTesting } = setup();
    const editor = open(
      fixture,
      host,
      { mode: 'add', series: { ...WEEKLY, seriesId: '' } },
      httpTesting,
    );

    editor.markSeriesIdTyped();
    editor.setField('seriesId', 'nm/2026');
    fixture.detectChanges();
    expect(editor.saveDisabled()).toBe(true);
    const error = document.querySelector('#series-id-error');
    expect(error?.textContent?.trim()).toBe('admin.publications.error.idUnservable');
    expect(document.querySelector('#series-id')?.getAttribute('aria-describedby')).toBe(
      'series-id-error',
    );
    editor.save();
    httpTesting.expectNone((r) => r.method === 'POST');

    for (const id of ['nm;2026', 'nm%2F2026', 'nm\\2026', '..']) {
      editor.setField('seriesId', id);
      expect(editor.saveDisabled(), id).toBe(true);
    }

    editor.setField('seriesId', 'nm-2026');
    fixture.detectChanges();
    expect(editor.saveDisabled()).toBe(false);
    expect(document.querySelector('#series-id-error')).toBeNull();
  });

  /** ERR7. A name goes into a varchar(255) column; a longer one used to answer 500. */
  it('will not save a name longer than its column, and counts under each name', () => {
    const { fixture, host, httpTesting } = setup();
    const editor = open(fixture, host, { mode: 'edit', series: WEEKLY }, httpTesting);
    fixture.detectChanges();
    expect(document.querySelectorAll('app-length-counter p')).toHaveLength(2);

    editor.setDescField('en', 'name', 'a'.repeat(256));
    fixture.detectChanges();
    expect(editor.saveDisabled()).toBe(true);
    expect(document.querySelector('app-length-counter p[role="alert"]')).not.toBeNull();
    editor.save();
    httpTesting.expectNone((r) => r.method === 'PUT');

    editor.setDescField('en', 'name', 'a'.repeat(255));
    fixture.detectChanges();
    expect(editor.saveDisabled()).toBe(false);
    expect(document.querySelector('app-length-counter p[role="alert"]')).toBeNull();
  });

  it('validates the edit in the form, not the series as stored', () => {
    const { fixture, host, httpTesting } = setup();
    const editor = open(fixture, host, { mode: 'edit', series: WEEKLY }, httpTesting);

    editor.setField('cadence', 'YEARLY');
    editor.validate();

    const req = httpTesting.expectOne(
      (r) => r.method === 'POST' && r.url === '/rest/publication-series/validate',
    );
    // The whole point of the endpoint: the body carries the unsaved change.
    expect((req.request.body as PublicationSeries).cadence).toBe('YEARLY');

    const errors: SeriesFieldError[] = [
      { rule: 'S-6', field: 'nominalCutoffMonth', message: 'a yearly cadence needs a month' },
    ];
    req.flush(errors);

    expect(editor.validationErrors()).toEqual(errors);
    expect(editor.errorsFor('nominalCutoffMonth')).toHaveLength(1);
    expect(editor.errorsFor('cadence')).toHaveLength(0);
  });

  it('clears the last verdict as soon as the form changes again', () => {
    // A green tick left over from before the edit is worse than no tick: it
    // reports on a series that no longer exists on screen.
    const { fixture, host, httpTesting } = setup();
    const editor = open(fixture, host, { mode: 'edit', series: WEEKLY }, httpTesting);

    editor.validate();
    httpTesting.expectOne((r) => r.url === '/rest/publication-series/validate').flush([]);
    expect(editor.validationErrors()).toHaveLength(0);

    editor.setField('cadence', 'MONTHLY');
    expect(editor.validationErrors()).toHaveLength(0);
    expect(editor.validation()).toBeUndefined();
  });

  // ------------------------------------------------------ the shape of it

  /**
   * FOUR TABS. Automation held one setting whose second value cannot be chosen
   * yet — a tab that costs a click to read a sentence.
   */
  it('offers four tabs, with automation folded away', () => {
    const { fixture, host, httpTesting } = setup();

    open(fixture, host, { mode: 'edit', series: WEEKLY }, httpTesting);
    fixture.detectChanges();

    const tabs = [...document.querySelectorAll('.cdk-overlay-container [role="tab"]')].map((tab) =>
      tab.textContent?.trim(),
    );
    expect(tabs).toEqual([
      'admin.publications.series.tab.basics',
      'admin.publications.series.tab.content',
      'admin.publications.series.tab.cadence',
      'admin.publications.series.tab.criteria',
    ]);
  });

  /** The setting is still on the screen, and still on the wire — just read-only. */
  it('shows the release mode as a read-only line and keeps sending it', () => {
    const { fixture, host, httpTesting } = setup();
    const editor = open(fixture, host, { mode: 'edit', series: WEEKLY }, httpTesting);
    fixture.detectChanges();

    const line = document.querySelector('[data-testid="series-release-mode"]');
    expect(line).not.toBeNull();
    expect(line?.textContent).toContain('admin.publications.series.releaseMode.MANUAL_GATE');
    // Nothing to choose between: there is no control, only the answer.
    expect(line?.querySelector('input')).toBeNull();
    expect(line?.querySelector('select')).toBeNull();

    editor.setField('cadence', 'MONTHLY');
    editor.save();
    const req = httpTesting.expectOne((r) => r.method === 'PUT');
    expect((req.request.body as PublicationSeries).releaseMode).toBe('MANUAL_GATE');
    req.flush(WEEKLY);
  });

  /**
   * A cadence IS the automation, so there is nothing left to choose.
   *
   * The control was a switch that turned the schedule off without saying so: a
   * weekly series whose successor nobody opens looks exactly like one that has
   * fallen behind, and the only record of the difference was a select three tabs
   * into this drawer.
   */
  it('states the next issue as derived on a cadenced series, and sends it that way', () => {
    const { fixture, host, httpTesting } = setup();
    const editor = open(fixture, host, { mode: 'edit', series: WEEKLY }, httpTesting);
    fixture.detectChanges();

    const line = document.querySelector('[data-testid="series-next-issue-derived"]');
    expect(line).not.toBeNull();
    expect(line?.textContent).toContain(
      'admin.publications.series.nextIssueCreation.AUTO_ON_PUBLISH',
    );
    expect(line?.textContent).toContain('admin.publications.series.hint.nextIssueDerived');
    // No control at all, not a locked one.
    expect(document.querySelector('#series-next-issue')).toBeNull();

    // The stored value is what an imported series and the new-series template
    // both arrive with, and it heals on the way out rather than being refused.
    editor.setField('nextIssueCreation', 'MANUAL');
    editor.save();
    const req = httpTesting.expectOne((r) => r.method === 'PUT');
    expect((req.request.body as PublicationSeries).nextIssueCreation).toBe('AUTO_ON_PUBLISH');
    req.flush(WEEKLY);
  });

  /**
   * …and a publication with no cadence states the other answer, also read-only.
   *
   * S-8 decides this field from the cadence in both directions, so a control
   * here could only ever offer the one answer the server refuses. It used to be
   * a select locked on its single legal value, which reads as a choice somebody
   * took away rather than as a consequence of having no frequency.
   */
  it('states the next issue as made by hand on a cadence-less publication', () => {
    const { fixture, host, httpTesting } = setup();
    const editor = open(fixture, host, { mode: 'edit', series: WEEKLY }, httpTesting);

    editor.setField('cadence', 'NONE');
    fixture.detectChanges();

    expect(document.querySelector('[data-testid="series-next-issue-derived"]')).toBeNull();
    const line = document.querySelector('[data-testid="series-next-issue-by-hand"]');
    expect(line).not.toBeNull();
    expect(line?.textContent).toContain('admin.publications.series.nextIssueCreation.MANUAL');
    expect(line?.textContent).toContain('admin.publications.series.hint.nextIssueByHand');
    // No control at all, not a locked one.
    expect(document.querySelector('#series-next-issue')).toBeNull();

    editor.save();
    const req = httpTesting.expectOne((r) => r.method === 'PUT');
    expect((req.request.body as PublicationSeries).nextIssueCreation).toBe('MANUAL');
    req.flush(WEEKLY);
  });

  /**
   * The name comes first, because it is the thing being made.
   *
   * The id used to lead, which put a technical key nobody has an answer for
   * above the one field they came to fill in.
   */
  it('puts the name above the identifier on the basics tab', () => {
    const { fixture, host, httpTesting } = setup(DOMAINS);

    open(fixture, host, { mode: 'add', series: { ...WEEKLY, seriesId: '' } }, httpTesting);
    fixture.detectChanges();

    const panel = document.querySelector('.cdk-overlay-container');
    const name = panel?.querySelector('#desc-name-da');
    const id = panel?.querySelector('#series-id');
    expect(name).not.toBeNull();
    expect(id).not.toBeNull();
    expect(name!.compareDocumentPosition(id!) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  /**
   * The id is proposed from the name until somebody types in it.
   *
   * Derived from the FIRST configured language, which is the installation's
   * primary one — the name an identifier should read like.
   */
  it('derives the identifier from the name while creating', () => {
    const { fixture, host, httpTesting } = setup(DOMAINS);
    const editor = open(
      fixture,
      host,
      { mode: 'add', series: { ...WEEKLY, seriesId: '', descs: [] } },
      httpTesting,
    );

    editor.setDescField('da', 'name', 'Ugentlig EfS');
    expect(editor.value().seriesId).toBe('ugentlig-efs');

    // Still following, because nobody has taken it over yet.
    editor.setDescField('da', 'name', 'Ugentlig EfS for Øresund');
    expect(editor.value().seriesId).toBe('ugentlig-efs-for-oeresund');
  });

  /** From the first keystroke in the id, the name stops proposing at it. */
  it('stops deriving once the identifier is typed in', () => {
    const { fixture, host, httpTesting } = setup(DOMAINS);
    const editor = open(
      fixture,
      host,
      { mode: 'add', series: { ...WEEKLY, seriesId: '', descs: [] } },
      httpTesting,
    );

    editor.setDescField('da', 'name', 'Ugentlig EfS');
    editor.markSeriesIdTyped();
    editor.form.seriesId().value.set('efs-weekly');

    editor.setDescField('da', 'name', 'Noget helt andet');
    expect(editor.value().seriesId).toBe('efs-weekly');
  });

  /** An existing series has a frozen id, so nothing proposes at it. */
  it('never proposes an identifier for a series that already has one', () => {
    const { fixture, host, httpTesting } = setup(DOMAINS);
    const editor = open(fixture, host, { mode: 'edit', series: WEEKLY }, httpTesting);

    editor.setDescField('da', 'name', 'Noget helt andet');
    expect(editor.value().seriesId).toBe('weekly-ntm');
  });

  /**
   * The owner is a locked FIELD showing the domain's NAME.
   *
   * It sits in the same row as the category and answers the same kind of
   * question, which a bare line of text does not; and it says the domain's name
   * rather than its id, because the id is not what anybody calls the desk.
   */
  it('renders the owner as a locked field carrying the domain name', () => {
    const { fixture, host, httpTesting } = setup(DOMAINS);

    open(fixture, host, { mode: 'edit', series: WEEKLY }, httpTesting);
    fixture.detectChanges();

    const owner = document.querySelector<HTMLInputElement>('#series-owner');
    expect(owner).not.toBeNull();
    expect(owner?.disabled).toBe(true);
    expect(owner?.value).toBe('Notices to Mariners');
    // One hint under the field, said once.
    const body = document.body.textContent ?? '';
    expect(body.split('admin.publications.series.hint.ownerLocked')).toHaveLength(2);
  });

  /**
   * The content type is a segmented control on the FIRST tab, and it offers
   * every mode a series can carry.
   *
   * The choice decides which of the other tabs mean anything, so it belongs
   * where the reader already is; and NONE is real data — eight imported series
   * carry it, and a picker that cannot show a series its own value changes it
   * on the next save.
   */
  it('offers the content type as a segmented control over every mode', () => {
    const { fixture, host, httpTesting } = setup(DOMAINS);
    const editor = open(fixture, host, { mode: 'edit', series: WEEKLY }, httpTesting);
    fixture.detectChanges();

    const buttons = [
      ...document.querySelectorAll<HTMLButtonElement>('.cdk-overlay-container .seg .seg-btn'),
    ];
    expect(buttons.map((b) => b.textContent?.trim())).toEqual([
      'admin.publications.series.contentMode.GENERATED_FROM_QUERY',
      'admin.publications.series.contentMode.UPLOADED_FILE',
      'admin.publications.series.contentMode.EXTERNAL_LINK',
      'admin.publications.series.contentMode.NONE',
    ]);
    expect(buttons[0].getAttribute('aria-pressed')).toBe('true');
    // Soft, and the width of the fields it stands among: a saturated block the
    // width of four words is the heaviest thing on a panel of ordinary inputs.
    const seg = document.querySelector('.cdk-overlay-container .seg');
    expect(seg?.classList.contains('is-soft')).toBe(true);
    expect(seg?.classList.contains('w-full')).toBe(true);
    // There is no dropdown left behind on the content tab.
    expect(document.querySelector('#series-content-mode')).toBeNull();

    buttons[2].click();
    fixture.detectChanges();
    expect(editor.value().contentMode).toBe('EXTERNAL_LINK');

    // The criteria tab rendered while the mode was query-backed, and its preview
    // probe is debounced — so it is still outstanding when the mode moves on and
    // the editor unsubscribes. Taken off the queue rather than asserted on: this
    // test is about the control, not about the preview.
    httpTesting.match((r) => r.url.includes('resolve-preview'));
  });

  /** Two sentences about the same rule is the same sentence twice. */
  it('carries one hint under the citation field, and swaps it when it locks', () => {
    const { fixture, host, httpTesting } = setup(DOMAINS);

    open(fixture, host, { mode: 'edit', series: WEEKLY }, httpTesting);
    fixture.detectChanges();
    let body = document.body.textContent ?? '';
    expect(body).toContain('admin.publications.series.hint.messagePublication');
    expect(body).not.toContain('admin.publications.series.hint.messagePublicationLocked');

    open(fixture, host, { mode: 'edit', series: { ...WEEKLY, status: 'ACTIVE' } }, httpTesting);
    fixture.detectChanges();
    body = document.body.textContent ?? '';
    expect(body).toContain('admin.publications.series.hint.messagePublicationLocked');
  });

  /**
   * A new series says what creating it does, and the button says the same.
   *
   * A save here does not start the publication: it files a draft in this one
   * domain, and nothing goes out until somebody activates it.
   */
  it('opens a new series with the draft notice and a button that names it', () => {
    const { fixture, host, httpTesting } = setup(DOMAINS);

    open(fixture, host, { mode: 'add', series: { ...WEEKLY, seriesId: '' } }, httpTesting);
    fixture.detectChanges();

    const body = document.body.textContent ?? '';
    expect(body).toContain('admin.publications.series.editor.createNotice');
    expect(body).toContain('admin.publications.series.editor.createDraft');
    expect(body).not.toContain('admin.publications.series.editor.save');
    // The drawer is titled the way the menu entry that opened it is titled.
    expect(document.querySelector('.cdk-overlay-container h2')?.textContent).toContain(
      'admin.publications.series.editor.newSeries',
    );
  });

  /**
   * The notice names the desk, and the desk is the emphasised part.
   *
   * Rendered as markup because the name sits inside the sentence: splitting the
   * copy in two around it would leave a translator with two half-sentences and
   * no way to reorder them.
   */
  it('sets the domain of the draft notice in bold', () => {
    const { fixture, host, httpTesting } = setup(DOMAINS);
    // The real sentence, because the emphasis is inside it: the stub bundle
    // answers with the key and there is nowhere for the name to land.
    TestBed.inject(TranslocoService).setTranslation(
      {
        'admin.publications.series.editor.createNotice':
          'The series is created as a draft in {{domain}}.',
      },
      'en',
      { merge: true },
    );

    open(fixture, host, { mode: 'add', series: { ...WEEKLY, seriesId: '' } }, httpTesting);
    fixture.detectChanges();

    const notice = document.querySelector('.cdk-overlay-container .message-info');
    expect(notice?.textContent).toContain('Notices to Mariners');
    expect(notice?.querySelector('strong')?.textContent).toBe('Notices to Mariners');
  });

  /**
   * The name field hints at what a name looks like, in the row's own language.
   *
   * The hint is a per-language one: a Danish row hints in Danish whichever
   * language the operator is running the app in.
   */
  it('hints the name field per language', () => {
    const { fixture, host, httpTesting } = setup(DOMAINS);
    const transloco = TestBed.inject(TranslocoService);
    const key = 'admin.publications.series.placeholder.name';
    transloco.setTranslation({ [key]: 'e.g. Notices to Mariners' }, 'en', { merge: true });
    // Warm the (stub) loader for the second language first: its empty bundle
    // lands on whatever is already there, so a seed written before the load is
    // wiped by it.
    transloco.load('da').subscribe();
    transloco.setTranslation({ [key]: 'fx Efterretninger for Søfarende' }, 'da', { merge: true });

    open(fixture, host, { mode: 'add', series: { ...WEEKLY, seriesId: '' } }, httpTesting);
    fixture.detectChanges();

    // Each row hints in its OWN language, not in the one the app is running in —
    // which is the whole point of the per-row lookup.
    expect(document.querySelector<HTMLInputElement>('#desc-name-da')?.placeholder).toBe(
      'fx Efterretninger for Søfarende',
    );
    expect(document.querySelector<HTMLInputElement>('#desc-name-en')?.placeholder).toBe(
      'e.g. Notices to Mariners',
    );
  });

  /**
   * The category prompt reads as an instruction, not as a chosen value.
   *
   * In the field's own colour "Choose a category…" looks like an answer somebody
   * gave, which is exactly the field S-19 refuses a save over.
   */
  it('mutes the category select while nothing is chosen', () => {
    const { fixture, host, httpTesting } = setup(DOMAINS);
    const editor = open(
      fixture,
      host,
      { mode: 'add', series: { ...WEEKLY, seriesId: '', categoryId: undefined } },
      httpTesting,
    );
    fixture.detectChanges();

    const category = document.querySelector<HTMLSelectElement>('#series-category');
    expect(category?.classList.contains('text-fg-muted')).toBe(true);

    editor.form.categoryId().value.set('dk-dma-weekly-nm-publications');
    fixture.detectChanges();
    expect(category?.classList.contains('text-fg-muted')).toBe(false);
  });

  /**
   * THE FOOTER CARRIES THREE THINGS, and none of them is a lifecycle action.
   *
   * Activating a series, withdrawing it and putting it back are things done TO
   * a series rather than settings on it: they change what other people see and
   * they are not part of the edit being saved, so a drawer that also performed
   * them would make Cancel mean two things.
   */
  it('offers only check-rules, cancel and save in the footer', () => {
    const { fixture, host, httpTesting } = setup(DOMAINS);

    // A DRAFT with a report is the state that used to offer Activate; ACTIVE
    // and RETIRED are the two that used to offer Retire and Reinstate.
    for (const status of ['DRAFT', 'ACTIVE', 'RETIRED'] as const) {
      open(fixture, host, { mode: 'edit', series: { ...WEEKLY_WITH_REPORT, status } }, httpTesting);
      fixture.detectChanges();

      const footer = document.querySelector('.cdk-overlay-container [drawerFooter]');
      const labels = [...(footer?.querySelectorAll('button') ?? [])].map((b) =>
        b.textContent?.trim(),
      );
      expect(labels).toEqual([
        'admin.publications.series.editor.validate',
        'admin.publications.series.editor.cancel',
        'admin.publications.series.editor.save',
      ]);
    }

    const body = document.body.textContent ?? '';
    expect(body).not.toContain('admin.publications.series.editor.activate');
    expect(body).not.toContain('admin.publications.series.editor.retire');
    expect(body).not.toContain('admin.publications.series.editor.reinstate');
  });

  /**
   * The pane being GONE is passed on, and it is a later moment than the ask.
   *
   * A host that builds this editor only while its drawer is up hangs the
   * teardown on it: destroying on the ask instead would cut the slide-out short,
   * and never destroying leaves the option lists — read once, when the editor is
   * built — to be shown again at the next opening.
   */
  it('passes on the drawer’s hidden signal, once the pane has gone', () => {
    const { fixture, host, httpTesting } = setup(DOMAINS);
    open(fixture, host, { mode: 'edit', series: WEEKLY }, httpTesting);
    fixture.detectChanges();

    const panel = pane();
    expect(panel).not.toBeNull();

    // The ask: the host has taken the model away, and the pane is sliding out.
    host.model.set(undefined);
    fixture.detectChanges();
    expect(host.hiddenCount).toBe(0);
    expect(pane()).not.toBeNull();

    endSlideOut(panel!);
    expect(host.hiddenCount).toBe(1);
    expect(pane()).toBeNull();
  });

  // ------------------------------------------------------ what a save carries

  /**
   * C.4. A query-backed series with no report can pass every S-rule and still
   * never produce anything: the report IS the document. `/validate` does not ask
   * for one, so the form says so where the report is chosen.
   */
  it('names a query-backed series with no report', () => {
    const { fixture, host, httpTesting } = setup();
    const editor = open(fixture, host, { mode: 'edit', series: WEEKLY }, httpTesting);

    expect(editor.reportMissing()).toBe(true);

    // Not a rule about query-backed series in general — a link-backed one has no
    // report to want.
    editor.setField('contentMode', 'EXTERNAL_LINK');
    expect(editor.reportMissing()).toBe(false);
  });

  it('keeps a language’s text when it is unticked and ticked again', () => {
    const { fixture, host, httpTesting } = setup();
    const editor = open(fixture, host, { mode: 'edit', series: WEEKLY }, httpTesting);

    expect(editor.languages()).toEqual(['da', 'en']);
    editor.toggleLanguage('da');
    expect(editor.languages()).toEqual(['en']);
    editor.toggleLanguage('da');
    // Order follows the configured list, not the click order.
    expect(editor.languages()).toEqual(['da', 'en']);
    expect(editor.descs()['da'].name).toBe('Ugentlig EfS');
  });

  /**
   * A series can declare a language the build-time list does not carry —
   * imported data does, and the backend can be configured wider than the build.
   *
   * Iterating the installation list alone meant every tick of ANY checkbox
   * silently dropped that language, and `updateFromVo` then deleted its desc
   * row. The language stays on the screen so it can be read, and removed on
   * purpose rather than by accident.
   */
  it('keeps a language the installation does not configure tickable', () => {
    const { fixture, host, httpTesting } = setup();
    const editor = open(
      fixture,
      host,
      {
        mode: 'edit',
        series: {
          ...WEEKLY,
          languages: ['da', 'en', 'fr'],
          descs: [...WEEKLY.descs, { lang: 'fr', name: 'Avis aux navigateurs' }],
        },
      },
      httpTesting,
    );

    expect(editor.languageOptions().map((o) => o.value)).toEqual(['da', 'en', 'fr']);
    expect(editor.languageOptions().find((o) => o.value === 'fr')?.configured).toBe(false);

    // An unrelated tick leaves it alone.
    editor.toggleLanguage('da');
    expect(editor.languages()).toEqual(['en', 'fr']);

    // And it can be removed, and put back, deliberately.
    editor.toggleLanguage('fr');
    expect(editor.languages()).toEqual(['en']);
    editor.toggleLanguage('fr');
    expect(editor.languages()).toEqual(['en', 'fr']);
    expect(editor.descs()['fr'].name).toBe('Avis aux navigateurs');
  });

  it('sends a desc row only for a declared language', () => {
    const { fixture, host, httpTesting } = setup();
    const editor = open(fixture, host, { mode: 'edit', series: WEEKLY }, httpTesting);

    editor.toggleLanguage('da');
    editor.save();

    const req = httpTesting.expectOne((r) => r.method === 'PUT');
    const body = req.request.body as PublicationSeries;
    expect(body.languages).toEqual(['en']);
    expect(body.descs.map((d) => d.lang)).toEqual(['en']);
    req.flush(WEEKLY);
  });

  it('asks the server why when a save is refused', () => {
    // A toast saying only that something went wrong leaves the admin with no
    // next move; the rules do.
    const { fixture, host, httpTesting } = setup();
    const editor = open(fixture, host, { mode: 'edit', series: WEEKLY }, httpTesting);

    editor.setField('cadence', 'YEARLY');
    editor.save();
    httpTesting
      .expectOne((r) => r.method === 'PUT')
      .flush({ error: 'SERIES_INVALID' }, { status: 400, statusText: 'Bad Request' });

    const validate = httpTesting.expectOne((r) => r.url === '/rest/publication-series/validate');
    validate.flush([
      { rule: 'S-6', field: 'nominalCutoffMonth', message: 'a yearly cadence needs a month' },
    ]);
    expect(editor.validationErrors()).toHaveLength(1);
  });

  /**
   * The citation field stops being editable once the series has left DRAFT.
   *
   * The citations already stored live in whichever field was configured at the
   * time, so moving the setting orphans them — the server refuses, and the form
   * stops offering it rather than letting the refusal be the first anybody hears
   * of the rule.
   */
  it('locks the citation field on a series that has left draft', () => {
    const { fixture, host, httpTesting } = setup();

    let editor = open(fixture, host, { mode: 'edit', series: WEEKLY }, httpTesting);
    expect(editor.messagePublicationLocked()).toBe(false);

    editor = open(
      fixture,
      host,
      { mode: 'edit', series: { ...WEEKLY, status: 'ACTIVE' } },
      httpTesting,
    );
    expect(editor.messagePublicationLocked()).toBe(true);

    // A new series has published nothing, so nothing cites it yet.
    editor = open(fixture, host, { mode: 'add', series: { ...WEEKLY, seriesId: '' } }, httpTesting);
    expect(editor.messagePublicationLocked()).toBe(false);
  });

  /**
   * A report parameter repeating what the issue supplies is refused here.
   *
   * Week, year and edition are injected at render, so a typed one is a second
   * source for the same value — the server refuses it, and a save that gets as
   * far as the server only to come back is a slower way of saying so.
   */
  it('will not save a report parameter that repeats an injected value', () => {
    const { fixture, host, httpTesting } = setup();
    const editor = open(
      fixture,
      host,
      { mode: 'edit', series: { ...WEEKLY, reportId: 'nm-report' } },
      httpTesting,
    );

    editor.addReportParam();
    editor.setReportParam(0, 'key', typed('Week '));
    expect(editor.reservedParamKeys()).toEqual(['Week']);
    expect(editor.saveDisabled()).toBe(true);

    editor.setReportParam(0, 'key', typed('volume'));
    expect(editor.reservedParamKeys()).toEqual([]);
    expect(editor.saveDisabled()).toBe(false);
  });

  /**
   * What the chosen report NAMES, offered as a row rather than applied.
   *
   * A template may read a key the report never declared and a report may declare
   * one its template ignores, so this is a shortcut to a row and never a check on
   * the series: nothing is added for anybody, and nothing is refused.
   */
  describe('the parameters the report names', () => {
    /**
     * The report's declared parameters, with values that must not be offered:
     * they are the message print dialog's defaults, written for a different
     * caller. `week` is among them and is supplied by the issue.
     */
    const REPORT: MessageReport = {
      reportId: 'nm-report',
      name: 'NtM report',
      properties: {},
      params: { 'NtM-ref': '${ntm-ref}', week: '${week}' },
    };

    /** The suggestion, as the control a reader clicks. */
    function suggestion(key: string): HTMLButtonElement | undefined {
      return [
        ...document.querySelectorAll<HTMLButtonElement>('.cdk-overlay-container button'),
      ].find((button) => button.textContent?.trim() === key);
    }

    it('suggests only the keys the series does not carry, reserved names apart', () => {
      const { fixture, host, httpTesting } = setup(DOMAINS, [], [], [REPORT]);
      const editor = open(fixture, host, { mode: 'edit', series: WEEKLY_WITH_REPORT }, httpTesting);
      fixture.detectChanges();

      // `week` is supplied by the issue and refused as a key, so the report
      // naming it changes nothing.
      expect(editor.suggestedParamKeys()).toEqual(['NtM-ref']);
      expect(suggestion('NtM-ref')).toBeDefined();
      expect(suggestion('week')).toBeUndefined();
    });

    it('appends the row, with no value, when the suggestion is clicked', () => {
      const { fixture, host, httpTesting } = setup(DOMAINS, [], [], [REPORT]);
      const editor = open(fixture, host, { mode: 'edit', series: WEEKLY_WITH_REPORT }, httpTesting);
      fixture.detectChanges();

      suggestion('NtM-ref')!.click();
      fixture.detectChanges();

      // The report's own value is ignored: what the parameter should say is the
      // admin's to write.
      expect(editor.reportParams()).toEqual([{ key: 'NtM-ref', value: '' }]);
      // And it stops being a suggestion, because the series now carries it.
      expect(editor.suggestedParamKeys()).toEqual([]);
      expect(suggestion('NtM-ref')).toBeUndefined();
    });

    it('says nothing about a key the series already answers', () => {
      const { fixture, host, httpTesting } = setup(DOMAINS, [], [], [REPORT]);
      const carried = {
        ...WEEKLY_WITH_REPORT,
        reportParams: { 'NtM-ref': 'EfS' },
      } as unknown as PublicationSeries;
      const editor = open(fixture, host, { mode: 'edit', series: carried }, httpTesting);
      fixture.detectChanges();

      expect(editor.suggestedParamKeys()).toEqual([]);
      expect(document.body.textContent).not.toContain(
        'admin.publications.series.hint.reportParamSuggestions',
      );
    });

    it('shows no hint for a report that declares no parameters', () => {
      const bare: MessageReport = { ...REPORT, params: {} };
      const { fixture, host, httpTesting } = setup(DOMAINS, [], [], [bare]);
      const editor = open(fixture, host, { mode: 'edit', series: WEEKLY_WITH_REPORT }, httpTesting);
      fixture.detectChanges();

      expect(editor.suggestedParamKeys()).toEqual([]);
      expect(document.body.textContent).not.toContain(
        'admin.publications.series.hint.reportParamSuggestions',
      );
    });
  });

  it('never lets a save carry a status', () => {
    // The backend owns the transition and validates it. A status on a save would
    // route around the rules that decide whether a series may be active at all.
    const { fixture, host, httpTesting } = setup();
    const editor = open(fixture, host, { mode: 'edit', series: WEEKLY }, httpTesting);

    editor.setDescField('en', 'name', 'Weekly Notices');
    editor.save();

    const req = httpTesting.expectOne((r) => r.method === 'PUT');
    expect((req.request.body as PublicationSeries).status).toBe('DRAFT');
    req.flush(WEEKLY);
  });

  // -------------------------------------------------- the domain and its zone

  /**
   * C.23. The domain is the ONLY source of a series' timezone, so a domain
   * without one cannot back a cut-off schedule: the times on this screen would
   * be read in whatever zone the reader happens to be in.
   */
  it('says so when the owner carries no timezone and the series has cut-offs', () => {
    const { fixture, host, httpTesting } = setup(DOMAINS);
    // niord-fe has no timezone: a real shape in the estate, and the one that
    // leaves a cadenced series declaring a schedule with no zone to be read in.
    const editor = open(
      fixture,
      host,
      { mode: 'edit', series: { ...WEEKLY, domainId: 'niord-fe' } },
      httpTesting,
    );

    expect(editor.ownerTimeZoneMissing()).toBe(true);

    // A cadence-less publication has no nominal cut-off at all, so there is
    // nothing for a missing zone to be wrong about.
    editor.setField('cadence', 'NONE');
    expect(editor.ownerTimeZoneMissing()).toBe(false);
  });

  it('says nothing about the zone when the owner has one', () => {
    const { fixture, host, httpTesting } = setup(DOMAINS);
    const editor = open(fixture, host, { mode: 'edit', series: WEEKLY }, httpTesting);

    expect(editor.ownerTimeZoneMissing()).toBe(false);
    expect(editor.ownerDomainName()).toBe('Notices to Mariners');
  });

  /**
   * The first-interval start is an INSTANT on both sides of the field.
   *
   * The value that crosses this boundary is the moment itself; the wall clock
   * belongs to the control, which is bound to the OWNER's zone. So re-committing
   * what is on screen stores the same instant, to the millisecond.
   */
  it('carries the first-interval start across the field as an instant', () => {
    const newYearInCopenhagen = Date.UTC(2025, 11, 31, 23, 0, 0);
    const { fixture, host, httpTesting } = setup(DOMAINS);
    const editor = open(
      fixture,
      host,
      { mode: 'edit', series: { ...WEEKLY, firstIssueStartsAt: newYearInCopenhagen } },
      httpTesting,
    );

    expect(editor.seriesTimeZone()).toBe('Europe/Copenhagen');
    const shown = editor.firstIssueStartsAtValue();
    expect(shown?.toISOString()).toBe('2025-12-31T23:00:00.000Z');

    editor.setFirstIssueStartsAt(shown);
    expect(editor.value().firstIssueStartsAt).toBe(newYearInCopenhagen);
    expect(editor.changed()).toBe(false);
  });

  /**
   * The schedule belongs to the domain that publishes the series, not to the
   * desk the admin is sitting at.
   *
   * 2026-06-10T00:30Z is 02:30 on the 10th in Copenhagen and 22:30 on the 9th in
   * the session's own zone — so a grid drawn in the wrong one opens on another
   * day, and a pick resolved in it stores another instant (2026-06-06T00:30Z).
   */
  it('resolves a picked day in the owner domain’s zone, not the session’s', async () => {
    const { fixture, host, httpTesting } = setup(DOMAINS);
    const editor = open(
      fixture,
      host,
      { mode: 'edit', series: { ...WEEKLY, firstIssueStartsAt: Date.UTC(2026, 5, 10, 0, 30) } },
      httpTesting,
    );
    fixture.autoDetectChanges();
    await fixture.whenStable();

    document.querySelector<HTMLButtonElement>('#series-first-start')!.click();
    await fixture.whenStable();
    calendarDay('5')!.click();
    await fixture.whenStable();

    const stored = new Date(editor.value().firstIssueStartsAt!);
    expect(stored.toISOString()).toBe('2026-06-05T00:30:00.000Z');
    expect(wallClockIn('Europe/Copenhagen', stored)).toBe('2026-06-05 02:30:00');
  });

  /**
   * An owner with no zone leaves nothing to read the clock in, and the session's
   * zone is not an answer: it is the zone of whoever happened to open the drawer.
   */
  it('disables the first-interval start where the owner has no zone', async () => {
    const { fixture, host, httpTesting } = setup(DOMAINS);
    sessionDomain.set({ domainId: 'niord-fe' } as Domain);
    const editor = open(
      fixture,
      host,
      {
        mode: 'edit',
        series: {
          ...WEEKLY,
          domainId: 'niord-fe',
          firstIssueStartsAt: Date.UTC(2026, 5, 10, 0, 30),
        },
      },
      httpTesting,
    );
    fixture.autoDetectChanges();
    await fixture.whenStable();

    expect(editor.seriesTimeZone()).toBeUndefined();
    // The instant is kept, and not printed as a clock nobody's domain reads.
    expect(editor.value().firstIssueStartsAt).toBe(Date.UTC(2026, 5, 10, 0, 30));
    expect(editor.firstIssueStartsAtValue()).toBeNull();
    const trigger = document.querySelector<HTMLButtonElement>('#series-first-start')!;
    expect(trigger.disabled).toBe(true);
    trigger.click();
    await fixture.whenStable();
    expect(calendarDay('5')).toBeUndefined();
    // The stub loader renders keys verbatim. There is no domain picker in this editor, so the
    // note names the domain as the place the zone is missing from.
    expect(document.body.textContent).toContain('admin.publications.series.hint.domainNoTimeZone');
    expect(document.body.textContent).not.toContain('common.dateTime.noTimeZone');
  });

  /**
   * The owner's zone arrives over HTTP, and a start that is on file must not blank while it is
   * on its way.
   *
   * An owner not yet in the catalogue and an owner without a zone both read as "no zone", but
   * only the second is a fact about the domain. The field stays closed to edits either way;
   * what it must not do is wipe the value off the screen and put it back a moment later.
   */
  it('keeps showing a stored start while the domain catalogue has not named the owner', async () => {
    // An empty catalogue is exactly the state the drawer opens in, before `/domains/all` answers.
    const { fixture, host, httpTesting } = setup();
    const editor = open(
      fixture,
      host,
      { mode: 'edit', series: { ...WEEKLY, firstIssueStartsAt: Date.UTC(2026, 5, 10, 0, 30) } },
      httpTesting,
    );
    fixture.autoDetectChanges();
    await fixture.whenStable();

    expect(editor.ownerKnown()).toBe(false);
    expect(editor.seriesTimeZone()).toBeUndefined();
    expect(editor.firstIssueStartsAtValue()?.getTime()).toBe(Date.UTC(2026, 5, 10, 0, 30));
    expect(document.querySelector<HTMLButtonElement>('#series-first-start')!.disabled).toBe(true);
    // No verdict on the domain until the catalogue has one to give.
    expect(document.body.textContent).not.toContain(
      'admin.publications.series.hint.domainNoTimeZone',
    );
  });

  // ------------------------------------------------------------ cut-off default

  /**
   * S-21, offered rather than hidden.
   *
   * Only a yearly series has a period whose boundary means anything; the two
   * period options stay in the list, disabled, so a reader can see what the
   * choice would be and why it is not available here.
   */
  it('locks the period boundaries on anything but a yearly series', () => {
    const { fixture, host, httpTesting } = setup();
    const editor = open(fixture, host, { mode: 'edit', series: WEEKLY }, httpTesting);

    expect(editor.periodCutoffAllowed()).toBe(false);
    expect(editor.isCutoffDefaultLocked('RELEASE_MOMENT')).toBe(false);
    expect(editor.isCutoffDefaultLocked('PERIOD_START')).toBe(true);
    expect(editor.isCutoffDefaultLocked('PERIOD_END')).toBe(true);

    editor.setField('cadence', 'YEARLY');
    expect(editor.periodCutoffAllowed()).toBe(true);
    expect(editor.isCutoffDefaultLocked('PERIOD_END')).toBe(false);
  });

  /** A boundary left behind by a cadence change is reported, not silently kept. */
  it('reports a cut-off default the rule refuses', () => {
    const { fixture, host, httpTesting } = setup();
    const editor = open(
      fixture,
      host,
      { mode: 'edit', series: { ...WEEKLY, cadence: 'YEARLY', cutoffDefault: 'PERIOD_END' } },
      httpTesting,
    );

    expect(editor.cutoffDefaultBroken()).toBe(false);
    editor.setField('cadence', 'WEEKLY');
    expect(editor.cutoffDefaultBroken()).toBe(true);
  });

  /**
   * The setting round-trips, and a yearly series keeps the boundary it chose.
   *
   * S-21 refuses a series that does not say where its cut-off falls, so the field
   * is on every save whether or not the admin touched it.
   */
  it('sends the chosen cut-off default on save', () => {
    const { fixture, host, httpTesting } = setup();
    const editor = open(
      fixture,
      host,
      {
        mode: 'edit',
        series: {
          ...WEEKLY,
          cadence: 'YEARLY',
          nominalCutoffMonth: 12,
          nominalCutoffDayOfMonth: 31,
          cutoffDefault: 'PERIOD_END',
        },
      },
      httpTesting,
    );

    expect(editor.value().cutoffDefault).toBe('PERIOD_END');
    editor.setField('cutoffDefault', 'PERIOD_START');
    editor.save();

    const req = httpTesting.expectOne(
      (r) => r.method === 'PUT' && r.url === '/rest/publication-series/series/weekly-ntm',
    );
    expect((req.request.body as PublicationSeries).cutoffDefault).toBe('PERIOD_START');
    req.flush(WEEKLY);
  });

  // ---------------------------------------------------------- the revision

  const AT_4 = { ...WEEKLY, version: 4 } as PublicationSeries;

  /**
   * A save sends the WHOLE series, so it has to say which revision it is from.
   *
   * That is the failure this closes: two admins open the same series, the first
   * corrects the criteria, and the second saves a rename — carrying the old
   * criteria back with it and silently undoing the correction, with nothing on
   * either screen to say so.
   */
  it('saves against the revision the drawer was opened on', () => {
    const { fixture, host, httpTesting } = setup();
    const editor = open(fixture, host, { mode: 'edit', series: AT_4 }, httpTesting);

    editor.setDescField('en', 'name', 'Weekly notices to mariners');
    editor.save();

    const req = httpTesting.expectOne(
      (r) => r.method === 'PUT' && r.url === '/rest/publication-series/series/weekly-ntm',
    );
    expect((req.request.body as PublicationSeries).version).toBe(4);
    req.flush(AT_4);
  });

  /**
   * A stale refusal re-reads the series UNDER the edit, keeps the edit, and says
   * so honestly.
   *
   * The refused save changed nothing, so throwing away what an admin has typed
   * in order to show them a field somebody else moved would be the worse answer
   * of the two. What the re-read buys is a payload built on the row as it now
   * stands — so the next press carries a revision the server will accept. That
   * next press also writes the typed values over the other admin's change in
   * every field this form owns (last write wins), and nothing on screen shows
   * that change: the toast says so, rather than the shared sentence's "it has
   * been reloaded — check what moved", which is true only on the pages that do
   * re-read what they show.
   */
  it('re-reads the series when a save is refused as composed against an older revision', () => {
    const { fixture, host, httpTesting } = setup();
    const editor = open(fixture, host, { mode: 'edit', series: AT_4 }, httpTesting);
    const notifications = TestBed.inject(NotificationService) as unknown as {
      error: ReturnType<typeof vi.fn>;
    };
    notifications.error.mockClear();

    editor.setDescField('en', 'name', 'Weekly notices to mariners');
    editor.save();
    httpTesting
      .expectOne(
        (r) => r.method === 'PUT' && r.url === '/rest/publication-series/series/weekly-ntm',
      )
      .flush(
        { code: 'STALE_VERSION', storedVersion: 5, submittedVersion: 4 },
        { status: 409, statusText: 'Conflict' },
      );

    // The toast says the other change is NOT shown and that saving overwrites
    // it, never that the form was reloaded.
    expect(notifications.error).toHaveBeenCalledWith('admin.publications.error.staleNotShown');
    expect(notifications.error).not.toHaveBeenCalledWith('admin.publications.error.staleVersion');

    // The re-read, and NOT the validation run every other refusal triggers:
    // there are no rules to show for a refusal that is not about the values.
    httpTesting.expectNone((r) => r.url === '/rest/publication-series/validate');
    httpTesting
      .expectOne('/rest/publication-series/editable-series/weekly-ntm')
      .flush({ ...WEEKLY, importSource: 'legacy-import', version: 5 });
    fixture.detectChanges();

    // What was typed is still there, and the next attempt is composed against 5.
    expect(editor.descs()['en'].name).toBe('Weekly notices to mariners');
    editor.save();
    const retry = httpTesting.expectOne(
      (r) => r.method === 'PUT' && r.url === '/rest/publication-series/series/weekly-ntm',
    );
    const body = retry.request.body as PublicationSeries;
    expect(body.version).toBe(5);
    expect(body.descs.find((d) => d.lang === 'en')?.name).toBe('Weekly notices to mariners');
    // And a field this form does not own comes from the re-read, not from the
    // copy the drawer was opened with — provenance is the server's to write, and
    // a save composed against the old copy would roll it back.
    expect(body.importSource).toBe('legacy-import');
    retry.flush(WEEKLY);
  });

  /**
   * The caret starts in the first name box, not on the tab strip.
   *
   * The first focusable node of a tabbed editor is a tab, so a drawer that
   * simply focuses what comes first lands the writer on the strip they are
   * already looking at and costs them a Tab before the first keystroke — and
   * an Escape pressed there closes what they opened to type in.
   */
  it('opens with the caret in the first name box', async () => {
    const { fixture, host, httpTesting } = setup(DOMAINS);

    open(fixture, host, { mode: 'edit', series: WEEKLY }, httpTesting);
    // The drawer lands focus in a microtask, after the panel is painted.
    await fixture.whenStable();

    const first = document.querySelector<HTMLInputElement>('[data-drawer-body] input');
    expect(first?.id).toBe('desc-name-da');
    expect(document.activeElement).toBe(first);
  });
});

/**
 * A series another domain is accountable for opens READ-ONLY.
 *
 * The drawer still opens, and every setting in it still reads: reads are not
 * scoped, and this is the only screen the full configuration of a series is
 * legible from — refusing to show it would hide a series from admins allowed to
 * see it in order to prevent a write the buttons already decline to offer.
 */
describe('PublicationSeriesEditor — a series another domain owns', () => {
  afterEach(() => {
    TestBed.inject(HttpTestingController).verify();
    document.querySelectorAll('.cdk-overlay-container').forEach((el) => el.replaceChildren());
  });

  it('names the domain that owns it and refuses the save', () => {
    const { fixture, host, httpTesting } = setup();
    sessionDomain.set({ domainId: 'niord-fe' } as Domain);
    const editor = open(fixture, host, { mode: 'edit', series: WEEKLY }, httpTesting);

    expect(editor.writable()).toBe(false);
    expect(editor.otherDomainId()).toBe('niord-nm');
    // Even with a real edit in hand, which is what would otherwise open the gate.
    editor.setDescField('da', 'name', 'Noget andet');
    fixture.detectChanges();
    expect(editor.changed()).toBe(true);
    expect(editor.saveDisabled()).toBe(true);
  });

  /**
   * The content type goes read-only with everything else.
   *
   * It is a segmented control rather than a select, so it has to decline the
   * write on its own: a disabled `<select>` cannot be changed, a row of buttons
   * can.
   */
  it('locks the content-type control on another domain’s series', () => {
    const { fixture, host, httpTesting } = setup(DOMAINS);
    sessionDomain.set({ domainId: 'niord-fe' } as Domain);
    open(fixture, host, { mode: 'edit', series: WEEKLY }, httpTesting);
    fixture.detectChanges();

    const buttons = [
      ...document.querySelectorAll<HTMLButtonElement>('.cdk-overlay-container .seg .seg-btn'),
    ];
    expect(buttons.length).toBeGreaterThan(0);
    expect(buttons.every((button) => button.disabled)).toBe(true);
  });

  /**
   * The answer comes off the STORED series, not off the domain control.
   *
   * Keying it on what is typed would lock the form the instant somebody opened
   * the domain picker, and unlock it by picking their own — so an admin at the
   * right desk keeps a live form while they move a series they do own.
   */
  it('does not lock the form when the domain field is edited', () => {
    const { fixture, host, httpTesting } = setup();
    const editor = open(fixture, host, { mode: 'edit', series: WEEKLY }, httpTesting);

    editor.setField('domainId', 'niord-fe');
    fixture.detectChanges();

    expect(editor.writable()).toBe(true);
    expect(editor.saveDisabled()).toBe(false);
  });

  /** A new series is always writable here; where it may be PLACED is the save's rule. */
  it('leaves a new series editable from any domain', () => {
    const { fixture, host, httpTesting } = setup();
    sessionDomain.set({ domainId: 'niord-fe' } as Domain);
    const editor = open(fixture, host, { mode: 'add', series: WEEKLY }, httpTesting);

    expect(editor.writable()).toBe(true);
    expect(editor.otherDomainId()).toBeUndefined();
  });
});
