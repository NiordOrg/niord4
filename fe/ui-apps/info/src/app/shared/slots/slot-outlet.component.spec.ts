import { Component, ErrorHandler, type Type } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideSlot, type SlotName } from './component-slots';
import { SlotOutletComponent } from './slot-outlet.component';

@Component({ selector: 'app-first-note', template: `<p>first</p>` })
class FirstNote {}

@Component({ selector: 'app-second-note', template: `<p>second</p>` })
class SecondNote {}

@Component({
  imports: [SlotOutletComponent],
  template: `<div><app-slot name="layout.beforeFooter" /></div>`,
})
class Host {}

/** A loader the test settles itself, to see the outlet before and after the chunk arrives. */
function deferredLoader() {
  let resolve!: (component: Type<unknown>) => void;
  let reject!: (error: unknown) => void;
  const promise = new Promise<Type<unknown>>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { load: vi.fn(() => promise), resolve, reject };
}

describe('SlotOutletComponent', () => {
  let fixture: ComponentFixture<Host>;
  const errorHandler = { handleError: vi.fn() };

  async function render(...providers: ReturnType<typeof provideSlot>[]) {
    errorHandler.handleError.mockClear();
    TestBed.configureTestingModule({
      imports: [Host],
      providers: [...providers, { provide: ErrorHandler, useValue: errorHandler }],
    });
    fixture = TestBed.createComponent(Host);
    await fixture.whenStable();
  }

  async function arrive(): Promise<void> {
    await new Promise((resolve) => setTimeout(resolve));
    await fixture.whenStable();
  }

  const slot = () => (fixture.nativeElement as HTMLElement).querySelector('app-slot')!;

  // Every build of the base product names the slot and registers nothing for it.
  it('renders nothing when no country registered a component', async () => {
    await render();
    expect(slot().children).toHaveLength(0);
    expect(slot().textContent).toBe('');
  });

  it('renders the registered component once its chunk has arrived, and fetches it once', async () => {
    const note = deferredLoader();
    await render(provideSlot('layout.beforeFooter', note.load));
    expect(slot().querySelector('app-first-note')).toBeNull();

    note.resolve(FirstNote);
    await arrive();
    expect(slot().querySelector('app-first-note')!.textContent).toBe('first');

    fixture.detectChanges();
    await arrive();
    expect(note.load).toHaveBeenCalledOnce();
  });

  it('keeps the order of the registrations, whichever chunk arrives first', async () => {
    const first = deferredLoader();
    const second = deferredLoader();
    await render(
      provideSlot('layout.beforeFooter', first.load),
      provideSlot('layout.beforeFooter', second.load),
    );

    second.resolve(SecondNote);
    await arrive();
    first.resolve(FirstNote);
    await arrive();

    expect(Array.from(slot().children, (child) => child.tagName.toLowerCase())).toEqual([
      'app-first-note',
      'app-second-note',
    ]);
  });

  it('ignores a registration for another slot', async () => {
    const elsewhere = deferredLoader();
    await render(provideSlot('home.elsewhere' as SlotName, elsewhere.load));
    elsewhere.resolve(FirstNote);
    await arrive();

    expect(elsewhere.load).not.toHaveBeenCalled();
    expect(slot().children).toHaveLength(0);
  });

  // A country component whose chunk cannot be fetched must not take the page around it along.
  it('renders nothing for a loader that fails, reports it, and still renders the others', async () => {
    const broken = deferredLoader();
    const working = deferredLoader();
    await render(
      provideSlot('layout.beforeFooter', broken.load),
      provideSlot('layout.beforeFooter', working.load),
    );
    const failure = new Error('chunk failed');

    broken.reject(failure);
    working.resolve(SecondNote);
    await arrive();

    expect(errorHandler.handleError).toHaveBeenCalledExactlyOnceWith(failure);
    expect(Array.from(slot().children, (child) => child.tagName.toLowerCase())).toEqual([
      'app-second-note',
    ]);
  });
});
