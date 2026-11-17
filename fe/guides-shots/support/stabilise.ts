import { expect, type Page, type Request } from '@playwright/test';

const STYLE_ID = 'guides-shots-stabiliser';

/**
 * What every picture leaves out or holds still. The administration app's deployment-mode badge
 * (under the logo) and the public site's corner ribbon name the local run, not the product; toasts,
 * the caret and scrollbars come and go with timing; an animation or a transition would be caught
 * half-way. Durations are cut to a hundredth of a millisecond rather than to zero, because a
 * transition of zero length fires no transitionend, and a component waiting for one would hang.
 * A shot that wants its toast adds the class `guides-show-toasts` to the root element, and a shot
 * of the deployment badge the class `guides-show-badge`.
 */
const STABILISER_CSS = `
*, *::before, *::after {
  animation-duration: 0.01ms !important;
  animation-delay: 0s !important;
  animation-iteration-count: 1 !important;
  transition-duration: 0.01ms !important;
  transition-delay: 0s !important;
  caret-color: transparent !important;
  scroll-behavior: auto !important;
  scrollbar-width: none !important;
}
::-webkit-scrollbar {
  display: none !important;
}
html:not(.guides-show-badge) app-main-header div.relative.shrink-0 > span {
  display: none !important;
}
.execution-mode {
  display: none !important;
}
html:not(.guides-show-toasts) .toast-pane {
  visibility: hidden !important;
}
`;

/**
 * Adds the stabiliser style to every document the page loads, before any picture is taken. A
 * document of an opaque origin (a sandboxed frame: the application's `app-sandboxed-html`, which
 * shows a mail or a report's result) is left alone: Playwright runs an init script in every frame,
 * and in a frame that may run no script the browser refuses the script's listener and logs it.
 */
export async function installStabiliser(page: Page): Promise<void> {
  await page.addInitScript(
    ({ id, css }) => {
      if (window.origin === 'null') return;
      const add = (): void => {
        if (document.getElementById(id)) return;
        const style = document.createElement('style');
        style.id = id;
        style.textContent = css;
        (document.head ?? document.documentElement)?.appendChild(style);
      };
      if (document.documentElement) add();
      document.addEventListener('DOMContentLoaded', add);
    },
    { id: STYLE_ID, css: STABILISER_CSS },
  );
}

// The requests each page has open. A server-sent event stream or a socket stays open for the
// page's lifetime, so neither counts.
const openRequests = new WeakMap<Page, Set<Request>>();
const LONG_LIVED = new Set(['eventsource', 'websocket']);

/** Starts counting the page's open requests; waitForQuiet reads the count. */
export function trackRequests(page: Page): void {
  const open = new Set<Request>();
  openRequests.set(page, open);
  page.on('request', (request) => {
    // A new document in the main frame ends the old one's requests, and a request cut off that
    // way reports neither finished nor failed: it would count as open for ever.
    if (request.isNavigationRequest() && request.frame() === page.mainFrame()) open.clear();
    if (!LONG_LIVED.has(request.resourceType())) open.add(request);
  });
  const done = (request: Request): void => {
    open.delete(request);
  };
  page.on('requestfinished', done);
  page.on('requestfailed', done);
}

function pause(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

/**
 * Waits until the page has had no open request for `quietMs`. Playwright's own `networkidle` is
 * reached once per navigation, so it says nothing about the requests a single-page application
 * makes after it, and a picture is taken long after.
 */
export async function waitForQuiet(page: Page, quietMs = 500, timeoutMs = 30_000): Promise<void> {
  const open = openRequests.get(page);
  if (!open) return;
  const deadline = Date.now() + timeoutMs;
  let quietSince = Date.now();
  for (;;) {
    if (open.size > 0) quietSince = Date.now();
    else if (Date.now() - quietSince >= quietMs) return;
    if (Date.now() > deadline) {
      const urls = [...open].slice(0, 5).map((request) => request.url());
      throw new Error(`The page kept loading for ${timeoutMs} ms; still open: ${urls.join(', ')}`);
    }
    await pause(50);
  }
}

type MapState = 'rendered' | 'no-handle' | 'not-attached' | 'timed-out';

/**
 * Waits until every OpenLayers map on the page has drawn all its tiles and features. The map kit
 * offers no signal of its own, so this asks each `app-ol-map` component for its OpenLayers map
 * through Angular's development-mode debugging API (both dev servers run unoptimised builds) and
 * waits for the map's `rendercomplete`, which OpenLayers fires once no tile is loading or queued.
 * Without that API it falls back to a fixed pause after the network went quiet.
 */
export async function waitForMaps(page: Page, timeoutMs = 30_000): Promise<void> {
  const states = await page.evaluate(async (timeout): Promise<MapState[]> => {
    interface RenderableMap {
      once(type: 'rendercomplete', listener: () => void): unknown;
      render(): void;
      getTargetElement(): HTMLElement | null;
    }
    interface Debug {
      getComponent?: (element: Element) => unknown;
    }
    const debug = (window as unknown as { ng?: Debug }).ng;
    const hosts = Array.from(document.querySelectorAll('app-ol-map'));
    return Promise.all(
      hosts.map(
        (host) =>
          new Promise<MapState>((resolve) => {
            const component = debug?.getComponent?.(host) as { map?: RenderableMap } | null;
            const map = component?.map;
            if (!map || typeof map.once !== 'function') return resolve('no-handle');
            if (!map.getTargetElement()) return resolve('not-attached');
            const timer = setTimeout(() => resolve('timed-out'), timeout);
            map.once('rendercomplete', () => {
              clearTimeout(timer);
              resolve('rendered');
            });
            map.render();
          }),
      ),
    );
  }, timeoutMs);
  if (states.includes('timed-out')) {
    throw new Error(`A map did not finish drawing within ${timeoutMs} ms (tiles still loading?).`);
  }
  if (states.includes('no-handle')) await pause(1500);
}

/** What settle waits for; each part can be left out for a page that has none. */
export interface SettleOptions {
  /** Wait for the maps to finish drawing (default true). */
  readonly maps?: boolean;
  /** Wait until no progress bar shows and nothing is marked busy (default true). */
  readonly busy?: boolean;
}

/**
 * Waits until the page is still: loaded, nothing busy, no request open, the fonts in, every map
 * drawn, then two frames painted. A map that has drawn may start new requests (the messages map
 * searches the extent it settled on), so the network and the maps are waited for twice.
 */
export async function settle(page: Page, options: SettleOptions = {}): Promise<void> {
  await page.waitForLoadState('load');
  if (options.busy !== false) {
    const busy = page.locator('[role="progressbar"]:visible, [aria-busy="true"]:visible');
    await expect(busy).toHaveCount(0, { timeout: 30_000 });
  }
  await waitForQuiet(page);
  await page.evaluate(async () => {
    await document.fonts.ready;
  });
  if (options.maps !== false) {
    await waitForMaps(page);
    await waitForQuiet(page);
    await waitForMaps(page);
  }
  await page.evaluate(
    () =>
      new Promise<void>((resolve) =>
        requestAnimationFrame(() => requestAnimationFrame(() => resolve())),
      ),
  );
}
