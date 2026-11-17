import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it } from 'vitest';

import { APP_CONFIG } from '../../../../../core/config/app-config.token';
import { LanguageService } from '../../../../../core/main-shell/services/language.service';
import { provideNiordIcons } from '../../../../../shared/icons/niord-icons';
import { provideTestTransloco } from '../../../../../shared/testing/transloco-testing';
import { PublicationIncludeDialog, type IncludeRequest } from './include-dialog';

const HOUR = 60 * 60 * 1000;
const PERIOD_FROM = Date.now() - 7 * 24 * HOUR;
const PERIOD_TO = Date.now() - HOUR;

@Component({
  imports: [PublicationIncludeDialog],
  template: `
    <app-publication-include-dialog
      [(visible)]="open"
      [issueName]="issueName()"
      [domainId]="domainId()"
      [domainName]="domainName()"
      [intervalFrom]="intervalFrom()"
      [intervalTo]="intervalTo()"
      [debounceMs]="0"
      [errorKey]="errorKey()"
      [errorDetail]="errorDetail()"
      (submitted)="last.set($event)"
    />
  `,
})
class Host {
  readonly open = signal(false);
  readonly issueName = signal<string | undefined>(undefined);
  readonly domainId = signal<string | undefined>(undefined);
  readonly domainName = signal<string | undefined>(undefined);
  readonly intervalFrom = signal<number | undefined>(undefined);
  readonly intervalTo = signal<number | undefined>(undefined);
  readonly errorKey = signal<string | undefined>(undefined);
  readonly errorDetail = signal<string | undefined>(undefined);
  readonly last = signal<IncludeRequest | undefined>(undefined);
}

async function setup() {
  TestBed.configureTestingModule({
    providers: [
      // The box searches the corpus as it is typed into, so the API service it
      // injects has to be constructible here.
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
      provideHttpClient(),
      provideHttpClientTesting(),
      provideTestTransloco(),
      provideNiordIcons(),
      {
        provide: LanguageService,
        useValue: { activeLocale: signal('en'), selectedLanguage: signal('en') },
      },
    ],
  });
  const fixture = TestBed.createComponent(Host);
  fixture.autoDetectChanges();
  await fixture.whenStable();
  return fixture;
}

const SEARCH_PATH = '/rest/messages/search';

const searchRequests = () =>
  TestBed.inject(HttpTestingController).match((req) => req.url === SEARCH_PATH);

const q = <T extends Element>(sel: string) => document.querySelector(sel) as T | null;

type Fixture = Awaited<ReturnType<typeof setup>>;

async function open(fixture: Fixture) {
  fixture.componentInstance.open.set(true);
  await fixture.whenStable();
}

async function type(fixture: Fixture, selector: string, value: string) {
  const field = q<HTMLInputElement | HTMLTextAreaElement>(selector)!;
  field.value = value;
  field.dispatchEvent(new Event('input', { bubbles: true }));
  await fixture.whenStable();
}

/** What the dialog resolved, which the stub bundle's key-for-key output cannot show. */
function dialog(fixture: Fixture): { domainLabel(): string } {
  return fixture.debugElement.children[0].componentInstance as unknown as { domainLabel(): string };
}

async function submit(fixture: Fixture) {
  q<HTMLButtonElement>('.dialog-panel .btn-primary')!.click();
  await fixture.whenStable();
}

describe('PublicationIncludeDialog', () => {
  afterEach(() => {
    document.querySelectorAll('.cdk-overlay-container').forEach((el) => el.replaceChildren());
  });

  /** Both halves are required: an override with no reason is unreviewable later. */
  it('will not submit without a message and a reason', async () => {
    const fixture = await setup();
    await open(fixture);

    expect(q<HTMLButtonElement>('.dialog-panel .btn-primary')!.disabled).toBe(true);

    await type(fixture, '#publication-include-message', 'NM-123-24');
    expect(q<HTMLButtonElement>('.dialog-panel .btn-primary')!.disabled).toBe(true);

    await submit(fixture);
    expect(fixture.componentInstance.last()).toBeUndefined();
  });

  /** Three characters is the floor the server enforces; the form refuses first. */
  it('refuses a reason shorter than the server accepts', async () => {
    const fixture = await setup();
    await open(fixture);

    await type(fixture, '#publication-include-message', 'NM-123-24');
    await type(fixture, '#publication-include-reason', 'x');

    expect(q<HTMLButtonElement>('.dialog-panel .btn-primary')!.disabled).toBe(true);
  });

  /** Both are measured and submitted TRIMMED, which is what the server counts. */
  it('submits the trimmed message id and reason', async () => {
    const fixture = await setup();
    await open(fixture);

    await type(fixture, '#publication-include-message', '  NM-123-24  ');
    await type(fixture, '#publication-include-reason', '  Requested by the author  ');
    await submit(fixture);

    expect(fixture.componentInstance.last()).toEqual({
      messageId: 'NM-123-24',
      reason: 'Requested by the author',
    });
  });

  /**
   * A refusal is shown WITHOUT clearing the form: the id that failed to resolve is
   * usually a typo, and retyping the reason to fix it is work nobody should redo.
   */
  it('shows a refusal and its server text while keeping what was typed', async () => {
    const fixture = await setup();
    await open(fixture);

    await type(fixture, '#publication-include-message', 'NM-999-24');
    await type(fixture, '#publication-include-reason', 'Requested by the author');

    fixture.componentInstance.errorKey.set('admin.publications.curation.messageNotFound');
    fixture.componentInstance.errorDetail.set('no message has uid NM-999-24');
    await fixture.whenStable();

    const panel = q('.dialog-panel')!;
    expect(panel.textContent).toContain('no message has uid NM-999-24');
    expect(q<HTMLInputElement>('#publication-include-message')!.value).toBe('NM-999-24');
  });

  /** Cleared on the way IN: a leftover id would be applied to a different issue. */
  it('clears the form each time it opens', async () => {
    const fixture = await setup();
    await open(fixture);
    await type(fixture, '#publication-include-message', 'NM-123-24');

    fixture.componentInstance.open.set(false);
    await fixture.whenStable();
    await open(fixture);

    expect(q<HTMLInputElement>('#publication-include-message')!.value).toBe('');
  });

  /**
   * The hint names the corpus the box is searching.
   *
   * The list is one desk's published messages. An admin who does not find the
   * message they are looking for is otherwise left deciding between "it is not
   * published" and "it is not ours".
   */
  it('names the owning desk under the search box', async () => {
    const fixture = await setup();
    fixture.componentInstance.domainId.set('niord-nm');
    fixture.componentInstance.domainName.set('Nautical Notices');
    await open(fixture);

    // The stub bundle prints every key as itself and drops the parameters with
    // it, so the sentence on screen says which of the two was chosen and the
    // dialog itself says what was put into it.
    expect(q('.dialog-panel')!.textContent).not.toContain('messageIdHintAnyDomain');
    expect(dialog(fixture).domainLabel()).toBe('Nautical Notices');
  });

  /** The id names the desk where nothing else does — never `niord-nm` over a name. */
  it('falls back to the desk id where no name was resolved', async () => {
    const fixture = await setup();
    fixture.componentInstance.domainId.set('niord-nm');
    await open(fixture);

    expect(dialog(fixture).domainLabel()).toBe('niord-nm');
  });

  /** A publication no desk owns is searched across all of them, and says so. */
  it('says nothing about a desk where the publication belongs to none', async () => {
    const fixture = await setup();
    await open(fixture);

    expect(q('.dialog-panel')!.textContent).toContain(
      'admin.publications.curation.messageIdHintAnyDomain',
    );
  });

  /** The box searches, and the glyph in it says so before anything is typed. */
  it('marks the message box as a search', async () => {
    const fixture = await setup();
    await open(fixture);

    expect(q('.ac-control app-icon')).not.toBeNull();
  });

  /**
   * No preamble above the fields.
   *
   * What an include does is what the two fields already say, and the sentence
   * stood between the header and the first field on every visit including the
   * hundredth.
   */
  it('opens straight onto the fields', async () => {
    const fixture = await setup();
    await open(fixture);

    expect(q('.dialog-panel')!.textContent).not.toContain(
      'admin.publications.curation.includeEffect',
    );
  });

  /** The header names the issue being added to, so two open tabs cannot be confused. */
  it('names the issue in the header', async () => {
    const fixture = await setup();
    fixture.componentInstance.issueName.set('Week 36 – 2026');
    await open(fixture);

    expect(document.querySelector('.dialog-panel')!.textContent).toContain(
      'admin.publications.curation.includeTitleNamed',
    );
  });
});

/**
 * The box searches rather than demanding an id from memory.
 *
 * An admin working from a title has nothing to type into an id box, and a typo
 * is refused without saying which message was meant.
 */
describe('PublicationIncludeDialog — the message search', () => {
  afterEach(() => {
    document.querySelectorAll('.cdk-overlay-container').forEach((el) => el.replaceChildren());
  });

  /** One character is a prefix of everything; the corpus is not asked about it. */
  it('asks nothing until there is something to search for', async () => {
    const fixture = await setup();
    await open(fixture);
    await type(fixture, '#publication-include-message', 'N');

    expect(searchRequests()).toHaveLength(0);
  });

  /**
   * Published only, and inside the owning desk.
   *
   * An include puts a message into a document about to go out, so a draft nobody
   * has released and a message belonging to another desk are not answers to this
   * question.
   */
  it('searches published messages of the owning domain', async () => {
    const fixture = await setup();
    fixture.componentInstance.domainId.set('niord-nm');
    await open(fixture);
    await type(fixture, '#publication-include-message', 'NM-79');

    const requests = searchRequests();
    expect(requests).toHaveLength(1);
    expect(requests[0].request.params.get('query')).toBe('NM-79');
    expect(requests[0].request.params.get('status')).toBe('PUBLISHED');
    expect(requests[0].request.params.get('domain')).toBe('niord-nm');
  });

  /**
   * Each row says when the message went out and whether that falls inside the
   * issue's period — the commonest reason to add one by hand is that it does not.
   */
  it('offers rows carrying the id, the type suffix, the date and the period note', async () => {
    const fixture = await setup();
    fixture.componentInstance.intervalFrom.set(PERIOD_FROM);
    fixture.componentInstance.intervalTo.set(PERIOD_TO);
    await open(fixture);
    await type(fixture, '#publication-include-message', 'NM-79');

    searchRequests()[0].flush({
      data: [
        {
          id: 'uid-a',
          shortId: 'NM-790-26',
          type: 'TEMPORARY_NOTICE',
          publishDateFrom: PERIOD_FROM - 24 * HOUR,
          descs: [{ lang: 'en', title: 'Anchorland. North Sound. Buoy withdrawn.' }],
        },
      ],
      total: 1,
    });
    await fixture.whenStable();

    const row = document.querySelector('.ac-panel .ac-option')!;
    expect(row.textContent).toContain('NM-790-26');
    expect(row.textContent).toContain('(T)');
    expect(row.textContent).toContain('Anchorland. North Sound. Buoy withdrawn.');
    expect(row.textContent).toContain('admin.publications.curation.beforePeriod');
  });

  /** Picking a row puts the id in the box, which is what the override is addressed by. */
  it('takes the id from the row that was picked', async () => {
    const fixture = await setup();
    await open(fixture);
    await type(fixture, '#publication-include-message', 'NM-79');

    searchRequests()[0].flush({
      data: [{ id: 'uid-a', shortId: 'NM-790-26', descs: [{ lang: 'en', title: 'A notice' }] }],
      total: 1,
    });
    await fixture.whenStable();

    document
      .querySelector('.ac-panel .ac-option')!
      .dispatchEvent(new MouseEvent('mousedown', { bubbles: true, cancelable: true }));
    await fixture.whenStable();

    expect(q<HTMLInputElement>('#publication-include-message')!.value).toBe('NM-790-26');
  });

  /**
   * The whole id typed, then its row picked: the id the dialog keeps does not change, and the
   * box must still show it, or the reader sees no message chosen while Add would add one.
   */
  it('keeps the id in the box when the whole id was typed and its row picked', async () => {
    const fixture = await setup();
    await open(fixture);
    await type(fixture, '#publication-include-message', 'NM-790-26');

    searchRequests()[0].flush({
      data: [{ id: 'uid-a', shortId: 'NM-790-26', descs: [{ lang: 'en', title: 'A notice' }] }],
      total: 1,
    });
    await fixture.whenStable();

    document
      .querySelector('.ac-panel .ac-option')!
      .dispatchEvent(new MouseEvent('mousedown', { bubbles: true, cancelable: true }));
    await fixture.whenStable();

    expect(q<HTMLInputElement>('#publication-include-message')!.value).toBe('NM-790-26');

    await type(fixture, '#publication-include-reason', 'Requested by the author');
    await submit(fixture);
    expect(fixture.componentInstance.last()).toEqual({
      messageId: 'NM-790-26',
      reason: 'Requested by the author',
    });
  });

  /**
   * Free text still goes through.
   *
   * An id pasted from elsewhere resolves server-side, and a message the search
   * cannot reach must not become one nobody can add.
   */
  it('submits what was typed when nothing was picked', async () => {
    const fixture = await setup();
    await open(fixture);
    await type(fixture, '#publication-include-message', 'NM-123-24');
    await type(fixture, '#publication-include-reason', 'Requested by the author');
    searchRequests().forEach((request) => request.flush({ data: [], total: 0 }));
    await fixture.whenStable();
    await submit(fixture);

    expect(fixture.componentInstance.last()).toEqual({
      messageId: 'NM-123-24',
      reason: 'Requested by the author',
    });
  });

  /** A failed search offers nothing rather than stopping somebody who knows the id. */
  it('offers no rows and keeps the box usable when the search fails', async () => {
    const fixture = await setup();
    await open(fixture);
    await type(fixture, '#publication-include-message', 'NM-79');

    searchRequests()[0].flush(null, { status: 500, statusText: 'Server Error' });
    await fixture.whenStable();

    expect(document.querySelector('.ac-panel .ac-option')).toBeNull();
    expect(q<HTMLInputElement>('#publication-include-message')!.value).toBe('NM-79');
  });
});
