import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NO_ERRORS_SCHEMA } from '@angular/core';
import { TranslocoService } from '@jsverse/transloco';
import { MessageIdBadgeComponent } from './message-id-badge.component';
import {
  getEmptyTranslocoTestingModule,
  getTranslocoTestingModule,
  provideTestNiordConfig,
  TEST_TRANSLATIONS,
} from '../../../../testing/test-config';
import { Message } from '../../../../core/models/message.model';

const CANCELLED: Message = { id: '1', mainType: 'NW', shortId: 'NW-001', status: 'CANCELLED' };
const UNNUMBERED: Message = { id: '1', mainType: 'NW', type: 'LOCAL_WARNING' };

describe('MessageIdBadgeComponent', () => {
  let fixture: ComponentFixture<MessageIdBadgeComponent>;

  function setUp(translations = getTranslocoTestingModule()): void {
    TestBed.configureTestingModule({
      imports: [MessageIdBadgeComponent, translations],
      providers: [provideTestNiordConfig()],
      schemas: [NO_ERRORS_SCHEMA],
    });
    fixture = TestBed.createComponent(MessageIdBadgeComponent);
  }

  function chips(): { className: string; text: string }[] {
    return [...fixture.nativeElement.querySelectorAll('span.status-badge')].map(
      (el: HTMLElement) => ({ className: el.className, text: (el.textContent ?? '').trim() }),
    );
  }

  /** What a language switch, or a bundle arriving, has set going is on the page. */
  async function rendered(): Promise<void> {
    await fixture.whenStable();
    fixture.detectChanges();
  }

  it('renders the identifier as one chip', () => {
    setUp();
    fixture.componentRef.setInput('msg', { id: '1', mainType: 'NW', shortId: 'NW-001' });
    fixture.detectChanges();
    expect(chips()).toEqual([{ className: 'status-badge mono-id', text: 'NW-001' }]);
  });

  it('gives an NM identifier the same chip as an NW one', () => {
    setUp();
    fixture.componentRef.setInput('msg', { id: '1', mainType: 'NM', shortId: 'NM-001' });
    fixture.detectChanges();
    expect(chips()).toEqual([{ className: 'status-badge mono-id', text: 'NM-001' }]);
  });

  it('adds a second chip for a cancellation when the status is asked for', async () => {
    setUp();
    fixture.componentRef.setInput('msg', CANCELLED);
    fixture.componentRef.setInput('showStatus', true);
    await rendered();
    const [id, status] = chips();
    expect(id.text).toBe('NW-001');
    expect(status.className).toContain('is-cancelled');
    expect(status.className).not.toContain('is-expired');
    expect(status.text).toBe(TEST_TRANSLATIONS['en']['STATUS_CANCELLED']);
  });

  it('names a message with no short id by its type and main type', async () => {
    setUp();
    fixture.componentRef.setInput('msg', UNNUMBERED);
    await rendered();
    const en = TEST_TRANSLATIONS['en'];
    expect(chips()[0].text).toBe(`${en['TYPE_LOCAL_WARNING']} ${en['MAIN_TYPE_NW']}`);
  });

  // The words were translated once, by a service, so after a language switch the chip kept
  // the language it was first drawn in until the list was read again. A language a reader
  // switches to for the first time has no bundle yet: the switch is drawn before its words
  // arrive, and the chip has to take them up when they do.
  it('follows a language switch, also when the bundle arrives after the switch', async () => {
    setUp(getEmptyTranslocoTestingModule());
    const transloco = TestBed.inject(TranslocoService);
    transloco.setTranslation(TEST_TRANSLATIONS['en'], 'en');
    fixture.componentRef.setInput('msg', CANCELLED);
    fixture.componentRef.setInput('showStatus', true);
    await rendered();
    expect(chips()[1].text).toBe(TEST_TRANSLATIONS['en']['STATUS_CANCELLED']);

    transloco.setActiveLang('zz');
    await rendered();
    transloco.setTranslation(TEST_TRANSLATIONS['zz'], 'zz');
    await rendered();

    expect(chips()[1].text).toBe(TEST_TRANSLATIONS['zz']['STATUS_CANCELLED']);
  });

  // Configured as the application configures Transloco, which re-renders on a language switch.
  it('words an unnumbered message in the language switched to', async () => {
    setUp(getEmptyTranslocoTestingModule());
    const transloco = TestBed.inject(TranslocoService);
    transloco.setTranslation(TEST_TRANSLATIONS['en'], 'en');
    transloco.setTranslation(TEST_TRANSLATIONS['zz'], 'zz');
    fixture.componentRef.setInput('msg', UNNUMBERED);
    await rendered();

    transloco.setActiveLang('zz');
    await rendered();

    const zz = TEST_TRANSLATIONS['zz'];
    expect(chips()[0].text).toBe(`${zz['TYPE_LOCAL_WARNING']} ${zz['MAIN_TYPE_NW']}`);
  });

  it('marks a temporary notice after the chips', () => {
    setUp();
    fixture.componentRef.setInput('msg', {
      id: '1',
      mainType: 'NW',
      shortId: 'NW-001',
      type: 'TEMPORARY_NOTICE',
    });
    fixture.detectChanges();
    expect(chips()).toHaveLength(1);
    expect(fixture.nativeElement.textContent).toContain('(T)');
  });

  // The badge is markup the template writes; nothing is trusted into the DOM here.
  it('binds no HTML', () => {
    setUp();
    fixture.componentRef.setInput('msg', { id: '1', mainType: 'NW', shortId: '<b>x</b>' });
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('b')).toBeNull();
    expect(fixture.nativeElement.textContent).toContain('<b>x</b>');
  });
});
