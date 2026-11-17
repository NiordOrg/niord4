import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { nextPaint, paintedOnce } from './next-paint';

/** Makes the page visible or not, and holds the frame callbacks until {@link runFrame}. */
function page(visibility: DocumentVisibilityState) {
  vi.spyOn(document, 'visibilityState', 'get').mockReturnValue(visibility);
  const frames: FrameRequestCallback[] = [];
  vi.spyOn(window, 'requestAnimationFrame').mockImplementation((callback) => {
    frames.push(callback);
    return frames.length;
  });
  return {
    frames,
    runFrame: () => frames.splice(0).forEach((callback) => callback(performance.now())),
  };
}

const aTask = () => new Promise((resolve) => setTimeout(resolve));

@Component({ template: '' })
class Host {
  readonly painted = paintedOnce();
}

describe('nextPaint', () => {
  afterEach(() => vi.restoreAllMocks());

  it('resolves at once on a page that is not visible', async () => {
    const { frames } = page('hidden');
    await nextPaint();
    expect(frames).toHaveLength(0);
  });

  it('resolves in the task after the next frame on a visible page', async () => {
    const { runFrame } = page('visible');
    let resolved = false;
    void nextPaint().then(() => (resolved = true));

    await aTask();
    expect(resolved).toBe(false);

    runFrame();
    expect(resolved).toBe(false);
    await aTask();
    expect(resolved).toBe(true);
  });
});

describe('paintedOnce', () => {
  afterEach(() => vi.restoreAllMocks());

  it('is true from the start on a page that is not visible', () => {
    page('hidden');
    const fixture = TestBed.createComponent(Host);
    expect(fixture.componentInstance.painted()).toBe(true);
  });

  it('turns true only after the view has been rendered and a frame painted', async () => {
    const { frames, runFrame } = page('visible');
    const fixture = TestBed.createComponent(Host);
    expect(fixture.componentInstance.painted()).toBe(false);

    fixture.detectChanges();
    await aTask();
    expect(frames.length).toBeGreaterThan(0);
    expect(fixture.componentInstance.painted()).toBe(false);

    runFrame();
    await aTask();
    expect(fixture.componentInstance.painted()).toBe(true);
  });
});
