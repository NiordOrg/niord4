import { Component, inject } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { MessageFilterComponent } from './components/message-filter/message-filter.component';
import { MessagesStore } from './messages.store';
import { restoreScrollWhenSettled } from '../../shared/utils/restore-scroll';

@Component({
  selector: 'app-messages',
  imports: [RouterOutlet, MessageFilterComponent],
  // The filter and the message list are this component's, so the store is created with it and
  // destroyed with it — no server data outlives the view that shows it. This component hosts the
  // outlet the three child views render in, so all three read the one instance and the three
  // renderings of one filtered list share a single fetch.
  providers: [MessagesStore],
  // A block that holds the filter bar and the view below it: the bar is pinned against this box,
  // so it stays up for as long as the list does and leaves before the footer.
  host: { class: 'block' },
  template: `
    <app-message-filter
      [areaRoots]="store.areaRoots()"
      [rootArea]="store.rootArea()"
      [subAreas]="store.subAreas()"
      [subAreaSelection]="store.subAreaSelection()"
      [mainTypes]="store.mainTypes()"
      [activeNow]="store.activeNow()"
      [canPrint]="store.canPrintList()"
      [browsingPublication]="store.publicationId() !== undefined"
      (rootAreaChange)="store.selectRootArea($event)"
      (mainTypesChange)="store.setMainTypes($event)"
      (activeNowChange)="store.toggleActiveNow()"
      (subAreaToggle)="store.toggleSubArea($event)"
      (printRequested)="print()"
    />
    <router-outlet />
  `,
})
export class MessagesComponent {
  protected readonly store = inject(MessagesStore);

  constructor() {
    // The list is read afresh whenever the reader comes back to it, so the place Back returns
    // them to exists only once it has arrived.
    restoreScrollWhenSettled(() => !this.store.loading());
  }

  /**
   * The backend renders the sheet. `noopener` is what an `<a rel="noopener">` would buy: the
   * tab the PDF lands in gets no handle on this one.
   *
   * The chip is inert while there is no list to print, and this asks again for a keyboard or an
   * assistive path that reaches the action anyway: an address with no message series in it is a
   * request for every published series, which is not what the reader is looking at.
   */
  protected print(): void {
    if (!this.store.canPrintList()) return;
    window.open(this.store.printPdfUrl(), '_blank', 'noopener');
  }
}
