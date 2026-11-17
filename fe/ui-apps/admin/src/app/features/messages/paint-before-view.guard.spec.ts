import type { ActivatedRouteSnapshot, RouterStateSnapshot } from '@angular/router';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { paintBeforeView } from './paint-before-view.guard';

const run = () =>
  paintBeforeView({} as ActivatedRouteSnapshot, {} as RouterStateSnapshot) as Promise<boolean>;

describe('paintBeforeView', () => {
  afterEach(() => vi.restoreAllMocks());

  it('lets a page that is not visible build its view without waiting for a frame', async () => {
    vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('hidden');
    const frame = vi.spyOn(window, 'requestAnimationFrame');
    await expect(run()).resolves.toBe(true);
    expect(frame).not.toHaveBeenCalled();
  });

  it('lets a visible page build its view only after the next frame and the task after it', async () => {
    vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible');
    let frame: FrameRequestCallback | undefined;
    vi.spyOn(window, 'requestAnimationFrame').mockImplementation((callback) => {
      frame = callback;
      return 1;
    });
    let settled = false;
    const result = run();
    void result.then(() => (settled = true));

    await new Promise((resolve) => setTimeout(resolve));
    expect(settled).toBe(false);
    expect(frame).toBeDefined();

    frame?.(performance.now());
    await expect(result).resolves.toBe(true);
  });
});
