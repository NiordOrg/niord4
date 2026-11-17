import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { NavigationError } from '@angular/router';
import { SwUpdate, type UnrecoverableStateEvent, type VersionEvent } from '@angular/service-worker';
import { Subject } from 'rxjs';
import { afterEach, describe, expect, it } from 'vitest';

import { APP_CONFIG } from '../config/app-config.token';
import {
  BuildVersionService,
  isChunkLoadError,
  reportMissingChunkOnNavigationError,
} from './build-version.service';

/**
 * Drain the effect scheduler + microtask queue so the build-version httpResource
 * and the index.html `toSignal` resolve (zoneless — flush manually).
 */
async function settle(): Promise<void> {
  TestBed.tick();
  await Promise.resolve();
  TestBed.tick();
  await Promise.resolve();
}

const injectedScripts: HTMLScriptElement[] = [];

/** Add a `<script src>` to the running document BEFORE the service reads it in its constructor. */
function injectRunningScript(name: string): void {
  const el = document.createElement('script');
  // jsdom does not fetch external scripts by default, so this never triggers a load.
  el.setAttribute('src', name);
  document.head.appendChild(el);
  injectedScripts.push(el);
}

/** The two service-worker streams the service listens to, driven by the test. */
function workerStub() {
  return {
    versionUpdates: new Subject<VersionEvent>(),
    unrecoverable: new Subject<UnrecoverableStateEvent>(),
  };
}

function setup(runningNames: string[] = [], worker = workerStub()) {
  runningNames.forEach(injectRunningScript);
  TestBed.configureTestingModule({
    providers: [
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest' } },
      { provide: SwUpdate, useValue: worker },
      provideHttpClient(),
      provideHttpClientTesting(),
    ],
  });
  // Constructing the service captures runningMainScripts and fires the index.html GET.
  const service = TestBed.inject(BuildVersionService);
  const httpTesting = TestBed.inject(HttpTestingController);
  // The build-version httpResource fires on the first tick after construction.
  TestBed.tick();
  return { service, httpTesting, worker };
}

function flushIndexHtml(httpTesting: HttpTestingController, body: string): void {
  // The `ngsw-bypass` assertion is load-bearing: without that header a controlling service
  // worker answers this probe from the cached app shell and the update banner never fires again.
  httpTesting
    .expectOne((r) => r.url.endsWith('index.html') && r.headers.has('ngsw-bypass'))
    .flush(body);
}
function flushServerVersion(httpTesting: HttpTestingController, body = ''): void {
  httpTesting.expectOne((r) => r.url.endsWith('/version/build-version')).flush(body);
}
function running(service: BuildVersionService): string[] {
  return (service as unknown as { runningMainScripts: string[] }).runningMainScripts;
}

describe('BuildVersionService', () => {
  afterEach(() => {
    injectedScripts.splice(0).forEach((el) => el.remove());
    TestBed.inject(HttpTestingController).verify();
    // Back to the prototype's getter.
    delete (document as unknown as Record<string, unknown>)['visibilityState'];
  });

  it('serverBuildVersion trims the fetched text body', async () => {
    const { service, httpTesting } = setup();
    flushIndexHtml(httpTesting, '<script src="main-abc123.js"></script>');
    flushServerVersion(httpTesting, '  04-07-2026 11:45 UTC \n');
    await settle();

    expect(service.serverBuildVersion()).toBe('04-07-2026 11:45 UTC');
  });

  it('webUpdateAvailable is true when the deployed index.html references a different main bundle', async () => {
    const { service, httpTesting } = setup(['main-RUNNINGHASH.js']);
    expect(running(service)).toContain('main-RUNNINGHASH.js');

    flushServerVersion(httpTesting, '');
    flushIndexHtml(
      httpTesting,
      '<html><body><script src="/app/main-DEPLOYEDHASH.js"></script></body></html>',
    );
    await settle();

    expect(service.webUpdateAvailable()).toBe(true);
  });

  it('webUpdateAvailable is false when the deployed bundle matches the running one', async () => {
    const { service, httpTesting } = setup(['main-samehash.js']);
    // Reference EXACTLY the running set so the comparison is equal regardless of any
    // ambient scripts the test document may carry.
    const body = running(service)
      .map((name) => `<script src="${name}"></script>`)
      .join('');

    flushServerVersion(httpTesting, '');
    flushIndexHtml(httpTesting, `<html><body>${body}</body></html>`);
    await settle();

    expect(service.webUpdateAvailable()).toBe(false);
  });

  it('webUpdateAvailable stays false when the running document has no hashed main bundle (dev-server / test-DOM guard)', async () => {
    const { service, httpTesting } = setup();
    // The test DOM has no `main-*.js` script tag — the guarded short-circuit path.
    expect(running(service)).toEqual([]);
    expect(service.webBuildVersion).toBe('');

    flushServerVersion(httpTesting, '');
    flushIndexHtml(httpTesting, '<script src="main-deployed.js"></script>');
    await settle();

    expect(service.webUpdateAvailable()).toBe(false);
  });

  describe('a tab left open across a deploy', () => {
    function setVisibility(state: DocumentVisibilityState): void {
      Object.defineProperty(document, 'visibilityState', { value: state, configurable: true });
      document.dispatchEvent(new Event('visibilitychange'));
    }

    it('probes index.html again when the tab comes back into view, and reports the new bundle', async () => {
      const { service, httpTesting } = setup(['main-RUNNING.js']);
      flushServerVersion(httpTesting);
      flushIndexHtml(httpTesting, '<script src="main-RUNNING.js"></script>');
      await settle();
      expect(service.webUpdateAvailable()).toBe(false);

      // Hidden: nothing to probe for.
      setVisibility('hidden');
      httpTesting.expectNone((r) => r.url.endsWith('index.html'));

      // Back in view after the deploy.
      setVisibility('visible');
      flushIndexHtml(httpTesting, '<script src="main-DEPLOYED.js"></script>');
      await settle();
      expect(service.webUpdateAvailable()).toBe(true);
    });

    it('runs one probe at a time', async () => {
      const { service, httpTesting } = setup(['main-RUNNING.js']);
      flushServerVersion(httpTesting);

      setVisibility('visible');
      setVisibility('visible');
      // Only the start-up probe is out; the two returns to view found it still on its way.
      flushIndexHtml(httpTesting, '<script src="main-RUNNING.js"></script>');
      await settle();
      expect(service.webUpdateAvailable()).toBe(false);

      setVisibility('visible');
      flushIndexHtml(httpTesting, '<script src="main-RUNNING.js"></script>');
      await settle();
    });

    it('keeps what an earlier probe found when a later one fails', async () => {
      const { service, httpTesting } = setup(['main-RUNNING.js']);
      flushServerVersion(httpTesting);
      flushIndexHtml(httpTesting, '<script src="main-RUNNING.js"></script>');
      await settle();

      setVisibility('visible');
      httpTesting
        .expectOne((r) => r.url.endsWith('index.html'))
        .flush('gone', { status: 503, statusText: 'Service Unavailable' });
      await settle();

      expect(service.webUpdateAvailable()).toBe(false);
    });

    it('stops probing once a reload is called for', async () => {
      const { service, httpTesting } = setup(['main-RUNNING.js']);
      flushServerVersion(httpTesting);
      flushIndexHtml(httpTesting, '<script src="main-DEPLOYED.js"></script>');
      await settle();
      expect(service.webUpdateAvailable()).toBe(true);

      setVisibility('visible');
      httpTesting.expectNone((r) => r.url.endsWith('index.html'));
    });

    it('reports a missing lazy chunk, also where the bundle names are not hashed', async () => {
      const { service, httpTesting } = setup();
      flushServerVersion(httpTesting);
      flushIndexHtml(httpTesting, '<script src="main.js"></script>');
      await settle();
      expect(service.webUpdateAvailable()).toBe(false);

      service.reportMissingChunk();

      expect(service.webUpdateAvailable()).toBe(true);
    });

    it('reports a version the service worker has ready, and ignores its other version events', async () => {
      const { service, httpTesting, worker } = setup();
      flushServerVersion(httpTesting);
      flushIndexHtml(httpTesting, '');
      await settle();

      worker.versionUpdates.next({ type: 'VERSION_DETECTED', version: { hash: 'new' } });
      worker.versionUpdates.next({ type: 'NO_NEW_VERSION_DETECTED', version: { hash: 'old' } });
      expect(service.webUpdateAvailable()).toBe(false);

      worker.versionUpdates.next({
        type: 'VERSION_READY',
        currentVersion: { hash: 'old' },
        latestVersion: { hash: 'new' },
      });
      expect(service.webUpdateAvailable()).toBe(true);
    });

    it('reports a version the service worker can no longer serve', async () => {
      const { service, httpTesting, worker } = setup();
      flushServerVersion(httpTesting);
      flushIndexHtml(httpTesting, '');
      await settle();

      worker.unrecoverable.next({ type: 'UNRECOVERABLE_STATE', reason: 'hash mismatch' });

      expect(service.webUpdateAvailable()).toBe(true);
    });
  });

  describe('the navigation error handler', () => {
    function navigationError(error: unknown): NavigationError {
      return new NavigationError(1, '/messages/grid', error);
    }

    it('turns a failed chunk fetch into the update banner', async () => {
      const { service, httpTesting } = setup();
      flushServerVersion(httpTesting);
      flushIndexHtml(httpTesting, '');
      await settle();

      TestBed.runInInjectionContext(() =>
        reportMissingChunkOnNavigationError(
          navigationError(
            new TypeError('Failed to fetch dynamically imported module: https://niord/chunk-A1.js'),
          ),
        ),
      );

      expect(service.webUpdateAvailable()).toBe(true);
    });

    it('leaves every other navigation failure alone', async () => {
      const { service, httpTesting } = setup();
      flushServerVersion(httpTesting);
      flushIndexHtml(httpTesting, '');
      await settle();

      TestBed.runInInjectionContext(() =>
        reportMissingChunkOnNavigationError(navigationError(new Error('Cannot match any routes'))),
      );

      expect(service.webUpdateAvailable()).toBe(false);
    });
  });
});

describe('isChunkLoadError', () => {
  it('recognises the wording of each browser', () => {
    expect(
      isChunkLoadError(
        new TypeError('Failed to fetch dynamically imported module: https://x/a.js'),
      ),
    ).toBe(true);
    expect(
      isChunkLoadError(new TypeError('error loading dynamically imported module: https://x/a.js')),
    ).toBe(true);
    expect(isChunkLoadError(new TypeError('Importing a module script failed.'))).toBe(true);
    expect(isChunkLoadError(new Error('Http failure response for /rest/x: 500'))).toBe(false);
    expect(isChunkLoadError(undefined)).toBe(false);
  });
});
