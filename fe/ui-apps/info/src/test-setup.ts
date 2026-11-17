import { getTestBed } from '@angular/core/testing';
import { beforeEach } from 'vitest';

// OpenLayers calls document.createElement('canvas').getContext(...) at module load, and
// jsdom has no canvas implementation, so the import itself throws. Returning null is what
// jsdom would return with the optional canvas package absent, and every OpenLayers path
// these specs reach treats a null context as "cannot render".
HTMLCanvasElement.prototype.getContext = (() => null) as never;

// OpenLayers observes the size of the element it draws into, and jsdom implements no
// ResizeObserver at all — constructing a Map throws without this. The stub never reports a
// size, which is what an element jsdom gives no layout to would report anyway.
const ignoreSize = () => undefined;
globalThis.ResizeObserver ??= class {
  observe = ignoreSize;
  unobserve = ignoreSize;
  disconnect = ignoreSize;
} as unknown as typeof ResizeObserver;

// Cross-test isolation for the single-fork runner, where the TestBed singleton and jsdom's
// storage are shared across spec files.
//
// 1. The site remembers the language, the root area and the two main types under
//    `niord-info.*`; all four leak into later files whose expected request URL is
//    language- or area-scoped.
// 2. Several specs call `TestBed.inject(HttpTestingController).verify()` in an
//    `afterEach`, which re-instantiates the module after teardown; the next file's
//    `configureTestingModule()` then throws "Cannot configure the test module when the
//    test module has already been instantiated".
beforeEach(() => {
  try {
    localStorage.clear();
    sessionStorage.clear();
  } catch {
    // No DOM storage in this environment — nothing to isolate.
  }
  getTestBed().resetTestingModule();
});
