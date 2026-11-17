import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { provideTestTransloco } from '../../../../shared/testing/transloco-testing';
import type { Message } from '../../models/message.model';
import { MessagesFacade } from '../../services/messages-facade.service';
import { MapResultList } from './map-result-list';

const SAMPLE: readonly Message[] = [
  { id: 'm1', shortId: 'NW-1' },
  { id: 'm2', shortId: 'NW-2' },
];

@Component({
  template: `<app-map-result-list
    [messages]="messages()"
    [total]="total()"
    [loading]="loading()"
    (messageClicked)="clicked = $event"
  />`,
  imports: [MapResultList],
})
class HostComponent {
  readonly messages = signal<readonly Message[]>([]);
  readonly total = signal(0);
  readonly loading = signal(false);
  clicked: string | undefined;
}

describe('MapResultList', () => {
  let fixture: ReturnType<typeof TestBed.createComponent<HostComponent>>;

  beforeEach(() => {
    const facadeStub = {
      list: {
        loading: signal(false),
        error: signal(null),
        retry: vi.fn(),
        hasMore: signal(false),
        messages: signal([] as readonly Message[]),
        total: signal(0),
        loadMore: vi.fn(),
      },
    } as unknown as MessagesFacade;
    TestBed.configureTestingModule({
      providers: [provideTestTransloco(), { provide: MessagesFacade, useValue: facadeStub }],
    });
    fixture = TestBed.createComponent(HostComponent);
  });

  afterEach(() => fixture.destroy());

  // The panel starts collapsed (body hidden). Tests that assert on body
  // contents click the header button first to expand.
  function expand(): void {
    const button = fixture.nativeElement.querySelector('header button') as HTMLButtonElement;
    button.click();
    fixture.detectChanges();
  }

  it('starts collapsed — header is visible but the body is not rendered', () => {
    fixture.componentInstance.messages.set(SAMPLE);
    fixture.componentInstance.total.set(2);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('header')).not.toBeNull();
    expect(fixture.nativeElement.querySelectorAll('li')).toHaveLength(0);
    expect(fixture.nativeElement.querySelector('app-empty-search-result')).toBeNull();
  });

  it('renders the empty state when there are no messages and the panel is expanded', () => {
    fixture.detectChanges();
    expand();
    expect(fixture.nativeElement.querySelector('app-empty-search-result')).not.toBeNull();
  });

  it('renders one row per loaded message when expanded', () => {
    fixture.componentInstance.messages.set(SAMPLE);
    fixture.componentInstance.total.set(2);
    fixture.detectChanges();
    expand();
    expect(fixture.nativeElement.querySelectorAll('li')).toHaveLength(2);
  });

  it('emits messageClicked with the message UUID on row click', () => {
    fixture.componentInstance.messages.set(SAMPLE);
    fixture.componentInstance.total.set(2);
    fixture.detectChanges();
    expand();
    const firstButton = fixture.nativeElement.querySelector('li button') as HTMLButtonElement;
    firstButton.click();
    expect(fixture.componentInstance.clicked).toBe('m1');
  });

  it('shows the partial-result heading when messages.length < total', () => {
    fixture.componentInstance.messages.set(SAMPLE);
    fixture.componentInstance.total.set(10);
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('messages.list.mapPanel.partialResult');
  });

  it('toggling collapse hides the body again after expanding', () => {
    fixture.componentInstance.messages.set(SAMPLE);
    fixture.componentInstance.total.set(2);
    fixture.detectChanges();
    expand();
    expect(fixture.nativeElement.querySelectorAll('li')).toHaveLength(2);
    expand(); // toggle is the same button — clicking again collapses
    expect(fixture.nativeElement.querySelectorAll('li')).toHaveLength(0);
  });
});
