import {
  computed,
  DestroyRef,
  DOCUMENT,
  inject,
  Injectable,
  signal,
  untracked,
} from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { httpResource } from '@angular/common/http';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import type { NavigationError } from '@angular/router';
import { SwUpdate } from '@angular/service-worker';
import { catchError, filter, finalize, map, of, timeout } from 'rxjs';

import { APP_CONFIG } from '../config/app-config.token';

/** Matches the hashed main-bundle script names Angular emits (`main-ABC123.js`). */
const MAIN_SCRIPT_RE = /src="([^"]*main[-.][^"]*\.js)"/g;

/**
 * Deploy-skew detection for a separately-deployed SPA.
 *
 * Because the frontend deploys independently of the backend, comparing a
 * build stamp baked into the web bundle against the backend's build version
 * would be meaningless here. Instead:
 *
 * - {@link serverBuildVersion} surfaces the backend build stamp (shown as the
 *   banner's detail line).
 * - {@link webUpdateAvailable} is true once anything shows that the running
 *   build is no longer the deployed one, which a reload is the only cure for:
 *   - the DEPLOYED `index.html` references a different hashed `main-*.js` than
 *     the one currently RUNNING. Probed at start-up and again whenever the tab
 *     comes back into view, because a tab left open across a deploy is exactly
 *     the case this exists for and nothing else re-reads `index.html` while it
 *     sits in the background. On the dev server the bundle is unhashed, so the
 *     sets always match and this part never fires;
 *   - the running build failed to load one of its own lazy chunks — the new
 *     deploy removed it — reported by the router's navigation error handler
 *     through {@link reportMissingChunk};
 *   - the service worker has a newer version ready, or has declared the version
 *     this tab runs unrecoverable because its files are gone from the server.
 *
 * Every probe is best-effort: a fetch error resolves to "no conflict" and keeps
 * whatever an earlier probe found. Nothing here reloads the page; the banner the
 * shell renders from {@link webUpdateAvailable} offers the reload.
 */
@Injectable({ providedIn: 'root' })
export class BuildVersionService {
  private readonly http = inject(HttpClient);
  private readonly config = inject(APP_CONFIG);
  private readonly document = inject(DOCUMENT);
  private readonly destroyRef = inject(DestroyRef);

  private readonly serverVersionResource = httpResource.text(
    () => `${this.config.apiBaseUrl}/version/build-version`,
    { defaultValue: '' },
  );

  /** The backend's build stamp (e.g. `04-07-2026 11:45 UTC`); `''` while loading / on error. */
  readonly serverBuildVersion = computed(() =>
    this.serverVersionResource.hasValue() ? this.serverVersionResource.value().trim() : '',
  );

  /** The hashed main-bundle script(s) the deployed index.html referenced at the latest probe. */
  private readonly deployedMainScripts = signal<readonly string[] | undefined>(undefined);

  /** Set once the running build has shown it can no longer load or serve its own files. */
  private readonly runningBuildGone = signal(false);

  /** Set once the service worker has downloaded a newer version that a reload would activate. */
  private readonly workerUpdateReady = signal(false);

  /** Whether an index.html probe is on its way. */
  private probing = false;

  /** The main-bundle script(s) the RUNNING document loaded. */
  private readonly runningMainScripts = [...this.document.querySelectorAll('script[src]')]
    .map((el) => scriptName(el.getAttribute('src') ?? ''))
    .filter((name) => /^main[-.].*\.js$/.test(name))
    .sort();

  /** Whether the deployed index.html names a main bundle other than the running one. */
  private readonly mainBundleDiffers = computed(() => {
    const deployed = this.deployedMainScripts();
    if (!deployed || deployed.length === 0 || this.runningMainScripts.length === 0) {
      return false;
    }
    return deployed.join(',') !== this.runningMainScripts.join(',');
  });

  /** True when the running build is no longer the deployed one — reload to update. */
  readonly webUpdateAvailable = computed(
    () => this.runningBuildGone() || this.workerUpdateReady() || this.mainBundleDiffers(),
  );

  /** Short web-build fingerprint for the banner's detail line (the main bundle name). */
  readonly webBuildVersion = this.runningMainScripts.join(', ');

  private readonly onVisibilityChange = (): void => {
    if (this.document.visibilityState === 'visible') {
      this.probeDeployedIndex();
    }
  };

  constructor() {
    this.probeDeployedIndex();
    this.document.addEventListener('visibilitychange', this.onVisibilityChange);

    this.destroyRef.onDestroy(() =>
      this.document.removeEventListener('visibilitychange', this.onVisibilityChange),
    );

    // Disabled (dev server, localhost, unit tests) the worker's streams never emit.
    const worker = inject(SwUpdate);
    worker.versionUpdates
      .pipe(
        filter((event) => event.type === 'VERSION_READY'),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe(() => this.workerUpdateReady.set(true));
    worker.unrecoverable
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.runningBuildGone.set(true));
  }

  /**
   * Records that the running build failed to fetch one of its own lazily-loaded modules. After a
   * deploy the old build's chunks are gone from the server, so every view this tab has not opened
   * yet is out of reach until the page is reloaded.
   */
  reportMissingChunk(): void {
    this.runningBuildGone.set(true);
  }

  private probeDeployedIndex(): void {
    // One probe at a time, and none once a reload is already called for: the answer can only
    // repeat what the banner says.
    if (this.probing || untracked(this.webUpdateAvailable)) {
      return;
    }
    this.probing = true;
    this.http
      // Resolve against the <base href>, not the current route — a fetch from a
      // deep link like /messages/map must still hit the app root's index.html.
      .get(new URL('index.html', this.document.baseURI).toString(), {
        responseType: 'text',
        // This probe only means anything if it reaches the network. `Cache-Control: no-cache`
        // governs the HTTP cache but NOT Cache Storage, so a controlling service worker would
        // answer it from the cached app shell and the comparison below would always report
        // "up to date". `ngsw-bypass` is honoured before the worker's fetch handling.
        // Header form, not the `?ngsw-bypass` query form — the latter changes the request URL.
        headers: { 'Cache-Control': 'no-cache', 'ngsw-bypass': 'true' },
      })
      .pipe(
        timeout(5000),
        map((html) => [...html.matchAll(MAIN_SCRIPT_RE)].map((m) => scriptName(m[1])).sort()),
        catchError(() => of(undefined)),
        finalize(() => (this.probing = false)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((deployed) => {
        if (deployed) {
          this.deployedMainScripts.set(deployed);
        }
      });
  }
}

/**
 * How the browsers word a failed fetch of a lazily-loaded module — Chromium, Firefox and Safari in
 * that order. None of them gives the failure a type of its own, so the message is all there is.
 */
const CHUNK_LOAD_FAILURE =
  /Failed to fetch dynamically imported module|error loading dynamically imported module|Importing a module script failed/i;

/** Whether an error is the browser failing to fetch a lazily-loaded module. */
export function isChunkLoadError(error: unknown): boolean {
  const message = error instanceof Error ? error.message : typeof error === 'string' ? error : '';
  return CHUNK_LOAD_FAILURE.test(message);
}

/**
 * The router's navigation error handler. A navigation that failed because its lazily-loaded route
 * could not be fetched means the running build has been replaced under this tab, and the update
 * banner says so. The navigation itself stays failed: the view it would have opened is not part of
 * the deployed build any more, and reloading is the user's decision, never the application's.
 */
export function reportMissingChunkOnNavigationError(event: NavigationError): void {
  if (isChunkLoadError(event.error)) {
    inject(BuildVersionService).reportMissingChunk();
  }
}

/** The bare file name of a script URL (drops path + query). */
function scriptName(src: string): string {
  return src.split('?')[0].split('/').pop() ?? '';
}
