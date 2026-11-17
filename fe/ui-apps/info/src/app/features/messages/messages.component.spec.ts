import { TestBed } from '@angular/core/testing';
import { NO_ERRORS_SCHEMA } from '@angular/core';
import { By } from '@angular/platform-browser';
import { MessagesComponent } from './messages.component';
import { MessagesStore } from './messages.store';
import { MessageFilterComponent } from './components/message-filter/message-filter.component';
import {
  FakeMessagesStoreSeed,
  fakeMessagesStore,
  getTranslocoTestingModule,
} from '../../testing/test-config';

describe('MessagesComponent', () => {
  // The page is the filter bar and an outlet, so what it owns is the wiring between the two;
  // the store's own behaviour is messages.store.spec.ts.
  let store: ReturnType<typeof fakeMessagesStore>;

  async function setUp(seed?: FakeMessagesStoreSeed) {
    store = fakeMessagesStore(seed);
    await TestBed.configureTestingModule({
      imports: [MessagesComponent, getTranslocoTestingModule()],
      providers: [{ provide: MessagesStore, useValue: store }],
      schemas: [NO_ERRORS_SCHEMA],
    }).compileComponents();
    // The component provides the real store itself, which would win over the one above. Only
    // the provider is removed, never replaced, so each test still gets its own stand-in rather
    // than the first one a cached component definition captured.
    TestBed.overrideComponent(MessagesComponent, { remove: { providers: [MessagesStore] } });

    const fixture = TestBed.createComponent(MessagesComponent);
    fixture.detectChanges();
    return {
      fixture,
      filter: fixture.debugElement.query(By.directive(MessageFilterComponent))
        .componentInstance as MessageFilterComponent,
      printButton: fixture.debugElement
        .queryAll(By.css('button.chip'))
        .map((chip) => chip.nativeElement as HTMLButtonElement)
        .find((chip) => chip.textContent?.trim() === 'Print')!,
    };
  }

  it('hands every filter change to the store', async () => {
    const { filter } = await setUp();

    filter.mainTypesChange.emit({ NW: false, NM: true });
    filter.activeNowChange.emit();
    filter.subAreaToggle.emit({ id: 11 });
    filter.rootAreaChange.emit({ id: 22 });

    expect(store.setMainTypes).toHaveBeenCalledWith({ NW: false, NM: true });
    expect(store.toggleActiveNow).toHaveBeenCalled();
    expect(store.toggleSubArea).toHaveBeenCalledWith({ id: 11 });
    expect(store.selectRootArea).toHaveBeenCalledWith({ id: 22 });
  });

  it('tells the filter bar whether the list is a publication being browsed', async () => {
    const { fixture, filter } = await setUp();
    expect(filter.browsingPublication()).toBe(false);

    store.publicationId.set('6f1c2a8e-3b4d-4e5f-9a0b-1c2d3e4f5a6b');
    fixture.detectChanges();
    expect(filter.browsingPublication()).toBe(true);
  });

  it('opens the backend PDF of the list the store describes, in a tab with no opener', async () => {
    const openSpy = vi.fn<typeof window.open>();
    vi.spyOn(window, 'open').mockImplementation(openSpy);
    const { filter, printButton } = await setUp({ canPrintList: true });
    store.printPdfUrl.mockReturnValue('/rest/message-reports/report.pdf?lang=da&mainType=NW');
    expect(printButton.disabled).toBe(false);

    filter.printRequested.emit();

    expect(openSpy).toHaveBeenCalledWith(
      '/rest/message-reports/report.pdf?lang=da&mainType=NW',
      '_blank',
      'noopener',
    );
  });

  // A sheet asked for without the message series the rows came from is a sheet of every
  // published series, so the chip is inert until there is a list to print — and the action
  // refuses again, for a path that reaches it without the chip.
  it('offers no sheet while the store says there is no list to print', async () => {
    const openSpy = vi.fn<typeof window.open>();
    vi.spyOn(window, 'open').mockImplementation(openSpy);
    const { filter, printButton } = await setUp({ canPrintList: false });

    expect(printButton.disabled).toBe(true);

    filter.printRequested.emit();

    expect(openSpy).not.toHaveBeenCalled();
    expect(store.printPdfUrl).not.toHaveBeenCalled();
  });
});
