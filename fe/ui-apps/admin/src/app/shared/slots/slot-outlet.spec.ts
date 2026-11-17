import {
  Component,
  ErrorHandler,
  type EnvironmentProviders,
  type Provider,
  type Type,
} from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';

import { provideSlot } from './component-slots';
import { SlotOutlet } from './slot-outlet';

@Component({ selector: 'app-test-first', template: 'first' })
class First {}

@Component({ selector: 'app-test-second', template: 'second' })
class Second {}

@Component({
  imports: [SlotOutlet],
  template: `<app-slot name="home.welcome" />`,
})
class Host {}

/** A loader the test resolves or rejects itself. */
function deferred() {
  let resolve!: (component: Type<unknown>) => void;
  let reject!: (error: unknown) => void;
  const promise = new Promise<Type<unknown>>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { load: () => promise, promise, resolve, reject };
}

async function render(providers: (Provider | EnvironmentProviders)[]) {
  TestBed.configureTestingModule({ providers });
  const fixture = TestBed.createComponent(Host);
  fixture.detectChanges();
  await fixture.whenStable();
  return fixture;
}

/** Lets every settled loader's promise chain run out, then renders what it placed. */
async function settle(fixture: { whenStable(): Promise<unknown> }): Promise<void> {
  await new Promise((resolve) => setTimeout(resolve));
  await fixture.whenStable();
}

function slotText(fixture: { nativeElement: HTMLElement }): string {
  return fixture.nativeElement.querySelector('app-slot')?.textContent?.trim() ?? '';
}

describe('SlotOutlet', () => {
  it('renders nothing when no component is registered', async () => {
    const fixture = await render([]);
    expect(fixture.nativeElement.querySelector('app-slot')?.children.length).toBe(0);
    expect(slotText(fixture)).toBe('');
  });

  it('renders a registered component once its loader resolves', async () => {
    const first = deferred();
    const fixture = await render([provideSlot('home.welcome', first.load)]);
    expect(slotText(fixture)).toBe('');

    first.resolve(First);
    await settle(fixture);
    expect(fixture.nativeElement.querySelector('app-slot app-test-first')).toBeTruthy();
  });

  it('renders two registrations in registration order, whichever loads first', async () => {
    const first = deferred();
    const second = deferred();
    const fixture = await render([
      provideSlot('home.welcome', first.load),
      provideSlot('home.welcome', second.load),
    ]);

    second.resolve(Second);
    first.resolve(First);
    await settle(fixture);
    const rendered = [...fixture.nativeElement.querySelector('app-slot').children].map((element) =>
      (element as HTMLElement).tagName.toLowerCase(),
    );
    expect(rendered).toEqual(['app-test-first', 'app-test-second']);
  });

  it('ignores a registration for another slot', async () => {
    const load = vi.fn(() => Promise.resolve(First as Type<unknown>));
    const fixture = await render([provideSlot('other.slot' as 'home.welcome', load)]);
    expect(load).not.toHaveBeenCalled();
    expect(slotText(fixture)).toBe('');
  });

  it('renders nothing for a loader that rejects and reports it to the ErrorHandler', async () => {
    const handleError = vi.fn();
    const failing = deferred();
    const fixture = await render([
      provideSlot('home.welcome', failing.load),
      { provide: ErrorHandler, useValue: { handleError } },
    ]);

    const error = new Error('chunk failed');
    failing.reject(error);
    await settle(fixture);
    expect(handleError).toHaveBeenCalledWith(error);
    expect(slotText(fixture)).toBe('');
  });

  it('reports a loader that throws synchronously once and still renders the other registration', async () => {
    const handleError = vi.fn();
    const error = new Error('loader threw');
    const throwing = (): Promise<Type<unknown>> => {
      throw error;
    };
    const second = deferred();
    const fixture = await render([
      provideSlot('home.welcome', throwing),
      provideSlot('home.welcome', second.load),
      { provide: ErrorHandler, useValue: { handleError } },
    ]);

    second.resolve(Second);
    await settle(fixture);
    expect(handleError).toHaveBeenCalledTimes(1);
    expect(handleError).toHaveBeenCalledWith(error);
    const rendered = [...fixture.nativeElement.querySelector('app-slot').children].map((element) =>
      (element as HTMLElement).tagName.toLowerCase(),
    );
    expect(rendered).toEqual(['app-test-second']);
  });
});
