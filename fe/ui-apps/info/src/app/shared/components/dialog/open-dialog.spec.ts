import {
  Component,
  createEnvironmentInjector,
  EnvironmentInjector,
  ErrorHandler,
  Injector,
} from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Dialog } from '@angular/cdk/dialog';
import { openInfoDialog } from './open-dialog';

@Component({ selector: 'app-some-dialog', template: '' })
class SomeDialog {}

describe('openInfoDialog', () => {
  let open: ReturnType<typeof vi.fn>;
  let handleError: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    open = vi.fn(() => ({ closed: { subscribe: vi.fn() } }));
    handleError = vi.fn();
    TestBed.configureTestingModule({
      providers: [
        { provide: Dialog, useValue: { open } },
        { provide: ErrorHandler, useValue: { handleError } },
      ],
    });
  });

  /** An opener's injector, standing for a view that is still on the page. */
  function opener(): Injector {
    return createEnvironmentInjector([], TestBed.inject(EnvironmentInjector));
  }

  /** A component fetch the spec answers when it chooses to. */
  function deferredComponent() {
    let resolve!: (type: typeof SomeDialog) => void;
    let reject!: (error: unknown) => void;
    const promise = new Promise<typeof SomeDialog>((res, rej) => {
      resolve = res;
      reject = rej;
    });
    return { load: () => promise, resolve, reject };
  }

  it('opens the fetched component with the site chrome and the data the opener gives it', async () => {
    const ref = await openInfoDialog(opener(), async () => SomeDialog, { messageId: 'msg-1' });

    expect(open).toHaveBeenCalledOnce();
    const [component, config] = open.mock.calls[0];
    expect(component).toBe(SomeDialog);
    expect(config).toMatchObject({
      data: { messageId: 'msg-1' },
      panelClass: 'dialog-pane',
      backdropClass: 'dialog-backdrop',
      ariaModal: true,
    });
    expect(ref).toBe(open.mock.results[0].value);
  });

  // On a slow link nothing happens for a while after the click, and a reader clicks again.
  it('drops a second request while the first is still fetching its dialog', async () => {
    const fetch = deferredComponent();
    const first = openInfoDialog(opener(), fetch.load, { messageId: 'msg-1' });
    const second = openInfoDialog(opener(), async () => SomeDialog, { messageId: 'msg-2' });

    expect(await second).toBeUndefined();
    fetch.resolve(SomeDialog);
    await first;
    expect(open).toHaveBeenCalledOnce();
    expect(open.mock.calls[0][1].data).toEqual({ messageId: 'msg-1' });

    // Once that one is open, the next request is served again.
    await openInfoDialog(opener(), async () => SomeDialog, { messageId: 'msg-3' });
    expect(open).toHaveBeenCalledTimes(2);
  });

  // The reader followed a link while the dialog was on its way; it would open over a page it has
  // nothing to do with.
  it('opens nothing for an opener that is gone by the time the fetch lands', async () => {
    const view = createEnvironmentInjector([], TestBed.inject(EnvironmentInjector));
    const fetch = deferredComponent();
    const opening = openInfoDialog(view, fetch.load, null);

    view.destroy();
    fetch.resolve(SomeDialog);
    expect(await opening).toBeUndefined();
    expect(open).not.toHaveBeenCalled();
  });

  // A chunk the server no longer has, after a deploy, or a link that dropped mid-fetch.
  it('reports a fetch that fails, opens nothing, and fetches again on the next request', async () => {
    const fetch = deferredComponent();
    const failed = openInfoDialog(opener(), fetch.load, null);
    const error = new TypeError('Failed to fetch dynamically imported module');
    fetch.reject(error);

    await expect(failed).resolves.toBeUndefined();
    expect(handleError).toHaveBeenCalledWith(error);
    expect(open).not.toHaveBeenCalled();

    await openInfoDialog(opener(), async () => SomeDialog, null);
    expect(open).toHaveBeenCalledOnce();
  });
});
