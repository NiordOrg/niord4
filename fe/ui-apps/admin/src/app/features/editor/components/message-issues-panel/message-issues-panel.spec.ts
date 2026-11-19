import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';

import { APP_CONFIG } from '../../../../core/config/app-config.token';
import { LanguageService } from '../../../../core/main-shell/services/language.service';
import { LocaleFormatService } from '../../../../core/main-shell/services/locale-format.service';
import { provideNiordIcons } from '../../../../shared/icons/niord-icons';
import { provideTestTransloco } from '../../../../shared/testing/transloco-testing';
import type { MessageIssueRef } from '../../../messages/models/message-issue.model';
import { MessageIssuesPanel } from './message-issues-panel';

@Component({
  imports: [MessageIssuesPanel],
  template: `<app-message-issues-panel [messageUid]="uid()" />`,
})
class Host {
  readonly uid = signal<string | undefined>('msg-1');
}

/** Zoneless: drain the effect scheduler + microtask queue by hand. */
async function settle(): Promise<void> {
  TestBed.tick();
  await Promise.resolve();
  TestBed.tick();
  await Promise.resolve();
}

function issue(overrides: Partial<MessageIssueRef> = {}): MessageIssueRef {
  return {
    issuePublicId: 'efs-29-2026',
    seriesId: 'dma-efs',
    status: 'PUBLISHED',
    membership: 'FROZEN',
    names: { da: 'EfS uge 29, 2026', en: 'NtM week 29, 2026' },
    seriesNames: { da: 'Efterretninger', en: 'Notices to Mariners' },
    ...overrides,
  };
}

async function setup(appLanguage = 'da', configure?: (host: Host) => void) {
  TestBed.configureTestingModule({
    providers: [
      provideTestTransloco(),
      provideNiordIcons(),
      provideHttpClient(),
      provideHttpClientTesting(),
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
      { provide: LanguageService, useValue: { selectedLanguage: signal(appLanguage) } },
      { provide: LocaleFormatService, useValue: { formatDate: () => '15.07.2026' } },
    ],
  });

  const fixture = TestBed.createComponent(Host);
  configure?.(fixture.componentInstance);
  fixture.detectChanges();
  await settle();

  return { fixture, host: fixture.componentInstance, http: TestBed.inject(HttpTestingController) };
}

describe('MessageIssuesPanel', () => {
  /**
   * An unsaved message has no uid, and there is nothing to ask about.
   *
   * The message editor mounts this panel before the message has finished
   * loading, so a component that fetched unconditionally would fire a request
   * whose only possible answer is "nothing" — on every message opened.
   */
  it('asks nothing when there is no message uid', async () => {
    const { http } = await setup('da', (host) => host.uid.set(undefined));

    http.expectNone(() => true);
    http.verify();
  });

  /**
   * The uid reaches the URL encoded, and the panel renders what comes back.
   *
   * The URL is asserted whole rather than by substring: the panel asked
   * `/for-message/` for months against a backend that only ever served
   * `/by-message/`, and a substring match on the uid passed the entire time.
   */
  it('renders a frozen membership as published', async () => {
    const { fixture, http } = await setup();

    http.expectOne('/rest/publication-issues/by-message/msg-1').flush([issue()]);
    await settle();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('EfS uge 29, 2026');
    expect(text).toContain('Efterretninger');

    const badge = (fixture.nativeElement as HTMLElement).querySelector('.status-badge');
    expect(badge?.className).toContain('is-issue-published');
  });

  /**
   * An open issue's membership is a prediction, and must not read as published.
   *
   * This is the assertion the whole panel exists for. The row was resolved when
   * the panel loaded and can still change before the cut-off; rendering it with
   * the settled tint tells an editor a correction is too late when it is not.
   */
  it('marks a live membership as pending and explains that it can still change', async () => {
    const { fixture, http } = await setup();

    http
      .expectOne('/rest/publication-issues/by-message/msg-1')
      .flush([issue({ status: 'OPEN', membership: 'LIVE', publishedAt: undefined })]);
    await settle();

    const badge = (fixture.nativeElement as HTMLElement).querySelector('.status-badge');
    expect(badge?.className).toContain('is-issue-open');
    expect(badge?.className).not.toContain('is-issue-published');

    // The caveat is shown only when there is a pending row to caveat.
    expect((fixture.nativeElement as HTMLElement).textContent).toContain(
      'editor.status.publications.pendingHint',
    );
    // And a pending row is not a missed one.
    expect((fixture.nativeElement as HTMLElement).textContent).not.toContain(
      'editor.status.publications.missedHint',
    );
  });

  /**
   * A released issue whose period holds the message's date, without the message,
   * is a warning and not a membership.
   *
   * The message was published into that period after the issue went out, so it
   * is in no document of it. Drawn as pending it would read "will be in this
   * issue" — the opposite of the truth — and drawn as published it would claim a
   * document that does not carry it.
   */
  it('marks an issue that missed the message with a warning and says how to take it in', async () => {
    const { fixture, http } = await setup();

    http.expectOne('/rest/publication-issues/by-message/msg-1').flush([
      issue({
        membership: 'MISSED',
        intervalFrom: 1_783_900_800_000,
        publishedAt: 1_784_505_600_000,
        links: { da: '/pub/efs-29.pdf' },
      }),
    ]);
    await settle();

    const element = fixture.nativeElement as HTMLElement;
    const badge = element.querySelector('.status-badge');
    expect(badge?.textContent).toContain('editor.status.publications.missed');
    expect(badge?.className).toContain('is-gap-MISSING');
    expect(badge?.className).not.toContain('is-issue-published');
    expect(badge?.className).not.toContain('is-issue-open');
    expect(element.textContent).toContain('EfS uge 29, 2026');
    expect(element.textContent).toContain('editor.status.publications.missedHint');
    expect(element.textContent).not.toContain('editor.status.publications.pendingHint');
    // The issue's document does not hold the message, so it is not offered as where it is.
    expect(element.querySelector('a[href]')).toBeNull();
  });

  /** Only a missed row carries the hint about one. */
  it('omits the missed hint when no issue missed the message', async () => {
    const { fixture, http } = await setup();

    http
      .expectOne('/rest/publication-issues/by-message/msg-1')
      .flush([issue(), issue({ seriesId: 'dma-pt', status: 'OPEN', membership: 'LIVE' })]);
    await settle();

    expect((fixture.nativeElement as HTMLElement).textContent).not.toContain(
      'editor.status.publications.missedHint',
    );
  });

  /**
   * A membership this build does not know is drawn as none of the known ones.
   *
   * The old panel drew every row that was not frozen as pending, so a new value
   * from a newer server read as "will be in this issue". Unknown, it is named
   * and dated, and makes no claim.
   */
  it('draws a membership it does not know as neither published nor pending', async () => {
    const { fixture, http } = await setup();

    http
      .expectOne('/rest/publication-issues/by-message/msg-1')
      .flush([issue({ membership: 'SOMETHING_NEWER', links: { da: '/pub/efs-29.pdf' } })]);
    await settle();

    const element = fixture.nativeElement as HTMLElement;
    expect(element.textContent).toContain('EfS uge 29, 2026');
    expect(element.querySelector('.status-badge')).toBeNull();
    expect(element.querySelector('a[href]')).toBeNull();
    expect(element.textContent).not.toContain('editor.status.publications.pendingHint');
    expect(element.textContent).not.toContain('editor.status.publications.missedHint');
  });

  /** With everything already out, there is nothing pending and no caveat to show. */
  it('omits the pending caveat when every membership is settled', async () => {
    const { fixture, http } = await setup();

    http.expectOne('/rest/publication-issues/by-message/msg-1').flush([issue()]);
    await settle();

    expect((fixture.nativeElement as HTMLElement).textContent).not.toContain(
      'editor.status.publications.pendingHint',
    );
  });

  /** Labels follow the app language. */
  it('labels each issue in the active language', async () => {
    const { fixture, http } = await setup('en');

    http.expectOne('/rest/publication-issues/by-message/msg-1').flush([issue()]);
    await settle();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('NtM week 29, 2026');
    expect(text).not.toContain('EfS uge 29, 2026');
  });

  /**
   * An issue named in only one language still shows, under that name.
   *
   * Blanking the label would hide a real publication behind a translation gap —
   * the row would render as an unnamed badge saying the message is out somewhere.
   */
  it('falls back to another language rather than showing an unnamed row', async () => {
    const { fixture, http } = await setup('en');

    http
      .expectOne('/rest/publication-issues/by-message/msg-1')
      .flush([issue({ names: { da: 'Kun dansk' }, seriesNames: undefined })]);
    await settle();

    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Kun dansk');
  });

  /**
   * A link is offered only for an issue that exists as a document.
   *
   * An open issue can carry a link pattern that resolves to a file nothing has
   * written yet, and an editor following it gets a 404 from the screen that just
   * told them where the message was published.
   */
  it('links only a published issue', async () => {
    const { fixture, http } = await setup();

    http.expectOne('/rest/publication-issues/by-message/msg-1').flush([
      issue({ links: { da: '/pub/efs-29.pdf' } }),
      issue({
        issuePublicId: 'efs-30-2026',
        status: 'OPEN',
        membership: 'LIVE',
        names: { da: 'EfS uge 30, 2026' },
        links: { da: '/pub/efs-30.pdf' },
      }),
    ]);
    await settle();

    const hrefs = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('a[href]'),
    ).map((a) => a.getAttribute('href'));
    // Absolute: the address is reported root-relative from the host that serves
    // the document, and the editor is not always on that host. Here the API base
    // is relative, so the two share the page's own origin.
    expect(hrefs).toEqual([`${document.location.origin}/pub/efs-29.pdf`]);
  });

  /**
   * A row standing for many issues says how many; a row standing for one does not.
   *
   * "1 issue" beside a row that already names that issue is a word that says
   * nothing, and it would appear on almost every row in the panel.
   */
  it('shows the issue count only when the row stands for more than one issue', async () => {
    const { fixture, http } = await setup();

    http
      .expectOne('/rest/publication-issues/by-message/msg-1')
      .flush([
        issue({ seriesId: 'dma-pt', issueCount: 104 }),
        issue({ issuePublicId: 'efs-29', seriesId: 'dma-efs', issueCount: 1 }),
      ]);
    await settle();

    const counts = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('li')).map(
      (li) => li.textContent?.includes('editor.status.publications.issueCount'),
    );
    expect(counts).toEqual([true, false]);
  });

  /**
   * A frozen membership in a RETIRED issue is not a publication on the site.
   *
   * The issue carries its status separately from the membership on purpose: the
   * record of the release survives the withdrawal, and reading only the
   * membership badges a taken-down document green.
   */
  it('marks a frozen membership in a retired issue as withdrawn', async () => {
    const { fixture, http } = await setup();

    http
      .expectOne('/rest/publication-issues/by-message/msg-1')
      .flush([issue({ status: 'RETIRED' })]);
    await settle();

    const badge = (fixture.nativeElement as HTMLElement).querySelector('.status-badge');
    expect(badge?.textContent).toContain('editor.status.publications.withdrawn');
    expect(badge?.className).not.toContain('is-issue-published');
    expect(badge?.className).not.toContain('is-issue-open');
  });

  /**
   * A frozen membership in an issue that is still open claims nothing.
   *
   * Only a published or retired issue's rows are a record; an open issue's rows
   * are the old system's member list an import copied, which the publish
   * rewrites. Badged as published, they told an editor the notice was out.
   */
  it('does not call a frozen membership in an open issue published', async () => {
    const { fixture, http } = await setup();

    http
      .expectOne('/rest/publication-issues/by-message/msg-1')
      .flush([issue({ status: 'OPEN', membership: 'FROZEN', publishedAt: undefined })]);
    await settle();

    const element = fixture.nativeElement as HTMLElement;
    expect(element.textContent).toContain('EfS uge 29, 2026');
    expect(element.querySelector('.is-issue-published')).toBeNull();
    expect(element.textContent).not.toContain('editor.status.publications.published');
  });

  /**
   * A failed fetch says so instead of claiming the message is in nothing.
   *
   * As a banner with a retry, not a grey paragraph: "could not load" and "not in
   * any publication" set in the identical muted line are the one distinction the
   * panel exists to make, and a failed load left the reader no way to re-ask.
   */
  it('reports a failed load as a banner with a way to retry', async () => {
    const { fixture, http } = await setup();

    http
      .expectOne('/rest/publication-issues/by-message/msg-1')
      .flush('boom', { status: 500, statusText: 'Server Error' });
    await settle();

    const element = fixture.nativeElement as HTMLElement;
    const text = element.textContent ?? '';
    expect(text).toContain('editor.status.publications.error');
    expect(text).not.toContain('editor.status.publications.empty');
    expect(element.querySelector('app-error-banner')).not.toBeNull();

    // The retry is wired, not decoration: clicking it re-asks the same URL.
    element.querySelector<HTMLButtonElement>('app-error-banner button')?.click();
    await settle();
    http.expectOne('/rest/publication-issues/by-message/msg-1').flush([]);
  });

  /**
   * Waiting is its own state, and it does not read as a finished answer.
   *
   * The progress bar is what separates "still asking" from "asked, and the
   * answer is nothing" — three states that were three identical paragraphs.
   */
  it('shows a progress bar while the answer is still outstanding', async () => {
    const { fixture, http } = await setup();

    const element = fixture.nativeElement as HTMLElement;
    expect(element.querySelector('app-progress-bar')).not.toBeNull();
    expect(element.textContent).not.toContain('editor.status.publications.empty');

    http.expectOne('/rest/publication-issues/by-message/msg-1').flush([]);
    await settle();

    expect(element.querySelector('app-progress-bar')).toBeNull();
  });

  /** No memberships is a real answer, and it is said out loud. */
  it('says the message is in no publication when the list comes back empty', async () => {
    const { fixture, http } = await setup();

    http.expectOne('/rest/publication-issues/by-message/msg-1').flush([]);
    await settle();

    expect((fixture.nativeElement as HTMLElement).textContent).toContain(
      'editor.status.publications.empty',
    );
  });
});
