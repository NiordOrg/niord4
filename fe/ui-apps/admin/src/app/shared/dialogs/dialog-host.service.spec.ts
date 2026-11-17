import { Observable } from 'rxjs';
import { describe, expect, it } from 'vitest';

import { DialogHostService } from './dialog-host.service';

/** Minimal concrete host exposing the protected `armOpen()` as a typed `open()`. */
class TestDialogHost extends DialogHostService<string> {
  open(): Observable<string | undefined> {
    return this.armOpen();
  }
}

describe('DialogHostService — dismiss re-entrancy branch', () => {
  it('re-entrant dismiss from within a subscriber is a safe no-op (single emission)', () => {
    const host = new TestDialogHost();
    const received: (string | undefined)[] = [];

    host.open().subscribe((value) => {
      received.push(value);
      // Re-enter dismiss() while the first dismiss is still resolving. `visible` has
      // already been flipped to false, so this second call must short-circuit rather
      // than emit again onto the same (about-to-complete) subject.
      host.dismiss();
    });

    expect(host.visible()).toBe(true);
    host.dismiss();

    expect(received).toEqual([undefined]);
    expect(host.visible()).toBe(false);
  });

  it('a second dismiss after the dialog is already hidden emits nothing more', () => {
    const host = new TestDialogHost();
    const received: (string | undefined)[] = [];
    let completions = 0;

    host.open().subscribe({
      next: (value) => received.push(value),
      complete: () => completions++,
    });

    host.dismiss();
    host.dismiss(); // already hidden — no-op

    expect(received).toEqual([undefined]);
    expect(completions).toBe(1);
    expect(host.visible()).toBe(false);
  });

  it('dismiss before any open never emits and does not throw', () => {
    const host = new TestDialogHost();
    expect(host.visible()).toBe(false);
    expect(() => host.dismiss()).not.toThrow();
    expect(host.visible()).toBe(false);
  });

  it('dismiss after a submit (already hidden) does not deliver a second result', () => {
    const host = new TestDialogHost();
    const received: (string | undefined)[] = [];

    host.open().subscribe((value) => received.push(value));
    host.submit('ok');
    host.dismiss(); // visible already false — no undefined leaks in behind the submitted value

    expect(received).toEqual(['ok']);
    expect(host.visible()).toBe(false);
  });
});
