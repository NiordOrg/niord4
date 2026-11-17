import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NO_ERRORS_SCHEMA, signal, WritableSignal } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { DIALOG_DATA, DialogRef } from '@angular/cdk/dialog';
import { LiveAnnouncer } from '@angular/cdk/a11y';
import { TranslocoService } from '@jsverse/transloco';
import { MessageDetailsDialogComponent } from './message-details-dialog.component';
import {
  getEmptyTranslocoTestingModule,
  getTranslocoTestingModule,
  provideTestNiordConfig,
  TEST_TRANSLATIONS,
} from '../../../../testing/test-config';
import { settle } from '../../../../testing/settle';

describe('MessageDetailsDialogComponent', () => {
  let fixture: ComponentFixture<MessageDetailsDialogComponent>;
  let component: MessageDetailsDialogComponent;
  let httpTesting: HttpTestingController;
  let announce: ReturnType<typeof vi.fn>;
  /** The opener's list, which the opener may still be reading when the dialog opens. */
  let openerList: WritableSignal<readonly string[]>;

  /** Everything the dialog needs arrives as DIALOG_DATA; nothing is assigned to the instance. */
  function setUp(
    messageId = 'msg-1',
    messageIds: readonly string[] = ['msg-0', 'msg-1', 'msg-2'],
    bundles: 'loaded' | 'not yet' = 'loaded',
    messageShown?: (messageId: string) => void,
  ) {
    announce = vi.fn(() => Promise.resolve());
    openerList = signal(messageIds);
    TestBed.configureTestingModule({
      // Settling the graph renders the dialog, so the words it renders are the shipped ones.
      imports: [
        MessageDetailsDialogComponent,
        bundles === 'loaded' ? getTranslocoTestingModule() : getEmptyTranslocoTestingModule(),
      ],
      providers: [
        provideTestNiordConfig(),
        provideHttpClient(),
        provideHttpClientTesting(),
        // `DialogRef` is provided by an actual `Dialog.open()`; a component test stands the
        // dialog up on its own, so it stands in for the reference and the data the opener hands it.
        { provide: DialogRef, useValue: { close: vi.fn() } },
        { provide: DIALOG_DATA, useValue: { messageId, messageIds: openerList, messageShown } },
        { provide: LiveAnnouncer, useValue: { announce } },
      ],
      schemas: [NO_ERRORS_SCHEMA],
    });

    fixture = TestBed.createComponent(MessageDetailsDialogComponent);
    component = fixture.componentInstance;
    httpTesting = TestBed.inject(HttpTestingController);
  }

  /** The read the dialog issues for whichever message is on top of its stack. */
  function expectRead(id: string) {
    return httpTesting.expectOne((r) => r.url.endsWith(`/rest/public/v1/message/${id}`));
  }

  afterEach(() => httpTesting.verify());

  it('reads the message the opener named, in the active language', async () => {
    setUp();
    await settle();
    const request = expectRead('msg-1');
    expect(request.request.urlWithParams).toContain('lang=en');
    expect(request.request.urlWithParams).toContain('externalize=false');
    request.flush({ id: 'msg-1', mainType: 'NW', descs: [{ lang: 'en' }] });
    await settle();

    expect(component.msg()?.id).toBe('msg-1');
    expect(component.notFound()).toBe(false);
    expect(component.currentIndex()).toBe(1);
  });

  // A link into one message opens its dialog before the list behind it has been read; the steps
  // through that list appear once it is in.
  it('offers the steps once the list it was opened from has been read', async () => {
    setUp('msg-1', []);
    await settle();
    expectRead('msg-1').flush({ id: 'msg-1', mainType: 'NW' });
    await settle();
    expect(component.showNavigation()).toBe(false);
    expect(button('Previous message')).toBeNull();

    openerList.set(['msg-0', 'msg-1', 'msg-2']);
    await settle();
    expect(component.currentIndex()).toBe(1);
    expect(button('Previous message')).not.toBeNull();

    component.selectNext();
    await settle();
    expectRead('msg-2').flush({ id: 'msg-2', mainType: 'NW' });
    await settle();
    expect(component.msg()?.id).toBe('msg-2');
  });

  it('says it is reading while the first message is on its way, and not after', async () => {
    setUp();
    await settle();
    const busy = () => el().querySelector('[aria-busy]')!;
    const spinner = () => busy().querySelector('app-icon[name="lucideLoaderCircle"]');
    expect(busy().getAttribute('aria-busy')).toBe('true');
    expect(spinner()).not.toBeNull();

    expectRead('msg-1').flush({ id: 'msg-1', mainType: 'NW' });
    await settle();
    expect(busy().getAttribute('aria-busy')).toBe('false');
    expect(spinner()).toBeNull();
    // The busy state is the message body's own, so it describes what arrived in it.
    expect(busy().querySelector('app-message-detail-card')).not.toBeNull();

    // Stepping keeps the message on screen until the next one lands; nothing flashes.
    component.selectNext();
    await settle();
    expect(busy().getAttribute('aria-busy')).toBe('false');
    expectRead('msg-2').flush({ id: 'msg-2', mainType: 'NW' });
    await settle();
  });

  it('steps to the next message and back to the previous one', async () => {
    setUp();
    await settle();
    expectRead('msg-1').flush({ id: 'msg-1', mainType: 'NW' });
    await settle();

    component.selectNext();
    await settle();
    expect(component.currentIndex()).toBe(2);
    // What the reader is looking at stays up until the next message has arrived.
    expect(component.msg()?.id).toBe('msg-1');
    expectRead('msg-2').flush({ id: 'msg-2', mainType: 'NW' });
    await settle();
    expect(component.msg()?.id).toBe('msg-2');

    component.selectPrev();
    await settle();
    expect(component.currentIndex()).toBe(1);
    expectRead('msg-1').flush({ id: 'msg-1', mainType: 'NW' });
    await settle();
    expect(component.msg()?.id).toBe('msg-1');
  });

  it('follows a reference and pops back to the message it was followed from', async () => {
    setUp();
    await settle();
    expectRead('msg-1').flush({ id: 'msg-1', mainType: 'NW' });
    await settle();

    component.selectMessage('msg-ref');
    await settle();
    expectRead('msg-ref').flush({ id: 'msg-ref', mainType: 'NM' });
    await settle();
    expect(component.pushedMessageIds()).toHaveLength(2);
    expect(component.msg()?.id).toBe('msg-ref');
    // A followed reference is not a place in the opener's list, so the step buttons go.
    expect(component.currentIndex()).toBe(-1);

    component.back();
    await settle();
    expectRead('msg-1').flush({ id: 'msg-1', mainType: 'NW' });
    await settle();
    expect(component.pushedMessageIds()).toHaveLength(1);
    expect(component.msg()?.id).toBe('msg-1');
  });

  it('names the message when the backend answers with no message at all', async () => {
    setUp('msg-gone', ['msg-gone']);
    await settle();
    expectRead('msg-gone').flush(null);
    await settle();

    expect(component.msg()).toBeUndefined();
    expect(alert()).toBe('Message msg-gone is not available');
  });

  // The answer can beat the bundle on a slow link. Words taken once, when the answer came, would
  // stay a key name for good; the notice has to fill in when the bundle lands.
  it('words the notice once the bundle lands after the answer', async () => {
    setUp('msg-gone', ['msg-gone'], 'not yet');
    await settle();
    expectRead('msg-gone').flush(null);
    await settle();
    expect(alert()).not.toContain('is not available');

    TestBed.inject(TranslocoService).setTranslation(TEST_TRANSLATIONS['en'], 'en');
    await settle();
    expect(alert()).toBe('Message msg-gone is not available');
  });

  it('prints the message the reader is on, as the backend PDF of that message', async () => {
    const openSpy = vi.fn<typeof window.open>();
    vi.spyOn(window, 'open').mockImplementation(openSpy);
    setUp();
    await settle();
    expectRead('msg-1').flush({ id: 'msg-1', mainType: 'NW' });
    await settle();

    component.printMessage();
    expect(openSpy).toHaveBeenCalledWith(
      '/rest/message-reports/message/msg-1.pdf?lang=en',
      '_blank',
      'noopener',
    );

    // A followed reference is what the button prints once it is the one on screen.
    component.selectMessage('NW-333-26');
    await settle();
    expectRead('NW-333-26').flush({ id: 'msg-ref', mainType: 'NW' });
    await settle();

    component.printMessage();
    expect(openSpy).toHaveBeenLastCalledWith(
      '/rest/message-reports/message/NW-333-26.pdf?lang=en',
      '_blank',
      'noopener',
    );
  });

  it('says the same when the read fails', async () => {
    setUp('msg-1', ['msg-1']);
    await settle();
    expectRead('msg-1').flush('nope', { status: 500, statusText: 'Server Error' });
    await settle();

    expect(component.msg()).toBeUndefined();
    expect(alert()).toBe('Message msg-1 is not available');
  });

  const el = () => fixture.nativeElement as HTMLElement;
  const alert = () => el().querySelector('[role="alert"]')!.textContent!.trim();
  const title = () => el().querySelector('.dialog-title')!;
  const button = (label: string) => el().querySelector(`button[aria-label="${label}"]`);

  // Focus enters the dialog before the message has been read, and the title is its name: a
  // dialog announced with no name at all tells a screen-reader user nothing.
  it('is named after the site until the message is read, then after its kind', async () => {
    setUp();
    await settle();
    expect(title().textContent!.trim()).toBe('Navigational Warnings and Notices to Mariners');

    expectRead('msg-1').flush({ id: 'msg-1', mainType: 'NW' });
    await settle();
    expect(title().textContent!.trim()).toBe('Navigational Warnings');
  });

  it('keeps its name for a message that does not exist, and announces the notice', async () => {
    setUp('msg-gone', ['msg-gone']);
    await settle();
    expectRead('msg-gone').flush(null);
    await settle();

    expect(title().textContent!.trim()).toBe('Navigational Warnings and Notices to Mariners');
    expect(el().querySelector('[role="alert"]')!.textContent).toContain(
      'Message msg-gone is not available',
    );
  });

  // A disabled button gives up focus, which then drops to the document behind the dialog; a
  // step that has nowhere to go says so and keeps it.
  it('marks a step with nowhere to go unavailable, and keeps it focusable', async () => {
    setUp('msg-0');
    await settle();
    expectRead('msg-0').flush({ id: 'msg-0', mainType: 'NW' });
    await settle();

    const prev = button('Previous message') as HTMLButtonElement;
    expect(prev.disabled).toBe(false);
    expect(prev.getAttribute('aria-disabled')).toBe('true');
    expect(button('Next message')!.hasAttribute('aria-disabled')).toBe(false);

    prev.focus();
    prev.click();
    await settle();
    expect(component.currentIndex()).toBe(0);
    expect(document.activeElement).toBe(prev);
  });

  it('marks the last step unavailable at the end of the list', async () => {
    setUp('msg-2');
    await settle();
    expectRead('msg-2').flush({ id: 'msg-2', mainType: 'NW' });
    await settle();

    expect(button('Next message')!.getAttribute('aria-disabled')).toBe('true');
    expect(button('Previous message')!.hasAttribute('aria-disabled')).toBe(false);
  });

  it('moves focus to the title when a reference is followed and when the reader goes back', async () => {
    setUp();
    await settle();
    expectRead('msg-1').flush({ id: 'msg-1', mainType: 'NW' });
    await settle();

    component.selectMessage('msg-ref');
    expect(document.activeElement).toBe(title());
    await settle();
    expectRead('msg-ref').flush({ id: 'msg-ref', mainType: 'NM' });
    await settle();

    const back = [...el().querySelectorAll('button')].find((b) => b.textContent!.includes('back'))!;
    back.focus();
    back.click();
    expect(document.activeElement).toBe(title());
    await settle();
    expectRead('msg-1').flush({ id: 'msg-1', mainType: 'NW' });
  });

  // The dialog is titled by an h2; the message's own title is the heading below it, so a screen
  // reader finds it by heading as it does in the list.
  it('puts the message title in a heading one level below the dialog title', async () => {
    setUp();
    await settle();
    expectRead('msg-1').flush({
      id: 'msg-1',
      mainType: 'NW',
      descs: [{ lang: 'en', title: 'Firing exercises' }],
    });
    await settle();

    expect(title().tagName).toBe('H2');
    const heading = el().querySelector('app-message-detail-card [role="heading"]')!;
    expect(heading.getAttribute('aria-level')).toBe('3');
    expect(heading.textContent!.trim()).toBe('Firing exercises');
  });

  // The reader stays on the step button or on the title, so the message that took the place of
  // the one they were reading is said to them.
  it('announces each message the reader moves to, and not the first one', async () => {
    setUp();
    await settle();
    expectRead('msg-1').flush({ id: 'msg-1', shortId: 'NW-001-26', mainType: 'NW' });
    await settle();
    expect(announce).not.toHaveBeenCalled();

    component.selectNext();
    await settle();
    expectRead('msg-2').flush({
      id: 'msg-2',
      shortId: 'NW-002-26',
      mainType: 'NW',
      descs: [{ lang: 'en', title: 'Firing exercises' }],
    });
    await settle();
    expect(announce).toHaveBeenCalledOnce();
    expect(announce).toHaveBeenCalledWith('NW-002-26 Firing exercises');
  });

  // The opener of a dialog a link opened keeps the address on the message shown, so it is told
  // each move, whichever way the reader made it, and not the message it opened on.
  it('tells the opener each message the reader moves to, and not the first one', async () => {
    const messageShown = vi.fn<(messageId: string) => void>();
    setUp('msg-1', ['msg-0', 'msg-1', 'msg-2'], 'loaded', messageShown);
    await settle();
    expectRead('msg-1').flush({ id: 'msg-1', mainType: 'NW' });
    await settle();
    expect(messageShown).not.toHaveBeenCalled();

    // At once, before the next message has been read: the address names what the dialog shows,
    // a notice for a message that is gone included.
    component.selectNext();
    await settle();
    expect(messageShown).toHaveBeenLastCalledWith('msg-2');
    expectRead('msg-2').flush({ id: 'msg-2', mainType: 'NW' });
    await settle();

    // A reference is told as it was written, which is the id the dialog reads.
    component.selectMessage('NW-333-26');
    await settle();
    expect(messageShown).toHaveBeenLastCalledWith('NW-333-26');
    expectRead('NW-333-26').flush({ id: 'msg-ref', mainType: 'NW' });
    await settle();

    component.back();
    await settle();
    expect(messageShown).toHaveBeenLastCalledWith('msg-2');
    expectRead('msg-2').flush({ id: 'msg-2', mainType: 'NW' });
    await settle();

    component.selectPrev();
    await settle();
    expect(messageShown).toHaveBeenLastCalledWith('msg-1');
    expectRead('msg-1').flush({ id: 'msg-1', mainType: 'NW' });
    await settle();
    expect(messageShown.mock.calls).toEqual([['msg-2'], ['NW-333-26'], ['msg-2'], ['msg-1']]);

    // A step with nowhere to go is no move.
    component.selectPrev();
    await settle();
    expectRead('msg-0').flush({ id: 'msg-0', mainType: 'NW' });
    await settle();
    component.selectPrev();
    await settle();
    expect(messageShown.mock.calls).toHaveLength(5);
    expect(messageShown).toHaveBeenLastCalledWith('msg-0');
  });
});
