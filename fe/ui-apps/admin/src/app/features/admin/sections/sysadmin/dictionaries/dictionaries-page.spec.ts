import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  TestRequest,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { TranslocoService } from '@jsverse/transloco';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { of } from 'rxjs';

import en from '../../../../../../../../../languages/en/i18n/admin/en.json';
import { APP_CONFIG } from '../../../../../core/config/app-config.token';
import { TicketedDownloadService } from '../../../../../core/auth/services/ticketed-download.service';
import { NotificationService } from '../../../../../core/notifications/notification.service';
import { TranslationRefreshService } from '../../../../../core/i18n/translation-refresh.service';
import { provideNiordIcons } from '../../../../../shared/icons/niord-icons';
import { ConfirmService } from '../../../../../shared/services/confirm.service';
import { provideTestTransloco } from '../../../../../shared/testing/transloco-testing';
import { DictionaryEntry } from '../../../services/api/dictionaries-api.service';
import { DictionariesPage } from './dictionaries-page';

interface DeleteInternals {
  deleteEntry: (entry: DictionaryEntry) => void;
}

interface PageInternals {
  filtered: () => readonly DictionaryEntry[];
  showExtended: () => boolean;
  extendedOverride: { set: (value: boolean | undefined) => void };
  onSearch: (value: string) => void;
  copyEntry: (entry: DictionaryEntry) => void;
  editorModel: () => { mode: string; entry: DictionaryEntry } | undefined;
  pager: { first: () => number; setFirst: (first: number) => void };
}

/** The page's config; `s124Enabled` is the backend's computed value the web settings supply. */
interface SetupOptions {
  readonly s124Enabled?: boolean;
}

function setup(entries: unknown[], options: SetupOptions = {}) {
  TestBed.configureTestingModule({
    providers: [
      {
        provide: APP_CONFIG,
        useValue: {
          apiBaseUrl: '/rest',
          appName: 'Niord',
          helpUrls: {},
          ...('s124Enabled' in options ? { s124Enabled: options.s124Enabled } : {}),
        },
      },
      provideHttpClient(),
      provideHttpClientTesting(),
      provideTestTransloco(),
      provideNiordIcons(),
      {
        provide: NotificationService,
        useValue: { success: () => undefined, error: () => undefined },
      },
      { provide: TicketedDownloadService, useValue: { openPublic: () => undefined } },
      // The live-translation push has its own spec; stub it so page tests don't flush its fetches.
      {
        provide: TranslationRefreshService,
        useValue: { refreshActiveLanguage: () => of(undefined) },
      },
    ],
  });
  const fixture = TestBed.createComponent(DictionariesPage);
  fixture.detectChanges();
  const httpTesting = TestBed.inject(HttpTestingController);
  httpTesting.expectOne('/rest/dictionaries/names').flush(['web', 'mail']);
  httpTesting.expectOne('/rest/dictionaries/dictionary/web/entries').flush(entries);
  fixture.detectChanges();
  return { fixture, page: fixture.componentInstance as unknown as PageInternals, httpTesting };
}

describe('DictionariesPage', () => {
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('guesses extended columns from ANY defined longValue — empty string counts as defined', () => {
    const { page } = setup([
      { key: 'a', descs: [{ lang: 'en', value: 'v', longValue: '' }] },
      { key: 'b', descs: [{ lang: 'en', value: 'w' }] },
    ]);
    expect(page.showExtended()).toBe(true);
  });

  it('guesses extended off when no desc carries a longValue; the user can override', () => {
    const { page } = setup([{ key: 'a', descs: [{ lang: 'en', value: 'v' }] }]);
    expect(page.showExtended()).toBe(false);
    page.extendedOverride.set(true);
    expect(page.showExtended()).toBe(true);
  });

  it('every reload overwrites a manual extended toggle with the re-guess', () => {
    const { page, httpTesting } = setup([{ key: 'a', descs: [{ lang: 'en', value: 'v' }] }]);
    page.extendedOverride.set(true);
    expect(page.showExtended()).toBe(true);

    (page as unknown as { onImportClosed: () => void }).onImportClosed();
    TestBed.tick();
    httpTesting
      .expectOne('/rest/dictionaries/dictionary/web/entries')
      .flush([{ key: 'a', descs: [{ lang: 'en', value: 'v' }] }]);
    expect(page.showExtended()).toBe(false); // override discarded, guess wins
  });

  it('filters on key OR any-language value; long values only while extended is shown', () => {
    const { page } = setup([
      { key: 'msg.title', descs: [{ lang: 'da', value: 'Titel' }] },
      { key: 'msg.body', descs: [{ lang: 'en', value: 'Body', longValue: 'Extended body' }] },
      { key: 'other', descs: [{ lang: 'en', value: 'x' }] },
    ]);

    page.onSearch('titel'); // value match, case-insensitive, any language
    expect(page.filtered().map((e) => e.key)).toEqual(['msg.title']);

    page.onSearch('msg.'); // key match
    expect(page.filtered().length).toBe(2);

    page.onSearch('extended body'); // longValue match — extended IS shown (guessed on)
    expect(page.filtered().map((e) => e.key)).toEqual(['msg.body']);

    page.extendedOverride.set(false); // hide extended → longValue leaves the filter
    page.onSearch('extended body');
    expect(page.filtered().length).toBe(0);
  });

  it('copy opens the drawer in add mode with the id stripped', () => {
    const source = { id: 7, key: 'k', descs: [{ lang: 'en', value: 'v' }] };
    const { page } = setup([source]);
    page.copyEntry(page.filtered()[0]);
    const model = page.editorModel();
    expect(model?.mode).toBe('add');
    expect(model?.entry.id).toBeUndefined();
    expect(model?.entry.key).toBe('k');
  });

  describe('delete confirmation', () => {
    const entry = { key: 'position.deg', descs: [{ lang: 'en', value: 'degrees' }] };
    const keyUsersUrl = '/rest/dictionaries/dictionary/web/key-users';
    const t = en.admin.dictionaries.confirmDelete;

    afterEach(() => {
      document.querySelectorAll('.cdk-overlay-container').forEach((el) => el.replaceChildren());
    });

    /** Opens the delete for `entry`, answers key-users with `answer`, returns the confirm. */
    function openDelete(answer: (req: TestRequest) => void, options: SetupOptions = {}) {
      const ctx = setup([entry], options);
      TestBed.inject(TranslocoService).setTranslation(en, 'en', { merge: true });
      (ctx.page as unknown as DeleteInternals).deleteEntry(ctx.page.filtered()[0]);
      const req = ctx.httpTesting.expectOne((r) => r.url === keyUsersUrl);
      expect(req.request.params.get('key')).toBe('position.deg');
      answer(req);
      ctx.fixture.detectChanges();
      const confirm = ctx.fixture.debugElement.injector.get(ConfirmService);
      return { ...ctx, confirm, details: () => confirm.active()?.details ?? [] };
    }

    it('lists the script resources and what each reader does without the key', () => {
      const { details } = openDelete((req) =>
        req.flush({
          templates: ['templates/a.ftl', 'templates/b.ftl'],
          readers: ['AUDIO_TEXT', 'LAT_LON_DIRECTIVE', 'SOMETHING_NEWER'],
        }),
      );
      expect(details()).toEqual([
        'Used by the script resource templates/a.ftl.',
        'Used by the script resource templates/b.ftl.',
        t.reader.audioText,
        t.reader.latLonDirective,
        t.reader.other,
        t.stays,
      ]);
      // The lines reach the dialog as a list under the message.
      const items = Array.from(document.querySelectorAll('.confirm-details li')).map((li) =>
        li.textContent?.trim(),
      );
      expect(items).toEqual(details());
    });

    it('names the runtime-built prefix a script resource was found by', () => {
      const { details } = openDelete((req) =>
        req.flush({
          templates: ['templates/message-support.ftl'],
          prefix: 'msg.status.',
          readers: [],
        }),
      );
      expect(details()).toEqual([
        "Used by the script resource templates/message-support.ftl, by name or by building keys from 'msg.status.'.",
        t.stays,
      ]);
    });

    it('names at most ten script resources and counts the rest', () => {
      const templates = Array.from({ length: 13 }, (_, i) => `templates/t${i}.ftl`);
      const { details } = openDelete((req) => req.flush({ templates, readers: [] }));
      const lines = details();
      expect(lines.length).toBe(12); // 10 paths, the 'more' line, the 'stays' line
      expect(lines[0]).toBe('Used by the script resource templates/t0.ftl.');
      expect(lines[9]).toBe('Used by the script resource templates/t9.ftl.');
      expect(lines[10]).toBe('And 3 more script resource(s).');
      expect(lines[11]).toBe(t.stays);
    });

    it('says so when nothing uses the key', () => {
      const { details } = openDelete((req) =>
        req.flush({ templates: [], readers: [], paramTypes: [] }),
      );
      expect(details()).toEqual([t.usedBy.none, t.stays]);
    });

    it('with S-124 off, a key only the S-124 agency reads is used by nothing', () => {
      const { details } = openDelete(
        (req) => req.flush({ templates: [], readers: ['S124_AGENCY'], paramTypes: [] }),
        { s124Enabled: false },
      );
      expect(details()).toEqual([t.usedBy.none, t.stays]);
      expect(details()).not.toContain(t.reader.s124Agency);
    });

    it('with S-124 off, the cancellation warning still reads the key', () => {
      const { details } = openDelete(
        (req) =>
          req.flush({
            templates: [],
            readers: ['MESSAGE_CANCELLATION', 'S124_AGENCY'],
            paramTypes: [],
          }),
        { s124Enabled: false },
      );
      expect(details()).toEqual([t.reader.messageCancellation, t.stays]);
    });

    it.each<[string, SetupOptions]>([
      ['on', { s124Enabled: true }],
      ['not reported', {}],
    ])('with S-124 %s, the S-124 agency reader is named', (_, options) => {
      const { details } = openDelete(
        (req) => req.flush({ templates: [], readers: ['S124_AGENCY'], paramTypes: [] }),
        options,
      );
      expect(details()).toEqual([t.reader.s124Agency, t.stays]);
    });

    it('names the parameter types that list the key first, since the delete is refused while they do', () => {
      const { details } = openDelete((req) =>
        req.flush({
          templates: ['templates/a.ftl'],
          readers: [],
          paramTypes: ['aton_lights', 'buoy_types'],
        }),
      );
      expect(details()).toEqual([
        'Listed by the parameter types aton_lights, buoy_types; the delete is refused while they list it.',
        'Used by the script resource templates/a.ftl.',
        t.stays,
      ]);
    });

    it('a key only a parameter type lists is not called unused', () => {
      const { details } = openDelete((req) =>
        req.flush({ templates: [], readers: [], paramTypes: ['aton_lights'] }),
      );
      expect(details()).toEqual([
        'Listed by the parameter types aton_lights; the delete is refused while they list it.',
        t.stays,
      ]);
    });

    it("a refused delete (409) says so with the backend's reason; another failure keeps the plain error", () => {
      const notifications = { success: vi.fn(), error: vi.fn() };
      TestBed.overrideProvider(NotificationService, { useValue: notifications });
      const { confirm, httpTesting } = openDelete((req) =>
        req.flush({ templates: [], readers: [], paramTypes: ['aton_lights'] }),
      );
      const detail = "The entry 'position.deg' is listed by the parameter types aton_lights";
      confirm.active()!.accept();
      httpTesting
        .expectOne('/rest/dictionaries/dictionary/web/position.deg')
        .flush(
          { type: 'about:blank', title: 'Conflict', status: 409, detail },
          { status: 409, statusText: 'Conflict' },
        );
      expect(notifications.error).toHaveBeenCalledWith('admin.dictionaries.toast.deleteRefused', {
        detail,
      });
      expect(notifications.success).not.toHaveBeenCalled();
    });

    it('a delete failing otherwise shows the plain delete error', () => {
      const notifications = { success: vi.fn(), error: vi.fn() };
      TestBed.overrideProvider(NotificationService, { useValue: notifications });
      const { confirm, httpTesting } = openDelete((req) =>
        req.flush({ templates: [], readers: [], paramTypes: [] }),
      );
      confirm.active()!.accept();
      httpTesting
        .expectOne('/rest/dictionaries/dictionary/web/position.deg')
        .flush(
          { type: 'about:blank', title: 'Server Error', status: 500, detail: 'boom' },
          { status: 500, statusText: 'Server Error' },
        );
      expect(notifications.error).toHaveBeenCalledWith('admin.dictionaries.toast.deleteError');
    });

    it('a failed check still opens the confirm, saying so, and accepting still deletes', () => {
      const { details, confirm, httpTesting } = openDelete((req) =>
        req.flush('', { status: 404, statusText: 'Not Found' }),
      );
      expect(details()).toEqual([t.usedBy.unknown, t.stays]);

      confirm.active()!.accept();
      const del = httpTesting.expectOne('/rest/dictionaries/dictionary/web/position.deg');
      expect(del.request.method).toBe('DELETE');
      del.flush({});
      TestBed.tick();
      httpTesting.expectOne('/rest/dictionaries/dictionary/web/entries').flush([]);
    });

    it('a check that does not answer within five seconds opens the confirm without it', () => {
      const ctx = setup([entry]);
      TestBed.inject(TranslocoService).setTranslation(en, 'en', { merge: true });
      const confirm = ctx.fixture.debugElement.injector.get(ConfirmService);
      vi.useFakeTimers();
      try {
        (ctx.page as unknown as DeleteInternals).deleteEntry(ctx.page.filtered()[0]);
        const req = ctx.httpTesting.expectOne((r) => r.url === keyUsersUrl);
        vi.advanceTimersByTime(4999);
        expect(confirm.active()).toBeUndefined();

        vi.advanceTimersByTime(2);
        ctx.fixture.detectChanges();
        expect(req.cancelled).toBe(true);
        expect(confirm.active()?.details).toEqual([t.usedBy.unknown, t.stays]);
      } finally {
        vi.useRealTimers();
      }
    });

    it("a second click drops the first click's check: only the last answer opens a confirm", () => {
      const other = { key: 'position.min', descs: [{ lang: 'en', value: 'minutes' }] };
      const ctx = setup([entry, other]);
      TestBed.inject(TranslocoService).setTranslation(en, 'en', { merge: true });
      const confirm = ctx.fixture.debugElement.injector.get(ConfirmService);
      const [first, second] = ctx.page.filtered();
      const deleteEntry = (ctx.page as unknown as DeleteInternals).deleteEntry;
      deleteEntry.call(ctx.page, first);
      deleteEntry.call(ctx.page, second);

      const [firstReq, secondReq] = ctx.httpTesting.match((r) => r.url === keyUsersUrl);
      expect(firstReq.request.params.get('key')).toBe('position.deg');
      expect(secondReq.request.params.get('key')).toBe('position.min');
      // The first check is cancelled, so its answer can never arrive; the second is the only one open.
      expect(firstReq.cancelled).toBe(true);
      expect(secondReq.cancelled).toBe(false);
      expect(confirm.active()).toBeUndefined();

      secondReq.flush({ templates: [], readers: [] });
      ctx.fixture.detectChanges();
      expect(confirm.active()?.message).toContain('position.min');
      expect(confirm.active()?.message).not.toContain('position.deg');
    });

    it('rejecting deletes nothing', () => {
      const { confirm, fixture } = openDelete((req) => req.flush({ templates: [], readers: [] }));
      (document.querySelector('.confirm-foot .btn-tertiary') as HTMLButtonElement).click();
      fixture.detectChanges();
      expect(confirm.active()).toBeUndefined();
      // afterEach's verify() fails on any DELETE that was sent.
    });
  });

  it("the cog's Import opens the dialog that previews before it writes, not the one-file upload", async () => {
    const { fixture, page, httpTesting } = setup([]);
    (page as unknown as { cogItems: () => { command?: () => void }[] }).cogItems()[1].command!();
    await fixture.whenStable();
    const doc = fixture.nativeElement.ownerDocument as Document;
    expect(doc.querySelector('#dictionaryImportFile')).not.toBeNull();
    expect(doc.querySelector('#jsonImportFile')).toBeNull();
    httpTesting.expectNone('/rest/dictionaries/import-preview');
    document.querySelectorAll('.cdk-overlay-container').forEach((el) => el.replaceChildren());
  });

  it('filter changes reset paging to the first page', () => {
    const { page } = setup(
      Array.from({ length: 45 }, (_, i) => ({
        key: `key-${i}`,
        descs: [{ lang: 'en', value: `v${i}` }],
      })),
    );
    page.pager.setFirst(40);
    expect(page.pager.first()).toBe(40);
    page.onSearch('key');
    expect(page.pager.first()).toBe(0);
  });
});
