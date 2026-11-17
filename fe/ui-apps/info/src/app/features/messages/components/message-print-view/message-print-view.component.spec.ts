import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NO_ERRORS_SCHEMA } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { TranslocoService } from '@jsverse/transloco';
import { EMPTY, of } from 'rxjs';
import { MessagePrintViewComponent } from './message-print-view.component';
import { provideTestNiordConfig } from '../../../../testing/test-config';
import { settle } from '../../../../testing/settle';

function makeRoute(queryParams: Record<string, string | string[]> = {}) {
  return {
    snapshot: {
      queryParams,
      queryParamMap: convertToParamMap(queryParams),
    },
  };
}

describe('MessagePrintViewComponent', () => {
  let fixture: ComponentFixture<MessagePrintViewComponent>;
  let component: MessagePrintViewComponent;
  let httpTesting: HttpTestingController;
  let translocoSpy: Record<string, ReturnType<typeof vi.fn> | typeof EMPTY>;

  const BUNDLE: Record<string, string> = {
    PRINT_DATE_FORMAT: "d MMMM yyyy 'at' HH:mm",
    PRINT_BANNER: 'NtM {{nw}} / {{nm}} — {{date}}',
    MENU_NW: 'NW',
    MENU_NM: 'NM',
    DATE_MONTHS_WIDE:
      'January|February|March|April|May|June|July|August|September|October|November|December',
  };

  function setup(queryParams: Record<string, string | string[]> = {}, timeZone?: string) {
    TestBed.resetTestingModule();
    translocoSpy = {
      translate: vi.fn((k: string, params?: Record<string, string>) => {
        const value = BUNDLE[k] ?? k;
        return params
          ? value.replace(/\{\{(\w+)\}\}/g, (_, name: string) => params[name] ?? '')
          : value;
      }),
      // The banner's own pattern and the month words both reach the sheet as signals, so it
      // redraws when the bundle lands instead of waiting for it before reading anything.
      selectTranslate: vi.fn((k: string) => of(BUNDLE[k] ?? k)),
      setActiveLang: vi.fn(),
      setAvailableLangs: vi.fn(),
      selectTranslation: vi.fn(() => of({})),
      events$: EMPTY,
    };

    TestBed.configureTestingModule({
      imports: [MessagePrintViewComponent],
      providers: [
        provideTestNiordConfig(timeZone ? { timeZone } : undefined),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: ActivatedRoute, useValue: makeRoute(queryParams) },
        { provide: TranslocoService, useValue: translocoSpy },
      ],
      schemas: [NO_ERRORS_SCHEMA],
    });

    fixture = TestBed.createComponent(MessagePrintViewComponent);
    component = fixture.componentInstance;
    httpTesting = TestBed.inject(HttpTestingController);
  }

  /** The list read, which every sheet but a single-message one issues. */
  function expectList() {
    return httpTesting.expectOne((r) => r.url.includes('/rest/public/v1/messages'));
  }

  afterEach(() => httpTesting.verify());

  it('should set language from ?language= param', async () => {
    setup({ language: 'zz' });
    await settle();
    const request = expectList();
    // The window is opened with `language`; the public API reads the language as `lang` only.
    expect(request.request.params.get('lang')).toBe('zz');
    expect(request.request.params.has('language')).toBe(false);
    request.flush([]);
    await settle();
  });

  it('should set language from ?lang= param as alias', async () => {
    setup({ lang: 'zz' });
    await settle();
    const request = expectList();
    expect(request.request.params.get('lang')).toBe('zz');
    request.flush([]);
    await settle();
  });

  it('should prefer ?language= over ?lang=', async () => {
    setup({ language: 'zz', lang: 'en' });
    await settle();
    const request = expectList();
    expect(request.request.params.get('lang')).toBe('zz');
    request.flush([]);
    await settle();
  });

  // The banner carries the moment the sheet was printed, and a printed sheet is read next to
  // the notices it lists — so the clock on it is the deployment's, never the machine's that
  // happened to print it. The runner sits in America/St_Johns, half an hour off both zones.
  it('stamps the banner with the print time of the configured zone (east of UTC)', async () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2024-01-01T01:00:00Z'));
    setup({}, 'Europe/Copenhagen');
    await settle();
    expectList().flush([]);
    await settle();
    expect(component.bannerText()).toBe('NtM NW / NM — 1 January 2024 at 02:00');
    vi.useRealTimers();
  });

  it('stamps the banner with the print time of the configured zone (west of UTC)', async () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2024-01-01T01:00:00Z'));
    setup({}, 'America/Halifax');
    await settle();
    expectList().flush([]);
    await settle();
    expect(component.bannerText()).toBe('NtM NW / NM — 31 December 2023 at 21:00');
    vi.useRealTimers();
  });

  it('should fetch single message when messageId param present', async () => {
    setup({ messageId: 'msg-42' });
    await settle();
    const request = httpTesting.expectOne((r) => r.url.includes('/rest/public/v1/message/'));
    expect(request.request.url).toContain('msg-42');
    expect(request.request.urlWithParams).toContain('externalize=false');
    request.flush({ id: 'msg-42', mainType: 'NW', descs: [{ lang: 'en' }] });
    await settle();
    expect(component.messages()).toHaveLength(1);
    // One message is printed on its own, without the area heading a list is grouped by.
    expect(component.areaHeadings().size).toBe(0);
  });

  it('should include mainType params in multi-message URL', async () => {
    setup({ mainType: ['NW', 'NM'], areaId: 'dk' });
    await settle();
    const request = expectList();
    expect(request.request.urlWithParams).toContain('mainType=NW');
    expect(request.request.urlWithParams).toContain('mainType=NM');
    expect(request.request.urlWithParams).toContain('areaId=dk');
    request.flush([]);
    await settle();
  });

  it('should pass publication param through to API', async () => {
    setup({ publication: 'pub-1' });
    await settle();
    const request = expectList();
    expect(request.request.urlWithParams).toContain('publication=pub-1');
    request.flush([]);
    await settle();
  });

  it('should include active param in URL', async () => {
    setup({ active: 'true', mainType: 'NW' });
    await settle();
    const request = expectList();
    expect(request.request.urlWithParams).toContain('active=true');
    request.flush([]);
    await settle();
  });

  it('groups a printed list by area and narrows it to the sub-areas it was opened with', async () => {
    const sound = { id: 11, parent: { id: 453 }, descs: [{ lang: 'en', name: 'The Sound' }] };
    const belt = { id: 22, parent: { id: 453 }, descs: [{ lang: 'en', name: 'The Belt' }] };
    setup({ areaId: '453', subAreaId: ['11'] });
    await settle();
    expectList().flush([
      { id: 'msg-1', mainType: 'NW', areas: [sound] },
      { id: 'msg-2', mainType: 'NW', areas: [belt] },
    ]);
    await settle();

    expect(component.messages().map((message) => message.id)).toEqual(['msg-1']);
    expect(component.areaHeadings().get('msg-1')?.id).toBe(11);
  });

  it('says so when the list read fails', async () => {
    setup();
    await settle();
    expectList().flush('nope', { status: 500, statusText: 'Server Error' });
    await settle();

    expect(component.error()).toBe('MSGS_LOAD_FAILED');
    expect(component.messages()).toHaveLength(0);
  });

  it('says so when the read of one message fails', async () => {
    setup({ messageId: 'msg-42' });
    await settle();
    httpTesting
      .expectOne((r) => r.url.includes('/rest/public/v1/message/'))
      .flush('nope', { status: 500, statusText: 'Server Error' });
    await settle();

    expect(component.error()).toBe('MSG_LOAD_FAILED');
    expect(component.messages()).toHaveLength(0);
  });
});
