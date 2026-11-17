import { Component, DestroyRef } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { HttpRequest, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter, Routes } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';

import { messagesRoutes } from './messages.routes';
import { MessagesStore } from './messages.store';
import { LanguageService } from '../../core/services/language.service';
import { provideInfoIcons } from '../../shared/icons/info-icons';
import { getTranslocoTestingModule, provideTestNiordConfig } from '../../testing/test-config';

/** Stands in for the publications page: the spec only needs somewhere else to be. */
@Component({ template: '' })
class ElsewhereComponent {}

const routes: Routes = [
  { path: 'messages', children: messagesRoutes },
  { path: 'publications', component: ElsewhereComponent },
];

const isMessagesRead = (request: HttpRequest<unknown>) =>
  request.url === '/rest/public/v1/messages';

/** Resources resolve on a microtask, and a component created on the first pass reads on the next. */
async function settle(): Promise<void> {
  TestBed.tick();
  await Promise.resolve();
  TestBed.tick();
  await Promise.resolve();
}

/**
 * Where the message list lives, proved through the router rather than through the route
 * configuration: no server data outlives the view that shows it.
 */
describe('the lifetime of the messages store', () => {
  let httpTesting: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [getTranslocoTestingModule()],
      providers: [
        provideRouter(routes),
        provideHttpClient(),
        provideHttpClientTesting(),
        provideInfoIcons(),
        // No root areas, so the list reads straight away and every request the spec counts is
        // the message read itself.
        provideTestNiordConfig({ rootAreas: [], languages: ['en', 'da'] }),
      ],
    });
    httpTesting = TestBed.inject(HttpTestingController);
  });

  it('destroys the store on the way out, reads nothing while away, and starts over on return', async () => {
    const harness = await RouterTestingHarness.create('/messages/details');
    await settle();

    const first = harness.routeDebugElement!.injector.get(MessagesStore);
    let destroyed = 0;
    harness.routeDebugElement!.injector.get(DestroyRef).onDestroy(() => destroyed++);

    // Entering the route reads the list once.
    httpTesting.match(isMessagesRead).forEach((request) => request.flush([]));
    expect(destroyed).toBe(0);

    await harness.navigateByUrl('/publications');
    await settle();
    expect(destroyed).toBe(1);

    // The store the reader left behind must not be listening any more. A language switch is the
    // cheapest way to ask: it changes the key the list is read on, so a store still alive would
    // fetch a list nobody is looking at — and hold it for the next visit, which is the whole
    // reason the store is not application-wide.
    TestBed.inject(LanguageService).setLanguage('da');
    await settle();
    expect(httpTesting.match(isMessagesRead)).toHaveLength(0);

    await harness.navigateByUrl('/messages/details');
    await settle();

    const second = harness.routeDebugElement!.injector.get(MessagesStore);
    expect(second).not.toBe(first);
    expect(httpTesting.match(isMessagesRead)).toHaveLength(1);
  });

  it('keeps one store across the three views of the same list', async () => {
    const harness = await RouterTestingHarness.create('/messages/details');
    await settle();
    const store = harness.routeDebugElement!.injector.get(MessagesStore);
    httpTesting.match(isMessagesRead).forEach((request) => request.flush([]));

    await harness.navigateByUrl('/messages/table');
    await settle();
    expect(harness.routeDebugElement!.injector.get(MessagesStore)).toBe(store);

    await harness.navigateByUrl('/messages/map');
    await settle();
    expect(harness.routeDebugElement!.injector.get(MessagesStore)).toBe(store);

    // Switching tabs re-renders the rows that are already there; it does not re-read them.
    expect(httpTesting.match(isMessagesRead)).toHaveLength(0);
  });
});
