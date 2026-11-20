import { TestBed } from '@angular/core/testing';
import { NO_ERRORS_SCHEMA } from '@angular/core';
import { Location } from '@angular/common';
import { provideLocationMocks, SpyLocation } from '@angular/common/testing';
import { ActivatedRoute, convertToParamMap, provideRouter, Router } from '@angular/router';
import { Dialog } from '@angular/cdk/dialog';
import { Subject } from 'rxjs';
import { MessageDetailsViewComponent } from './message-details-view.component';
import { MessagesStore } from '../../messages.store';
import type { MessageDetailsDialogData } from '../message-details-dialog/message-details-dialog.component';
import {
  fakeMessagesStore,
  getTranslocoTestingModule,
  provideTestNiordConfig,
} from '../../../../testing/test-config';

/**
 * The dialog and the CDK's overlay are fetched on demand, and the first fetch in a run compiles
 * them: on a busy machine that takes longer than waitFor's default second.
 */
const DIALOG_FETCH = { timeout: 5000 };

describe('MessageDetailsViewComponent', () => {
  function setUp(routeParams: Record<string, string>, address = '/messages/details') {
    const store = fakeMessagesStore({
      messages: [
        { id: 'msg-1', mainType: 'NW' },
        { id: 'msg-2', mainType: 'NM' },
      ],
    });
    const closed = new Subject<void>();
    const dialogSpy = { open: vi.fn(() => ({ closed })) };

    TestBed.configureTestingModule({
      imports: [MessageDetailsViewComponent, getTranslocoTestingModule()],
      providers: [
        provideTestNiordConfig(),
        provideRouter([]),
        provideLocationMocks(),
        { provide: MessagesStore, useValue: store },
        { provide: Dialog, useValue: dialogSpy },
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { paramMap: convertToParamMap(routeParams) } },
        },
      ],
      schemas: [NO_ERRORS_SCHEMA],
    });

    const location = TestBed.inject(Location);
    location.go(address);
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    const fixture = TestBed.createComponent(MessageDetailsViewComponent);
    fixture.detectChanges();
    return { fixture, dialogSpy, store, closed, location, navigate };
  }

  it('opens the dialog for the message a deep link names, on the list the store holds', async () => {
    const { dialogSpy } = setUp({ messageId: 'msg-2' }, '/messages/details/message/msg-2');

    // The dialog and the CDK's overlay are fetched on demand, so the open lands a tick later.
    await vi.waitFor(() => expect(dialogSpy.open).toHaveBeenCalled(), DIALOG_FETCH);
    const config = (dialogSpy.open.mock.calls[0] as unknown[])[1] as {
      data: { messageId: string; messageIds: () => string[] };
    };
    expect(config.data.messageId).toBe('msg-2');
    expect(config.data.messageIds()).toEqual(['msg-1', 'msg-2']);
  });

  // A link into one message opens before the list behind it has been read; the dialog takes the
  // list itself, so the steps through it appear once it is in.
  it('hands the dialog the list itself, which fills in after the dialog has opened', async () => {
    const { dialogSpy, store } = setUp({ messageId: 'msg-2' }, '/messages/details/message/msg-2');
    store.messages.set([]);
    await vi.waitFor(() => expect(dialogSpy.open).toHaveBeenCalled(), DIALOG_FETCH);
    const config = (dialogSpy.open.mock.calls[0] as unknown[])[1] as {
      data: { messageIds: () => string[] };
    };
    expect(config.data.messageIds()).toEqual([]);

    store.messages.set([
      { id: 'msg-1', mainType: 'NW' },
      { id: 'msg-2', mainType: 'NM' },
    ]);
    expect(config.data.messageIds()).toEqual(['msg-1', 'msg-2']);
  });

  // A reload or a shared address would otherwise open the dialog the reader just closed.
  it('returns the address to the list when the deep-linked dialog is closed', async () => {
    const { closed, navigate } = setUp(
      { messageId: 'msg-2' },
      '/messages/details/message/msg-2?publicationId=p-1',
    );
    await vi.waitFor(() => expect(closed.observed).toBe(true), DIALOG_FETCH);

    closed.next();
    expect(navigate).toHaveBeenCalledWith(['/messages/details'], {
      queryParamsHandling: 'preserve',
      replaceUrl: true,
    });
  });

  // Back and Forward close the dialog too, and by then the browser has an address in place that
  // the reader asked for.
  it('leaves the address alone when the dialog closed because the reader went Back', async () => {
    const { closed, location, navigate } = setUp(
      { messageId: 'msg-2' },
      '/messages/details/message/msg-2',
    );
    await vi.waitFor(() => expect(closed.observed).toBe(true), DIALOG_FETCH);

    location.go('/publications');
    closed.next();
    expect(navigate).not.toHaveBeenCalled();
  });

  /** What the view handed the dialog it opened, once the open has landed. */
  async function openedData(dialogSpy: { open: ReturnType<typeof vi.fn> }) {
    await vi.waitFor(() => expect(dialogSpy.open).toHaveBeenCalled(), DIALOG_FETCH);
    return ((dialogSpy.open.mock.calls[0] as unknown[])[1] as { data: MessageDetailsDialogData })
      .data;
  }

  // A reload or a shared address would otherwise show the first message again, not the one on
  // screen. The entry is replaced, so Back still leaves for wherever the reader came from.
  it('follows the message the linked dialog moves to with the address, keeping the query', async () => {
    const { dialogSpy, location } = setUp(
      { messageId: 'msg-2' },
      '/messages/details/message/msg-2?publicationId=p-1',
    );
    const data = await openedData(dialogSpy);

    data.messageShown!('msg-1');
    expect(location.path()).toBe('/messages/details/message/msg-1?publicationId=p-1');
    expect((location as SpyLocation).urlChanges.at(-1)).toMatch(/^replace: /);

    // The id is one segment of the path, as the router and the web server read it back.
    data.messageShown!('NW-001/26 A');
    expect(location.path()).toBe('/messages/details/message/NW-001%2F26%20A?publicationId=p-1');
  });

  it('returns the address to the list when the linked dialog closes after a step', async () => {
    const { dialogSpy, closed, navigate } = setUp(
      { messageId: 'msg-2' },
      '/messages/details/message/msg-2?publicationId=p-1',
    );
    const data = await openedData(dialogSpy);

    data.messageShown!('msg-1');
    closed.next();
    expect(navigate).toHaveBeenCalledWith(['/messages/details'], {
      queryParamsHandling: 'preserve',
      replaceUrl: true,
    });
  });

  // Back or Forward closes the dialog and puts the address the reader asked for in place; neither
  // the close nor a late move inside the dialog writes over it.
  it('leaves the address alone after a step when the reader went Back', async () => {
    const { dialogSpy, closed, location, navigate } = setUp(
      { messageId: 'msg-2' },
      '/messages/details/message/msg-2',
    );
    const data = await openedData(dialogSpy);
    data.messageShown!('msg-1');

    location.go('/publications');
    data.messageShown!('msg-2');
    expect(location.path()).toBe('/publications');
    closed.next();
    expect(navigate).not.toHaveBeenCalled();
  });

  // WCAG 2.4.3: a dialog a link opened has no control behind it to give focus back to, and focus
  // fell to the page's body, so the next Tab started again at the top of the page.
  describe('focus, once the linked dialog has closed', () => {
    const TITLED = [
      { id: 'msg-1', shortId: 'NW-001-26', mainType: 'NW', descs: [{ lang: 'en', title: 'One' }] },
      { id: 'msg-2', mainType: 'NM', descs: [{ lang: 'en', title: 'Two' }] },
    ] as const;
    let main: HTMLElement;

    beforeEach(() => {
      main = document.createElement('main');
      main.id = 'main-content';
      main.tabIndex = -1;
      document.body.append(main);
    });
    afterEach(() => main.remove());

    /** Opens the dialog a link names over the list, in the page, and closes it after `steps`. */
    async function closeAfter(linked: string, steps: string[]): Promise<void> {
      const { fixture, dialogSpy, closed, store } = setUp(
        { messageId: linked },
        `/messages/details/message/${linked}`,
      );
      store.messages.set([...TITLED]);
      fixture.detectChanges();
      main.append(fixture.nativeElement);
      const data = await openedData(dialogSpy);
      for (const step of steps) data.messageShown!(step);

      closed.next();
      await new Promise((resolve) => setTimeout(resolve));
      TestBed.tick();
    }

    it('goes to the row of the message the dialog showed last', async () => {
      await closeAfter('msg-2', ['msg-1']);
      const focused = document.activeElement as HTMLElement;
      expect(focused.matches('button.row-link')).toBe(true);
      expect(focused.textContent).toContain('One');
    });

    it('finds that row by a short id as well', async () => {
      await closeAfter('NW-001-26', []);
      const focused = document.activeElement as HTMLElement;
      expect(focused.matches('button.row-link')).toBe(true);
      expect(focused.textContent).toContain('One');
    });

    it('goes to the main content when the list does not hold that message', async () => {
      await closeAfter('msg-9', []);
      expect(document.activeElement).toBe(main);
    });
  });

  // Only a link names a message in the address; a dialog opened from the list leaves it alone.
  it('hands a dialog opened from the list nothing to follow the address with', async () => {
    const { fixture, dialogSpy } = setUp({});
    void fixture.componentInstance.openDetails('msg-1');
    const data = await openedData(dialogSpy);
    expect(data.messageId).toBe('msg-1');
    expect(data.messageShown).toBeUndefined();
  });

  it('opens nothing when the route names no message', async () => {
    const { dialogSpy } = setUp({});
    await Promise.resolve();
    expect(dialogSpy.open).not.toHaveBeenCalled();
  });

  it('says a read failed, and nothing alarming for a list with nothing in it', () => {
    const { fixture, store } = setUp({});
    // The live region is there before the failure, so the failure is announced when it lands.
    const alert = (fixture.nativeElement as HTMLElement).querySelector('[role="alert"]')!;
    expect(alert.textContent!.trim()).toBe('');

    store.failed.set(true);
    fixture.detectChanges();
    expect(alert.textContent!.trim()).toBe('Failed to load messages');
  });

  // A blank page under the filter bar cannot be told from one that did not work.
  it('says in words that a list arrived with nothing in it', () => {
    const { fixture, store } = setUp({});
    // The live region is there before the words, so they are announced when they land.
    const status = (fixture.nativeElement as HTMLElement).querySelector('[role="status"]')!;
    expect(status.textContent!.trim()).toBe('');

    store.messages.set([]);
    store.empty.set(true);
    fixture.detectChanges();
    expect(status.textContent!.trim()).toBe('No messages to show');
    const alert = (fixture.nativeElement as HTMLElement).querySelector('[role="alert"]')!;
    expect(alert.textContent!.trim()).toBe('');
  });

  // On a slow link the first read takes seconds, and an empty page reads as "no warnings here".
  it('says it is reading while the list is on its way', () => {
    const { fixture, store } = setUp({});
    const list = (fixture.nativeElement as HTMLElement).querySelector('.message-details-list')!;
    expect(list.getAttribute('aria-busy')).toBe('false');
    expect(list.querySelector('[aria-hidden="true"] app-icon')).toBeNull();

    store.loading.set(true);
    fixture.detectChanges();
    expect(list.getAttribute('aria-busy')).toBe('true');
    expect(list.querySelector('[aria-hidden="true"] app-icon')).not.toBeNull();
  });
});
