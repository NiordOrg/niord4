import { TestBed } from '@angular/core/testing';
import { NO_ERRORS_SCHEMA } from '@angular/core';
import { Dialog } from '@angular/cdk/dialog';
import { MessageTableViewComponent } from './message-table-view.component';
import { MessagesStore } from '../../messages.store';
import {
  fakeMessagesStore,
  getTranslocoTestingModule,
  provideTestNiordConfig,
  provideTranslocoSpy,
} from '../../../../testing/test-config';

describe('MessageTableViewComponent', () => {
  it('opens the dialog on the whole list the store holds', async () => {
    const store = fakeMessagesStore({
      messages: [
        { id: 'msg-1', mainType: 'NW' },
        { id: 'msg-2', mainType: 'NW' },
        { id: 'msg-3', mainType: 'NM' },
      ],
    });
    const dialogSpy = { open: vi.fn() };

    TestBed.configureTestingModule({
      imports: [MessageTableViewComponent],
      providers: [
        provideTestNiordConfig(),
        provideTranslocoSpy(),
        { provide: MessagesStore, useValue: store },
        { provide: Dialog, useValue: dialogSpy },
      ],
      schemas: [NO_ERRORS_SCHEMA],
    });

    const fixture = TestBed.createComponent(MessageTableViewComponent);
    // The dialog and the CDK's overlay are fetched on demand, so the open lands a tick later.
    await fixture.componentInstance.openDetails('msg-2');

    expect(dialogSpy.open).toHaveBeenCalled();
    const config = dialogSpy.open.mock.calls[0][1];
    expect(config.data.messageId).toBe('msg-2');
    // The list itself, which the dialog follows, not a copy of what it held at the click.
    expect(config.data.messageIds()).toEqual(['msg-1', 'msg-2', 'msg-3']);
  });

  it('says it is reading while the list is on its way', () => {
    const store = fakeMessagesStore({ loading: true });

    TestBed.configureTestingModule({
      imports: [MessageTableViewComponent, getTranslocoTestingModule()],
      providers: [
        provideTestNiordConfig(),
        { provide: MessagesStore, useValue: store },
        { provide: Dialog, useValue: { open: vi.fn() } },
      ],
      schemas: [NO_ERRORS_SCHEMA],
    });

    const fixture = TestBed.createComponent(MessageTableViewComponent);
    fixture.detectChanges();
    const list = (fixture.nativeElement as HTMLElement).querySelector('.message-details-list')!;
    expect(list.getAttribute('aria-busy')).toBe('true');
    expect(list.querySelector('[aria-hidden="true"] app-icon')).not.toBeNull();

    store.loading.set(false);
    fixture.detectChanges();
    expect(list.getAttribute('aria-busy')).toBe('false');
    expect(list.querySelector('[aria-hidden="true"] app-icon')).toBeNull();
  });

  it('says a read failed, where a list with nothing in it says nothing', () => {
    const store = fakeMessagesStore();

    TestBed.configureTestingModule({
      imports: [MessageTableViewComponent, getTranslocoTestingModule()],
      providers: [
        provideTestNiordConfig(),
        { provide: MessagesStore, useValue: store },
        { provide: Dialog, useValue: { open: vi.fn() } },
      ],
      schemas: [NO_ERRORS_SCHEMA],
    });

    const fixture = TestBed.createComponent(MessageTableViewComponent);
    fixture.detectChanges();
    // The live region is there before the failure, so the failure is announced when it lands.
    const alert = (fixture.nativeElement as HTMLElement).querySelector('[role="alert"]')!;
    expect(alert.textContent!.trim()).toBe('');

    store.failed.set(true);
    fixture.detectChanges();
    expect(alert.textContent!.trim()).toBe('Failed to load messages');
  });

  // A heading inside the list is counted as one of its items, and WebKit drops the list role of
  // a list drawn without bullets unless the list names it; either way a screen reader reports a
  // list of the wrong length, or none.
  it('puts each area heading above a list of its own messages, not into the list', () => {
    const area = (id: number, name: string) => ({ id, descs: [{ lang: 'en', name }] });
    const store = fakeMessagesStore({
      messages: [
        { id: 'msg-1', mainType: 'NW', descs: [{ lang: 'en', title: 'One' }] },
        { id: 'msg-2', mainType: 'NW', descs: [{ lang: 'en', title: 'Two' }] },
        { id: 'msg-3', mainType: 'NM', descs: [{ lang: 'en', title: 'Three' }] },
      ],
      areaHeadings: new Map([
        ['msg-1', area(1, 'The Sound')],
        ['msg-3', area(2, 'Kattegat')],
      ]),
    });

    TestBed.configureTestingModule({
      imports: [MessageTableViewComponent, getTranslocoTestingModule()],
      providers: [
        provideTestNiordConfig(),
        { provide: MessagesStore, useValue: store },
        { provide: Dialog, useValue: { open: vi.fn() } },
      ],
      schemas: [NO_ERRORS_SCHEMA],
    });

    const fixture = TestBed.createComponent(MessageTableViewComponent);
    fixture.detectChanges();

    const list = (fixture.nativeElement as HTMLElement).querySelector('.message-details-list')!;
    const outline = [...list.children]
      .slice(1)
      .map((child) =>
        child.tagName === 'UL'
          ? `${child.getAttribute('role')}: ${[...child.children].map((li) => li.textContent!.trim().split(/\s+/).at(-1)).join(', ')}`
          : `${child.tagName}: ${child.textContent!.trim()}`,
      );
    // After the live region, which says nothing while the list is there.
    expect(list.firstElementChild!.getAttribute('role')).toBe('alert');
    expect(outline).toEqual(['H4: The Sound', 'list: One, Two', 'H4: Kattegat', 'list: Three']);
    expect(list.querySelector('li h4')).toBeNull();
  });

  it('marks a title that is not in the language of the page with its own', () => {
    const store = fakeMessagesStore({
      messages: [
        { id: 'msg-1', mainType: 'NW', descs: [{ lang: 'en', title: 'Firing exercises' }] },
      ],
    });

    TestBed.configureTestingModule({
      imports: [MessageTableViewComponent, getTranslocoTestingModule()],
      providers: [
        provideTestNiordConfig({ defaultLanguage: 'zz' }),
        { provide: MessagesStore, useValue: store },
        { provide: Dialog, useValue: { open: vi.fn() } },
      ],
      schemas: [NO_ERRORS_SCHEMA],
    });

    const fixture = TestBed.createComponent(MessageTableViewComponent);
    fixture.detectChanges();

    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('.message-title')!.getAttribute('lang')).toBe('en');
    expect(el.querySelector('button img')!.getAttribute('alt')).toBe('[English flág]');
  });

  // A flag path is never made up from a language code: it would be a broken image.
  it('shows a title language the build ships no flag for by its code, with no image', () => {
    const store = fakeMessagesStore({
      messages: [{ id: 'msg-1', mainType: 'NW', descs: [{ lang: 'de', title: 'Schießübung' }] }],
    });

    TestBed.configureTestingModule({
      imports: [MessageTableViewComponent, getTranslocoTestingModule()],
      providers: [
        provideTestNiordConfig(),
        { provide: MessagesStore, useValue: store },
        { provide: Dialog, useValue: { open: vi.fn() } },
      ],
      schemas: [NO_ERRORS_SCHEMA],
    });

    const fixture = TestBed.createComponent(MessageTableViewComponent);
    fixture.detectChanges();

    const button = (fixture.nativeElement as HTMLElement).querySelector('li button')!;
    expect(button.querySelector('img')).toBeNull();
    const code = [...button.querySelectorAll('span')].find(
      (span) => span.textContent!.trim() === 'DE',
    );
    expect(code).toBeDefined();
    // To the eye only: the title beside it carries the lang a screen reader needs.
    expect(code!.getAttribute('aria-hidden')).toBe('true');
  });

  // A flex item is never narrower than its longest word unless it is told it may be, so one
  // Danish compound in a title takes a 320 px page sideways. jsdom lays nothing out; the width
  // itself is checked in a browser by npm run layout:info.
  it('lets a title shrink below its longest word and break it', () => {
    const store = fakeMessagesStore({
      messages: [
        { id: 'msg-1', mainType: 'NM', descs: [{ lang: 'da', title: 'Havbundsundersøgelser' }] },
      ],
    });

    TestBed.configureTestingModule({
      imports: [MessageTableViewComponent, getTranslocoTestingModule()],
      providers: [
        provideTestNiordConfig(),
        { provide: MessagesStore, useValue: store },
        { provide: Dialog, useValue: { open: vi.fn() } },
      ],
      schemas: [NO_ERRORS_SCHEMA],
    });

    const fixture = TestBed.createComponent(MessageTableViewComponent);
    fixture.detectChanges();

    const title = (fixture.nativeElement as HTMLElement).querySelector('.message-title')!;
    expect(title.textContent!.trim()).toBe('Havbundsundersøgelser');
    expect(title.classList).toContain('min-w-0');
    expect(title.classList).toContain('break-words');
  });
});
