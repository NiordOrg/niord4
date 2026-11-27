import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { APP_CONFIG } from '../../../../../core/config/app-config.token';
import { DomainFacade } from '../../../../../core/domain/facades/domain.facade';
import type { Domain } from '../../../../../core/domain/models/domain.model';
import { NotificationService } from '../../../../../core/notifications/notification.service';
import { provideNiordIcons } from '../../../../../shared/icons/niord-icons';
import { DiscardConfirmService } from '../../../../../shared/services/discard-confirm.service';
import { provideTestTransloco } from '../../../../../shared/testing/transloco-testing';
import type { OneOffPublication } from '../../../services/api/one-off-api.service';
import type { PublicationSeries } from '../../../services/api/publication-series-api.service';
import { OneOffEditor, type OneOffEditorModel } from './one-off-editor';
import { SeriesCriteriaEditor } from './series-criteria-editor';

const BASE = '/rest';

/** A link-backed one-off, as the endpoint returns it. */
const LINKED_SERIES = {
  seriesId: 'journal-number',
  status: 'DRAFT',
  kind: 'ONE_OFF',
  cadence: 'NONE',
  contentMode: 'EXTERNAL_LINK',
  categoryId: 'dk-dma-internal-publications',
  // Named, so that clearing it in the test below is a real edit rather than a
  // no-op the save gate correctly refuses to send.
  domainId: 'niord-nm',
  languages: ['en'],
  descs: [{ lang: 'en', name: 'Journal number' }],
} as unknown as PublicationSeries;

const EXISTING: OneOffPublication = {
  series: LINKED_SERIES,
  // eslint-disable-next-line @typescript-eslint/no-deprecated -- the server still sends the field, and the type requires it
  active: true,
  issuePublicId: 'i-1',
  issueStatus: 'PUBLISHED',
  links: [{ lang: 'en', value: 'https://example.invalid/j' }],
  fileNames: [],
  publishable: false,
};

/** An uploaded publication written in two languages. */
const UPLOADED_TWO_LANG: OneOffPublication = {
  ...EXISTING,
  series: {
    ...LINKED_SERIES,
    contentMode: 'UPLOADED_FILE',
    // One document for both languages, which is what a one-off usually is.
    languageSpecific: false,
    languages: ['da', 'en'],
    descs: [
      { lang: 'da', name: 'Journalnummer' },
      { lang: 'en', name: 'Journal number' },
    ],
  } as unknown as PublicationSeries,
  links: [],
  fileNames: [{ lang: 'da', value: 'journal-nummer.pdf' }],
};

/**
 * A publication that already IS generated from a query.
 *
 * The only way that mode reaches this drawer: the segment is not offered on a
 * publication that is anything else, so the fields behind it are reachable
 * exactly where they describe something.
 */
const QUERY_BACKED: OneOffPublication = {
  ...EXISTING,
  series: {
    ...LINKED_SERIES,
    contentMode: 'GENERATED_FROM_QUERY',
    timeRelation: 'PUBLISHED_IN_INTERVAL',
  } as unknown as PublicationSeries,
  links: [],
};

/** The protected surface the assertions reach for. */
interface EditorInternals {
  languages: () => readonly string[];
  form: OneOffEditor['form'];
  canSave: () => boolean;
  writable: () => boolean;
  otherDomainId: () => string | undefined;
  noSessionDomain: () => boolean;
  ownerDomainName: () => string;
  availableDomainIds: { (): readonly string[]; set: (value: readonly string[]) => void };
  availabilityIncomplete: () => boolean;
  showAdvanced: { (): boolean; set: (value: boolean) => void };
  validate: () => void;
  validation: () => readonly unknown[] | undefined;
  value: () => { availability: string; domainId: string; languageSpecific: boolean };
  setField: (key: 'contentMode' | 'availability' | 'languageSpecific', value: unknown) => void;
}

/**
 * The desk the admin is sitting at.
 *
 * `niord-nm` by default, which owns the fixture publication: the drawer is
 * read-only on one another domain is accountable for, so every test about
 * saving has to be sitting at the right desk to be about what it says.
 */
const sessionDomain = signal<Domain | undefined>({ domainId: 'niord-nm' } as Domain);

/** The domain catalogue the availability list is drawn from. */
const availableDomains = signal<readonly Domain[]>([
  { domainId: 'niord-nm', name: 'Notices to Mariners' } as Domain,
  { domainId: 'niord-annex', name: 'Annexes' } as Domain,
]);

/**
 * The discard prompt's answer, decided per test.
 *
 * The real service resolves from a dialog the app-root host renders, which does
 * not exist here — an unstubbed prompt would hang instead of failing.
 */
let discardAnswer = true;
const confirmDiscard = vi.fn(() => Promise.resolve(discardAnswer));

/** Let the awaited prompt resolve and its continuation run. */
function settled(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0));
}

async function setup(model: OneOffEditorModel, failing?: string) {
  sessionDomain.set({ domainId: 'niord-nm' } as Domain);
  discardAnswer = true;
  confirmDiscard.mockClear();
  TestBed.configureTestingModule({
    imports: [OneOffEditor],
    providers: [
      provideTestTransloco(),
      provideNiordIcons(),
      provideHttpClient(),
      provideHttpClientTesting(),
      { provide: DiscardConfirmService, useValue: { confirmDiscard } },
      // Stubbed rather than real: the owner read would otherwise pull the
      // AuthFacade -> Keycloak chain into a spec about a form.
      {
        provide: DomainFacade,
        useValue: { sessionDomain, availableDomains } as unknown as DomainFacade,
      },
      { provide: APP_CONFIG, useValue: { apiBaseUrl: BASE, languages: ['en'] } },
    ],
  });

  const fixture = TestBed.createComponent(OneOffEditor);
  fixture.componentRef.setInput('model', model);
  fixture.autoDetectChanges();
  await fixture.whenStable();

  const httpTesting = TestBed.inject(HttpTestingController);
  // The pickers load on construction; they are not what these assert, beyond the
  // two tests below that are about which endpoints they read, and on what terms.
  // The query string comes with the URL so those tests can see the parameters.
  const lookupUrls: string[] = [];
  httpTesting
    .match(() => true)
    .forEach((r) => {
      lookupUrls.push(r.request.urlWithParams);
      if (failing && r.request.url.includes(failing)) {
        r.flush('down', { status: 503, statusText: 'Service Unavailable' });
      } else {
        r.flush([]);
      }
    });
  await fixture.whenStable();

  return { fixture, httpTesting, lookupUrls };
}

/**
 * The whole drawer, footer included.
 *
 * Save and Cancel live in the drawer's footer slot rather than in the form body,
 * so a query rooted at the `<form>` would not find them.
 */
function panel(): HTMLElement {
  const found = document.querySelector('.niord-drawer-panel') ?? document.querySelector('form');
  if (!found) {
    throw new Error('the drawer did not render');
  }
  return found as HTMLElement;
}

/**
 * One control, by id.
 *
 * Addressed by id rather than by position in the drawer: a control added or
 * removed above one of these renumbers every assertion below it, which is how a
 * test about the content mode came to be reading the message-publication list.
 */
function selectById(id: string): HTMLSelectElement {
  const found = panel().querySelector<HTMLSelectElement>(`#${id}`);
  if (!found) {
    throw new Error(`no select #${id}`);
  }
  return found;
}

/**
 * The type segments, in the order they are offered.
 *
 * Addressed by their `data-mode` rather than by position, so a reordering does
 * not silently repoint every assertion below at a different answer.
 */
function typeSegments(): HTMLButtonElement[] {
  return Array.from(panel().querySelectorAll<HTMLButtonElement>('#one-off-content-mode .seg-btn'));
}

function typeSegment(mode: string): HTMLButtonElement {
  const found = panel().querySelector<HTMLButtonElement>(`.seg-btn[data-mode="${mode}"]`);
  if (!found) {
    throw new Error(`no type segment for ${mode}`);
  }
  return found;
}

/** Choose a content mode the way an admin does. */
async function chooseType(
  fixture: { whenStable: () => Promise<unknown> },
  mode: string,
): Promise<void> {
  typeSegment(mode).click();
  await fixture.whenStable();
}

/**
 * The language tag of each upload row, `''` where the row carries none.
 *
 * Read off the row that owns each file input, so the assertion is about what
 * sits in front of a control rather than about text anywhere on the screen.
 */
function fileRowTags(): string[] {
  return Array.from(panel().querySelectorAll('input[type="file"]')).map((input) =>
    Array.from(input.closest('div')?.querySelectorAll('span') ?? [])
      .map((span) => span.textContent?.trim() ?? '')
      .filter((text) => text === 'DA' || text === 'EN')
      .join(''),
  );
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

function buttonLabelled(text: string): HTMLButtonElement {
  const match = Array.from(panel().querySelectorAll('button')).find((b) =>
    (b.textContent ?? '').toLowerCase().includes(text.toLowerCase()),
  );
  if (!match) {
    throw new Error(`no button matching "${text}"`);
  }
  return match as HTMLButtonElement;
}

/**
 * The one-off editor.
 *
 * What these hold is that it is a full editor for a publication rather than a
 * shortcut: every content mode a series can take, the query fields when it takes
 * the one that needs them, and a domain that can be set back to "everywhere".
 */
describe('OneOffEditor', () => {
  afterEach(() => {
    // The overlay attaches to the document, not to the fixture, so it outlives
    // the component unless it is cleared between tests.
    document.querySelectorAll('.cdk-overlay-container').forEach((n) => n.replaceChildren());
  });

  it('says so when a picker list could not be read, and Retry reads it again', async () => {
    const { fixture, httpTesting } = await setup({ mode: 'add' }, '/message-reports/');
    const banner = () =>
      [...panel().querySelectorAll('[role="alert"]')].find((node) =>
        node.textContent?.includes('common.fetchError'),
      );
    expect(banner()).toBeDefined();

    banner()?.querySelector('button')?.click();
    await fixture.whenStable();
    // Only the list that failed is read again.
    httpTesting.expectNone((r) => r.url.includes('/name-tokens'));
    httpTesting.expectOne((r) => r.url.includes('/message-reports/')).flush([]);
    await fixture.whenStable();
    expect(banner()).toBeUndefined();
  });

  it('fills the report picker from the endpoint a plain admin may read', async () => {
    // `/message-reports/all` is sysadmin-only and answers everyone else with a
    // 403, which left an admin with an empty picker and no way past the gate
    // that makes a report a precondition for activating a query-backed one-off.
    const { lookupUrls } = await setup({ mode: 'add' });

    expect(lookupUrls).toContain(`${BASE}/message-reports/reports`);
    expect(lookupUrls.some((url) => url.includes('/message-reports/all'))).toBe(false);
  });

  /**
   * The domain catalogue is read as ids and names, and nothing else.
   *
   * The server's default is to compute each domain's Keycloak state, which it
   * does by asking Keycloak once per row — a third of a second the drawer would
   * spend on a field it never renders.
   */
  it('reads the domain catalogue without the per-domain Keycloak lookup', async () => {
    const { lookupUrls } = await setup({ mode: 'add' });

    const domains = lookupUrls.find((url) => url.includes('/domains/all'));
    expect(domains).toContain('keycloakState=false');
  });

  it('seeds the fields from an existing publication', async () => {
    await setup({ mode: 'edit', publication: EXISTING });

    const inputs = Array.from(panel().querySelectorAll('input')) as HTMLInputElement[];
    expect(inputs.some((i) => i.value === 'Journal number')).toBe(true);
    expect(inputs.some((i) => i.value === 'https://example.invalid/j')).toBe(true);
  });

  /**
   * THE THREE ANSWERS A ONE-OFF IS WRITTEN DOWN AS.
   *
   * Reference-only, an uploaded PDF, an external link. All three are real data —
   * three of the five one-offs in the estate carry NONE — and the earlier
   * version of this form offered only the last two, which is narrower than what
   * it edits.
   */
  it('offers the three modes a one-off is written down as', async () => {
    await setup({ mode: 'add' });

    // In the order a one-off is usually chosen from, and labelled the way a
    // one-off reads: "reference only" rather than "no content".
    expect(typeSegments().map((b) => b.getAttribute('data-mode'))).toEqual([
      'NONE',
      'UPLOADED_FILE',
      'EXTERNAL_LINK',
    ]);
    expect(typeSegment('NONE').textContent?.trim()).toBe('admin.publications.oneOff.typeNone');
    expect(typeSegment('UPLOADED_FILE').getAttribute('aria-pressed')).toBe('true');
  });

  /**
   * "Generated from a query" is shown where the publication already IS one.
   *
   * Nobody builds a one-off that way — the ones that exist arrived from the
   * previous system — and offering it turns a form for writing down an existing
   * document into the series form. But a picker that cannot show a publication
   * its own value changes it on the next save, so the segment appears where
   * there is something for it to describe.
   */
  it('offers the generated mode only on a publication that already is one', async () => {
    await setup({ mode: 'edit', publication: QUERY_BACKED });

    expect(typeSegments().map((b) => b.getAttribute('data-mode'))).toEqual([
      'NONE',
      'UPLOADED_FILE',
      'EXTERNAL_LINK',
      'GENERATED_FROM_QUERY',
    ]);
    expect(typeSegment('GENERATED_FROM_QUERY').textContent?.trim()).toBe(
      'admin.publications.oneOff.typeGenerated',
    );
    expect(typeSegment('GENERATED_FROM_QUERY').getAttribute('aria-pressed')).toBe('true');
  });

  /**
   * And it stays offered after another segment is pressed.
   *
   * The question the segment answers is what the publication IS, not what is
   * currently typed: read off the working copy, one press would remove the
   * segment along with any way back to the stored answer, and the next save
   * would rewrite a query-backed publication as something else.
   */
  it('keeps the generated segment offered after switching away from it', async () => {
    const { fixture } = await setup({ mode: 'edit', publication: QUERY_BACKED });

    await chooseType(fixture, 'EXTERNAL_LINK');

    expect(typeSegments().map((b) => b.getAttribute('data-mode'))).toContain(
      'GENERATED_FROM_QUERY',
    );
    expect(typeSegment('GENERATED_FROM_QUERY').getAttribute('aria-pressed')).toBe('false');

    // And the way back leads to the stored mode.
    await chooseType(fixture, 'GENERATED_FROM_QUERY');

    expect(typeSegment('GENERATED_FROM_QUERY').getAttribute('aria-pressed')).toBe('true');
  });

  /**
   * A query-backed one-off gets the query fields, and only then.
   *
   * S-1 requires a time relation and a criteria document on a query-backed
   * series and forbids both on every other mode, so the fields have to follow
   * the mode rather than always being present.
   */
  it('shows the criteria editor on a query-backed publication', async () => {
    await setup({ mode: 'edit', publication: QUERY_BACKED });

    expect(panel().querySelectorAll('app-series-criteria-editor').length).toBe(1);
  });

  /**
   * Two regimes here, where the series editor offers three.
   *
   * Compiling another publication's issues is a series shape: it needs a source
   * series and a period to compile, and a one-off has a single issue and no
   * series to tile against. Offering it would put a value on the screen whose
   * companion control lives on another form.
   */
  it('offers the two regimes a one-off can take, and not the compiled one', async () => {
    await setup({ mode: 'edit', publication: QUERY_BACKED });

    const select = panel().querySelector<HTMLSelectElement>('#one-off-time-relation');
    expect([...(select?.options ?? [])].map((option) => option.value)).toEqual([
      'PUBLISHED_IN_INTERVAL',
      'IN_FORCE_AT_CUTOFF',
    ]);
  });

  it('names each page size as the print dialog does, the stored size as the value', async () => {
    await setup({ mode: 'edit', publication: QUERY_BACKED });

    const select = [...panel().querySelectorAll<HTMLSelectElement>('select')].find((candidate) =>
      [...candidate.options].some((option) => option.value === 'LETTER'),
    );
    const options = [...(select?.options ?? [])];
    expect(options.map((option) => option.value)).toEqual([
      'A3',
      'A4',
      'A5',
      'B4',
      'B5',
      'LETTER',
      'LEGAL',
      'LEDGER',
    ]);
    expect(options.map((option) => option.textContent?.trim())).toEqual([
      'messages.print.settings.paperSize.A3',
      'messages.print.settings.paperSize.A4',
      'messages.print.settings.paperSize.A5',
      'messages.print.settings.paperSize.B4',
      'messages.print.settings.paperSize.B5',
      'messages.print.settings.paperSize.letter',
      'messages.print.settings.paperSize.legal',
      'messages.print.settings.paperSize.ledger',
    ]);
  });

  it('shows no criteria editor on a publication that is not query-backed', async () => {
    await setup({ mode: 'add' });

    expect(panel().querySelectorAll('app-series-criteria-editor').length).toBe(0);
  });

  /** And the cadence and automation controls are absent in every mode. */
  it('never offers the series-only controls', async () => {
    await setup({ mode: 'edit', publication: QUERY_BACKED });

    const text = panel().textContent ?? '';
    // NOT 'cutoff' as a bare substring: aliveAtCutoff is a CRITERIA setting and
    // belongs here. What must be absent is the schedule -- when the next issue
    // cuts off, what it gets numbered, whether it appears by itself.
    for (const seriesOnly of [
      'cadence',
      'nextIssueCreation',
      'numberingScheme',
      'nominalCutoff',
      'firstIssueStartsAt',
      'releaseMode',
    ]) {
      expect(text.toLowerCase()).not.toContain(seriesOnly.toLowerCase());
    }
  });

  /**
   * THE OWNER IS THE DESK BEING WORKED AT, and it reaches the wire as one.
   *
   * There is no picker: a one-off is listed and administered in exactly one
   * domain, and offering a choice here would offer to file it somewhere the save
   * refuses. Moving it afterwards is its own action, with its own reason.
   */
  it('sends the session domain as the owner of a new publication', async () => {
    const { fixture, httpTesting } = await setup({ mode: 'add' });

    expect(panel().querySelectorAll('select').length).toBeGreaterThan(0);
    const editor = fixture.componentInstance as unknown as EditorInternals;
    expect(editor.value().domainId).toBe('niord-nm');
    expect(editor.noSessionDomain()).toBe(false);
    expect(httpTesting).toBeDefined();
  });

  /**
   * A NEW publication is meant to go out.
   *
   * The one place the transition flag is deliberately `true`: an admin writing a
   * document down means it to be citable, and the create endpoint activates the
   * series and publishes its issue on the strength of it. A publication that
   * still needs its bytes comes back unpublishable instead, which the toast says.
   */
  it('asks the create endpoint to publish the new publication', async () => {
    const { fixture, httpTesting } = await setup({ mode: 'add' });
    const editor = fixture.componentInstance as unknown as EditorInternals & {
      setName: (event: { lang: string; value: string }) => void;
      setField: (key: string, value: unknown) => void;
    };

    editor.setName({ lang: 'en', value: 'Charter of the port' });
    editor.setField('categoryId', 'dk-dma-internal-publications');
    await fixture.whenStable();

    // A new publication is created rather than saved, so the button says so.
    buttonLabelled('createDraft').click();
    await fixture.whenStable();

    const request = httpTesting.expectOne(`${BASE}/one-off-publications/`);
    expect(request.request.method).toBe('POST');
    expect(request.request.body.active).toBe(true);
    expect(request.request.body.reason).toBeUndefined();
    request.flush({ ...EXISTING, publishable: false });
    await fixture.whenStable();
  });

  /**
   * Availability is the question the domain picker used to answer badly.
   *
   * A document that exists to be cited starts available everywhere, which is
   * what the citation-only publications were before anybody had the choice; a
   * generated one is the desk's own product and starts closed.
   */
  it('defaults a new publication to every domain, and follows the content mode', async () => {
    const { fixture } = await setup({ mode: 'add' });
    const editor = fixture.componentInstance as unknown as EditorInternals;

    expect(editor.value().availability).toBe('ALL_DOMAINS');

    // Through the same setter the segments call. The generated segment is not
    // offered on a new publication, and this is about the rule the mode carries
    // rather than about which segments a new publication shows.
    editor.setField('contentMode', 'GENERATED_FROM_QUERY');
    await fixture.whenStable();

    expect(editor.value().availability).toBe('OWNER_ONLY');
  });

  /** The shared-with list travels, and never names the owner itself. */
  it('sends the availability and its list, owner excluded', async () => {
    const { fixture, httpTesting } = await setup({ mode: 'edit', publication: EXISTING });
    const editor = fixture.componentInstance as unknown as EditorInternals;

    editor.setField('availability', 'SELECTED_DOMAINS');
    editor.availableDomainIds.set(['niord-annex', 'niord-nm']);
    await fixture.whenStable();

    buttonLabelled('save').click();
    await fixture.whenStable();

    const request = httpTesting.expectOne(`${BASE}/one-off-publications/journal-number`);
    expect(request.request.body.series.domainId).toBe('niord-nm');
    expect(request.request.body.series.availability).toBe('SELECTED_DOMAINS');
    expect(request.request.body.series.availableDomainIds).toEqual(['niord-annex']);
    request.flush(EXISTING);
  });

  /**
   * "Selected domains" with nothing selected is refused before the server sees it.
   *
   * An empty list is a publication shared with nobody, which is what OWNER_ONLY
   * says — so the save gate holds and the control says why in place.
   */
  it('refuses to save selected-domains with nothing selected', async () => {
    const { fixture } = await setup({ mode: 'edit', publication: EXISTING });
    const ed = fixture.componentInstance as unknown as EditorInternals;

    ed.setField('availability', 'SELECTED_DOMAINS');
    await fixture.whenStable();

    expect(ed.availabilityIncomplete()).toBe(true);
    expect(ed.canSave()).toBe(false);

    ed.availableDomainIds.set(['niord-annex']);
    await fixture.whenStable();
    expect(ed.availabilityIncomplete()).toBe(false);
    expect(ed.canSave()).toBe(true);
  });

  /** Narrowing back to the desk clears the list rather than leaving a stale share. */
  it('sends an empty list when the publication is narrowed to its own desk', async () => {
    const { fixture, httpTesting } = await setup({ mode: 'edit', publication: EXISTING });
    const editor = fixture.componentInstance as unknown as EditorInternals;

    editor.availableDomainIds.set(['niord-annex']);
    editor.setField('availability', 'OWNER_ONLY');
    await fixture.whenStable();

    buttonLabelled('save').click();
    await fixture.whenStable();

    const request = httpTesting.expectOne(`${BASE}/one-off-publications/journal-number`);
    expect(request.request.body.series.availability).toBe('OWNER_ONLY');
    expect(request.request.body.series.availableDomainIds).toEqual([]);
    request.flush(EXISTING);
  });

  /**
   * A publication another desk owns is read-only here, as it is on the series
   * editor: the server refuses the write, so the button declines rather than the
   * request.
   */
  it('will not save a publication another domain owns', async () => {
    const { fixture } = await setup({ mode: 'edit', publication: EXISTING });
    // Moved AFTER the setup, which seeds the desk that owns the fixture: the
    // question here is what happens when the admin is sitting somewhere else.
    sessionDomain.set({ domainId: 'niord-annex' } as Domain);
    const editor = fixture.componentInstance as unknown as EditorInternals;
    await fixture.whenStable();

    expect(editor.writable()).toBe(false);
    expect(editor.otherDomainId()).toBe('niord-nm');
    editor.setField('availability', 'OWNER_ONLY');
    await fixture.whenStable();
    expect(editor.canSave()).toBe(false);
  });

  /** A publication with no name cannot be saved: the list would have a blank row. */
  it('refuses to save without a name', async () => {
    await setup({ mode: 'add' });

    // The create button, which is what the primary action is called here: a
    // publication is created rather than saved the first time.
    expect(buttonLabelled('admin.publications.series.editor.createDraft').disabled).toBe(true);
  });

  /** ERR7. A name goes into a varchar(255) column; a longer one used to answer 500. */
  it('will not save a name longer than its column, and counts under the names', async () => {
    const { fixture } = await setup({ mode: 'edit', publication: EXISTING });
    const editor = fixture.componentInstance as unknown as EditorInternals & {
      setName: (event: { lang: string; value: string }) => void;
    };
    const counters = () => [...document.querySelectorAll('app-length-counter p')];
    expect(counters()).toHaveLength(editor.languages().length);

    const lang = editor.languages()[0];
    editor.setName({ lang, value: 'a'.repeat(256) });
    await fixture.whenStable();
    expect(editor.canSave()).toBe(false);
    expect(counters().some((line) => line.getAttribute('role') === 'alert')).toBe(true);

    editor.setName({ lang, value: 'a'.repeat(255) });
    await fixture.whenStable();
    expect(editor.canSave()).toBe(true);
    expect(counters().some((line) => line.getAttribute('role') === 'alert')).toBe(false);
  });

  /**
   * Cancelling a drawer with typed work asks first.
   *
   * Every close intent — this button, the ✕, Escape, the backdrop — goes through
   * the drawer's gate, so wiring the button straight to `closed` would make three
   * of the four discard silently.
   */
  it('asks before discarding unsaved work, and stays open when declined', async () => {
    const { fixture } = await setup({ mode: 'edit', publication: EXISTING });
    discardAnswer = false;

    await chooseType(fixture, 'NONE');

    buttonLabelled('cancel').click();
    await settled();

    expect(confirmDiscard).toHaveBeenCalled();
    // Declined: the drawer is still there, with the edit still in it.
    expect(typeSegment('NONE').getAttribute('aria-pressed')).toBe('true');
  });

  /** An untouched drawer closes without a prompt — there is nothing to lose. */
  it('closes without asking when nothing was changed', async () => {
    await setup({ mode: 'edit', publication: EXISTING });

    buttonLabelled('cancel').click();
    await settled();

    expect(confirmDiscard).not.toHaveBeenCalled();
  });

  /**
   * An untouched Save sends nothing.
   *
   * A full-body PUT that changes nothing is not free here: on a live publication
   * the backend re-runs the activation sequence for it, and there is no edit to
   * justify that.
   */
  it('does not send an untouched edit', async () => {
    const { httpTesting } = await setup({ mode: 'edit', publication: EXISTING });

    expect(buttonLabelled('save').disabled).toBe(true);
    httpTesting.expectNone(() => true);
  });

  /**
   * A CLEARED LINK REACHES THE SERVER AS A CLEARED LINK.
   *
   * A row left out of the request reads as "unchanged", so dropping the blank
   * ones left the old address public with nothing on this screen showing it. One
   * row per declared language, blank ones carried as null.
   */
  it('sends a cleared link as a cleared link', async () => {
    const { fixture, httpTesting } = await setup({ mode: 'edit', publication: EXISTING });

    const link = Array.from(panel().querySelectorAll('input')).find(
      (i) => i.value === 'https://example.invalid/j',
    );
    if (!link) {
      throw new Error('the link input did not render');
    }
    link.value = '';
    link.dispatchEvent(new Event('input'));
    await fixture.whenStable();

    buttonLabelled('save').click();
    await fixture.whenStable();

    const request = httpTesting.expectOne(`${BASE}/one-off-publications/journal-number`);
    expect(request.request.body.links).toEqual([{ lang: 'en', value: null }]);
    request.flush(EXISTING);
  });

  /**
   * A stale refusal says honestly that the other change is not shown.
   *
   * The drawer re-reads the series under the typed values, so the next Save
   * carries the revision the server accepts, and with it writes the typed values
   * over the other admin's change in every field this drawer owns (last write
   * wins). Nothing on screen shows that change, so the toast must not say "it
   * has been reloaded — check what moved", the shared sentence the pages that do
   * re-read what they show use.
   */
  it('says the other change is not shown when a save is refused as stale', async () => {
    const { fixture, httpTesting } = await setup({ mode: 'edit', publication: EXISTING });
    const error = vi.spyOn(TestBed.inject(NotificationService), 'error');

    const link = Array.from(panel().querySelectorAll('input')).find(
      (i) => i.value === 'https://example.invalid/j',
    );
    if (!link) {
      throw new Error('the link input did not render');
    }
    link.value = 'https://example.invalid/k';
    link.dispatchEvent(new Event('input'));
    await fixture.whenStable();

    buttonLabelled('save').click();
    await fixture.whenStable();
    httpTesting
      .expectOne(`${BASE}/one-off-publications/journal-number`)
      .flush(
        { code: 'STALE_VERSION', storedVersion: 5, submittedVersion: 4 },
        { status: 409, statusText: 'Conflict' },
      );
    httpTesting
      .expectOne(`${BASE}/publication-series/editable-series/journal-number`)
      .flush({ ...LINKED_SERIES, version: 5 });
    await fixture.whenStable();

    expect(error).toHaveBeenCalledWith('admin.publications.error.staleNotShown');
    expect(error).not.toHaveBeenCalledWith('admin.publications.error.staleVersion');

    // The typed value is kept, and the next press is composed against 5.
    buttonLabelled('save').click();
    await fixture.whenStable();
    const retry = httpTesting.expectOne(`${BASE}/one-off-publications/journal-number`);
    expect(retry.request.body.series.version).toBe(5);
    expect(retry.request.body.links).toEqual([{ lang: 'en', value: 'https://example.invalid/k' }]);
    retry.flush(EXISTING);
  });

  /**
   * The two rules this drawer carries are the form's, and they reach the DOM.
   *
   * A hand-written `categoryId.trim() !== ''` gated Save while no control
   * reflected the rule, so a blank category greyed the button out and marked
   * nothing; and the immutable id was locked by a template `[disabled]` the
   * schema knew nothing about.
   */
  it('gates Save through the form, and reflects the rules onto the controls', async () => {
    const { fixture } = await setup({ mode: 'add' });
    const editor = fixture.componentInstance as unknown as EditorInternals;

    expect(editor.form().invalid()).toBe(true);
    expect(editor.canSave()).toBe(false);

    const category = panel().querySelector<HTMLSelectElement>('select');
    expect(category?.required).toBe(true);

    editor.form.categoryId().value.set('dk-dma-public-publications');
    await fixture.whenStable();
    expect(editor.form().invalid()).toBe(false);
    expect(editor.form.seriesId().disabled()).toBe(false);
  });

  /** S-16: the id is the citation handle, so it is editable exactly once. */
  it('locks the id on an existing publication through the schema', async () => {
    const { fixture } = await setup({ mode: 'edit', publication: EXISTING });
    const editor = fixture.componentInstance as unknown as EditorInternals;

    expect(editor.form.seriesId().disabled()).toBe(true);
  });

  /**
   * WHETHER THE PUBLICATION IS ON THE PUBLIC SITE IS NOT A FIELD IN HERE.
   *
   * Withdrawing one retires the issue behind it and has to record why; publishing
   * it again is the same kind of change the other way. Both are things done TO a
   * publication rather than settings on it, so they live on the publication's own
   * page and in its row's menu — and this drawer has no tick, and no reason
   * dialog behind one.
   */
  it('carries no status toggle and no reason dialog', async () => {
    await setup({ mode: 'edit', publication: EXISTING });

    const body = panel().textContent ?? '';
    expect(body).not.toContain('admin.publications.oneOff.active');
    expect(body).not.toContain('admin.publications.oneOff.status');
    expect(body).not.toContain('admin.publications.oneOff.withdrawTitle');
    expect(body).not.toContain('admin.publications.oneOff.withdrawEffect');
    expect(document.querySelector('app-publication-reason-dialog')).toBeNull();
  });

  /**
   * The save asks for NO transition, and that is derived from where the
   * publication actually stands.
   *
   * The endpoint reads the flag as a transition rather than as a value: it acts
   * on the difference between what is asked for and where the publication is. So
   * a form that sends the whole publication back has to send the matching value,
   * or an ordinary rename becomes a withdrawal.
   */
  it.each([
    ['a published publication inside its period', 'PUBLISHED', undefined, true],
    ['a publication nobody has released', 'OPEN', undefined, false],
    ['a withdrawn publication', 'RETIRED', undefined, false],
    ['a publication whose period has run out', 'PUBLISHED', Date.UTC(2017, 11, 31), false],
  ])('asks for no transition on %s', async (_name, issueStatus, publicTo, expected) => {
    const { fixture, httpTesting } = await setup({
      mode: 'edit',
      publication: {
        ...EXISTING,
        series: { ...LINKED_SERIES, status: 'ACTIVE' } as unknown as PublicationSeries,
        issueStatus,
        publicFrom: Date.UTC(2017, 1, 22),
        publicTo,
      },
    });
    const editor = fixture.componentInstance as unknown as EditorInternals;

    editor.setField('availability', 'OWNER_ONLY');
    await fixture.whenStable();

    buttonLabelled('save').click();
    await fixture.whenStable();

    const request = httpTesting.expectOne(`${BASE}/one-off-publications/journal-number`);
    expect(request.request.body.active).toBe(expected);
    expect(request.request.body.reason).toBeUndefined();
    request.flush(EXISTING);
  });

  /**
   * A box per DECLARED language, not per installed one.
   *
   * The installation runs two languages; this publication declares one. A box
   * for the other accepts text that `buildDescs` then drops, silently.
   */
  it('offers a text box only for a language the publication declares', async () => {
    const { fixture } = await setup({ mode: 'edit', publication: EXISTING });
    const editor = fixture.componentInstance as unknown as EditorInternals;

    expect(editor.languages()).toEqual(['en']);
    const nameGroup = panel().querySelector('app-localized-inputs');
    expect(nameGroup?.querySelectorAll('input').length).toBe(1);
    // Stacked: the name boxes run down the drawer rather than side by side, so
    // each gets the width a title needs.
    expect(nameGroup?.querySelector('div')?.className).not.toContain('sm:grid-cols-2');
  });

  /**
   * The preview probes what the SAVE will send, over a bounded window.
   *
   * S-3 forces the liveness flag on for an in-force publication, so probing the
   * raw checkbox answers for a publication that will never exist; and a
   * cadence-less publication has no period to step back by, so without a floor
   * the probe asks the resolver for every message ever published.
   */
  it('probes the preview with the flag it will send, over a bounded window', async () => {
    const { fixture } = await setup({ mode: 'edit', publication: QUERY_BACKED });

    const criteriaEditor = fixture.debugElement.query(By.directive(SeriesCriteriaEditor))
      .componentInstance as SeriesCriteriaEditor;

    // Interval-based: a floor, and it is below the cut-off it runs to.
    expect(criteriaEditor.timeRelation()).toBe('PUBLISHED_IN_INTERVAL');
    expect(criteriaEditor.intervalFrom()).toBeDefined();
    expect(criteriaEditor.intervalFrom()!).toBeLessThan(criteriaEditor.cutoff());

    const relationSelect = selectById('one-off-time-relation');
    relationSelect.value = 'IN_FORCE_AT_CUTOFF';
    relationSelect.dispatchEvent(new Event('change'));
    await fixture.whenStable();

    // In force: the flag is forced on though nothing was ticked, and the window
    // is deliberately open — the issues have no floor either.
    expect(criteriaEditor.aliveAtCutoff()).toBe(true);
    expect(criteriaEditor.intervalFrom()).toBeUndefined();
  });
});

/**
 * The order of the drawer, and what it folds away.
 *
 * A one-off IS its document, so the file or the address belongs directly under
 * the answer that asked for it — and everything with a working default that is
 * set once in a publication's life belongs behind a disclosure rather than
 * between the reader and the two fields they came for.
 */
describe('OneOffEditor — the shape of the drawer', () => {
  afterEach(() => {
    document.querySelectorAll('.cdk-overlay-container').forEach((n) => n.replaceChildren());
  });

  function internals(fixture: { componentInstance: unknown }): EditorInternals {
    return fixture.componentInstance as EditorInternals;
  }

  /**
   * The caret starts in the name box, not on the ✕.
   *
   * A drawer that exists for the typing costs a Tab otherwise, and makes the
   * first key a writer presses the one that closes it.
   */
  it('opens with the caret in the first name box', async () => {
    await setup({ mode: 'add' });

    expect(document.activeElement).toBe(panel().querySelector('app-localized-inputs input'));
  });

  /**
   * The pane being GONE is passed on, and it is a later moment than the ask.
   *
   * A host that builds this editor only while its drawer is up hangs the
   * teardown on it: destroying on the ask instead would cut the slide-out short,
   * and never destroying leaves the option lists — read once, when the editor is
   * built — to be shown again at the next opening.
   */
  it('passes on the drawer’s hidden signal, once the pane has gone', async () => {
    const { fixture } = await setup({ mode: 'edit', publication: EXISTING });
    let hiddenCount = 0;
    fixture.componentInstance.hidden.subscribe(() => (hiddenCount += 1));

    const drawerPane = pane();
    expect(drawerPane).not.toBeNull();

    // The ask: the host has taken the model away, and the pane is sliding out.
    fixture.componentRef.setInput('model', undefined);
    await fixture.whenStable();
    expect(hiddenCount).toBe(0);
    expect(pane()).not.toBeNull();

    endSlideOut(drawerPane!);
    expect(hiddenCount).toBe(1);
    expect(pane()).toBeNull();
  });

  /** Name, then type, then the thing the type names. */
  it('puts the name, the type and the file row in that order', async () => {
    const { fixture } = await setup({ mode: 'add' });

    const name = panel().querySelector('#one-off-name-en, app-localized-inputs');
    const type = panel().querySelector('#one-off-content-mode');
    expect(name).not.toBeNull();
    expect(type).not.toBeNull();
    expect(name!.compareDocumentPosition(type!) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();

    // UPLOADED_FILE is the seeded mode, so the file row is already the next
    // thing on the screen.
    const hint = Array.from(panel().querySelectorAll('p')).find((p) =>
      (p.textContent ?? '').includes('admin.publications.oneOff.uploadOnDetail'),
    );
    expect(hint).toBeDefined();
    expect(type!.compareDocumentPosition(hint!) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(fixture).toBeDefined();
  });

  /**
   * A publication being created has nowhere to put the bytes yet.
   *
   * The issue is made by the first save, so the hint names where the file goes
   * instead of offering a control that cannot work.
   */
  it('offers no upload while the publication does not exist, and says where the file goes', async () => {
    await setup({ mode: 'add' });

    expect(panel().querySelector('input[type="file"]')).toBeNull();
    expect(panel().textContent).toContain('admin.publications.oneOff.uploadOnDetail');
  });

  /** An existing one has an issue behind it, so the file goes straight in. */
  it('uploads to the issue behind an existing publication', async () => {
    const { fixture, httpTesting } = await setup({
      mode: 'edit',
      publication: {
        ...EXISTING,
        series: { ...LINKED_SERIES, contentMode: 'UPLOADED_FILE' } as PublicationSeries,
        fileNames: [{ lang: 'en', value: 'journal-number.pdf' }],
      },
    });

    // The stored name is on the screen, and the control says "replace".
    expect(panel().textContent).toContain('journal-number.pdf');
    expect(panel().textContent).toContain('admin.publications.oneOff.detail.replaceFile');

    const input = panel().querySelector<HTMLInputElement>('input[type="file"]');
    expect(input).not.toBeNull();
    const file = new File(['%PDF'], 'journal-2027.pdf', { type: 'application/pdf' });
    Object.defineProperty(input!, 'files', { value: [file] });
    input!.dispatchEvent(new Event('change'));
    await fixture.whenStable();

    const request = httpTesting.expectOne(`${BASE}/publication-issues/issue/i-1/file/en`);
    expect(request.request.method).toBe('POST');
    request.flush({ publicId: 'i-1', status: 'PUBLISHED', descs: [] });
  });

  /**
   * ONE FILE, ONE ROW.
   *
   * A publication that does not declare a file per language HAS one file, and a
   * row per language offers to put a different document behind each — which the
   * server stores as one, last write winning. The language tag goes with the
   * extra rows: in front of a lone row it names a distinction the document does
   * not make.
   */
  it('offers one upload row per file, not one per language', async () => {
    const { fixture } = await setup({ mode: 'edit', publication: UPLOADED_TWO_LANG });
    const editor = fixture.componentInstance as unknown as EditorInternals;

    expect(fileRowTags()).toEqual(['']);
    expect(panel().textContent).toContain('journal-nummer.pdf');

    editor.setField('languageSpecific', true);
    await fixture.whenStable();

    expect(fileRowTags()).toEqual(['DA', 'EN']);
  });

  /**
   * The identifier, the sort order, the per-language file switch and the
   * citation live behind "Advanced".
   *
   * All four have a working default and are set once. Folded away rather than
   * dropped: the identifier is the citation handle, so it has to be reachable.
   */
  it('folds the settings nobody changes twice behind a disclosure', async () => {
    const { fixture } = await setup({ mode: 'add' });
    const editor = internals(fixture);

    expect(panel().querySelector('[data-testid="one-off-advanced"]')).toBeNull();
    expect(panel().textContent).toContain('admin.publications.oneOff.advanced');

    editor.showAdvanced.set(true);
    await fixture.whenStable();

    const advanced = panel().querySelector('[data-testid="one-off-advanced"]');
    expect(advanced).not.toBeNull();
    const text = advanced?.textContent ?? '';
    expect(text).toContain('admin.publications.oneOff.seriesId');
    expect(text).toContain('admin.publications.oneOff.sortOrder');
    expect(text).toContain('admin.publications.series.field.languageSpecific');
    expect(text).toContain('admin.publications.series.field.messagePublication');
  });

  /**
   * The reference format uses the pattern kit, so the token vocabulary is
   * offered rather than remembered.
   */
  it('offers the token menu on the reference format', async () => {
    const { fixture, httpTesting } = await setup({
      mode: 'edit',
      publication: {
        ...EXISTING,
        series: { ...LINKED_SERIES, messagePublication: 'EXTERNAL' } as PublicationSeries,
      },
    });
    // The token vocabulary is fetched on construction and drained by `setup`;
    // re-answered here so the menu has something to offer.
    httpTesting.match((r) => r.url.includes('/name-tokens')).forEach((r) => r.flush(['uge']));

    internals(fixture).showAdvanced.set(true);
    await fixture.whenStable();

    expect(panel().querySelectorAll('app-localized-text-input').length).toBeGreaterThan(0);
    expect(panel().querySelector('#one-off-reference-en')).not.toBeNull();
  });

  /**
   * THE FOOTER CARRIES THREE THINGS, and moving the publication is not one.
   *
   * A transfer changes who is accountable for the publication rather than what
   * it says, so it belongs where the publication itself is — its own page and
   * its row in the list.
   */
  it('offers only check-rules, cancel and save in the footer', async () => {
    await setup({ mode: 'edit', publication: EXISTING });

    const footer = document.querySelector('[drawerFooter]');
    const labels = [...(footer?.querySelectorAll('button') ?? [])].map((b) =>
      b.textContent?.trim(),
    );
    expect(labels).toEqual([
      'admin.publications.series.editor.validate',
      'admin.publications.oneOff.editor.cancel',
      'admin.publications.oneOff.editor.save',
    ]);
    expect(panel().textContent).not.toContain('admin.publications.transfer.action');
  });

  /** Creating says what it makes, and the button says the same. */
  it('names the create outcome on the primary button', async () => {
    await setup({ mode: 'add' });

    const footer = document.querySelector('[drawerFooter]');
    const labels = [...(footer?.querySelectorAll('button') ?? [])].map((b) =>
      b.textContent?.trim(),
    );
    expect(labels).toContain('admin.publications.series.editor.createDraft');
  });

  /** "Check rules" asks the same endpoint the series form asks. */
  it('checks the rules against the body being edited', async () => {
    const { fixture, httpTesting } = await setup({ mode: 'edit', publication: EXISTING });
    const editor = internals(fixture);

    editor.validate();
    await fixture.whenStable();

    const request = httpTesting.expectOne(`${BASE}/publication-series/validate`);
    expect(request.request.method).toBe('POST');
    expect(request.request.body.seriesId).toBe('journal-number');
    request.flush([{ rule: 'S-19', field: 'categoryId', message: 'a publication needs one' }]);
    await fixture.whenStable();

    expect(panel().textContent).toContain('admin.publications.series.editor.rulesFail');
    // The field is named the way the screen names it, and the reason is said in
    // the reader's language — never the server's English prose.
    expect(panel().textContent).toContain('admin.publications.series.field.categoryId');
    expect(panel().textContent).toContain('admin.publications.series.ruleFailed');
    expect(panel().textContent).not.toContain('a publication needs one');

    // And the next edit drops the answer, so a stale verdict cannot stand.
    editor.setField('availability', 'OWNER_ONLY');
    await fixture.whenStable();
    expect(editor.validation()).toBeUndefined();
  });
});
