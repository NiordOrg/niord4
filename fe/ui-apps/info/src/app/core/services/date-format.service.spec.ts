import { computed, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { TranslocoService } from '@jsverse/transloco';
import { firstValueFrom } from 'rxjs';
import { DateFormatService } from './date-format.service';
import {
  getEmptyTranslocoTestingModule,
  getTranslocoTestingModule,
  provideTestNiordConfig,
  TEST_TRANSLATIONS,
} from '../../testing/test-config';

const INSTANT = '2024-01-01T01:00:00Z';

async function setUp(timeZone = 'Europe/Copenhagen'): Promise<DateFormatService> {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({
    imports: [getTranslocoTestingModule()],
    providers: [provideTestNiordConfig({ timeZone })],
  });
  const service = TestBed.inject(DateFormatService);
  // The bundles are preloaded by the testing module; one turn lets the signals pick them up.
  await firstValueFrom(TestBed.inject(TranslocoService).load('en'));
  return service;
}

/** A site whose English bundle has not arrived yet; `arrive()` delivers it and nothing else. */
function setUpBeforeTheBundle(): { service: DateFormatService; arrive: () => void } {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({
    imports: [getEmptyTranslocoTestingModule()],
    providers: [provideTestNiordConfig()],
  });
  return {
    service: TestBed.inject(DateFormatService),
    arrive: () => TestBed.inject(TranslocoService).setTranslation(TEST_TRANSLATIONS['en'], 'en'),
  };
}

describe('DateFormatService', () => {
  afterEach(() => vi.useRealTimers());

  // The month words are the bundle's, which no browser locale data supplies.
  it('renders a source date in the configured zone, in the active language', async () => {
    const service = await setUp();
    TestBed.inject(TranslocoService).setActiveLang('zz');
    expect(service.format(INSTANT, 'SOURCE_DATE_FORMAT')).toBe('1 [Jánúáry] 2024');
  });

  // The same instant, the same language, a zone five hours further west: a different day. This
  // is the assertion a renderer reading the browser's clock cannot satisfy for both zones.
  it('reads the zone from the configuration, not from the runner', async () => {
    const service = await setUp('America/Halifax');
    TestBed.inject(TranslocoService).setActiveLang('zz');
    expect(service.format(INSTANT, 'SOURCE_DATE_FORMAT')).toBe('31 [Décémbér] 2023');
  });

  it('accepts an ISO string, epoch millis and a Date alike', async () => {
    const service = await setUp();
    const iso = service.format(INSTANT, 'SOURCE_DATE_FORMAT');
    expect(service.format(Date.parse(INSTANT), 'SOURCE_DATE_FORMAT')).toBe(iso);
    expect(service.format(new Date(INSTANT), 'SOURCE_DATE_FORMAT')).toBe(iso);
    expect(iso).toBe('1 January 2024');
  });

  it('renders nothing for a value that is not an instant', async () => {
    const service = await setUp();
    expect(service.format(undefined, 'SOURCE_DATE_FORMAT')).toBe('');
    expect(service.format('', 'SOURCE_DATE_FORMAT')).toBe('');
    expect(service.format(Number.NaN, 'SOURCE_DATE_FORMAT')).toBe('');
    expect(service.format(new Date('nonsense'), 'SOURCE_DATE_FORMAT')).toBe('');
  });

  it('renders nothing rather than the key name when the pattern has not loaded', async () => {
    const service = await setUp();
    expect(service.format(INSTANT, 'NO_SUCH_DATE_FORMAT')).toBe('');
  });

  // The same instant, the same language, the same zone: the only thing that changes is that the
  // words arrive. A renderer that read the pattern before the month words would have returned ''
  // without tracking anything, and this computed would stay empty for the rest of the visit.
  it('fills the date in when the bundle arrives and nothing else changes', () => {
    const { service, arrive } = setUpBeforeTheBundle();
    const instant = signal<string | undefined>(INSTANT);
    const rendered = computed(() => service.format(instant(), 'SOURCE_DATE_FORMAT'));

    expect(rendered()).toBe('');
    arrive();
    expect(rendered()).toBe('1 January 2024');
  });

  it('fills the date in the same way through formatSignal', () => {
    const { service, arrive } = setUpBeforeTheBundle();
    const rendered = service.formatSignal(
      signal<string | undefined>(INSTANT),
      'SOURCE_DATE_FORMAT',
    );

    expect(rendered()).toBe('');
    arrive();
    expect(rendered()).toBe('1 January 2024');
  });

  it('re-renders on a language switch, through formatSignal', async () => {
    const service = await setUp();
    const instant = signal<string | undefined>(INSTANT);
    const rendered = service.formatSignal(instant, 'SOURCE_DATE_FORMAT');
    expect(rendered()).toBe('1 January 2024');
    TestBed.inject(TranslocoService).setActiveLang('zz');
    expect(rendered()).toBe('1 [Jánúáry] 2024');
  });

  it('names the calendar year of the configured zone', async () => {
    // The runner is America/St_Johns, three and a half hours behind UTC, so it is still 2023
    // there while Copenhagen has already turned the year.
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2023-12-31T23:30:00Z'));
    expect((await setUp('Europe/Copenhagen')).currentYear()).toBe(2024);
    expect((await setUp('America/Halifax')).currentYear()).toBe(2023);
  });
});
