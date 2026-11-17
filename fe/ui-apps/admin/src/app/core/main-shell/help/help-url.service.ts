import { computed, inject, Injectable, Signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRouteSnapshot, NavigationEnd, Router } from '@angular/router';
import { filter, map } from 'rxjs';

import { APP_CONFIG } from '../../config/app-config.token';
import helpUrls from './help-urls.json';

/**
 * Every help section id the application uses, and the page and anchor of the user guides each one
 * opens, relative to the documentation base (`documentationUrl`). The map is a contract between this
 * code and the guides, not deployment configuration: it lives in `help-urls.json` so that the guides'
 * build can check every anchor against it, and `scripts/help-urls.test.mjs` (`npm run test:guards`)
 * fails a section id the code uses that the map lacks, and a map key no code uses.
 */
export const HELP_URLS: Readonly<Record<string, string>> = helpUrls;

interface HelpRouteData {
  readonly section: string;
}

@Injectable({ providedIn: 'root' })
export class HelpUrlService {
  private readonly router = inject(Router);
  private readonly config = inject(APP_CONFIG);

  // Track the resolved section, not the URL. If we tracked URL strings, the
  // first NavigationEnd after construction would re-emit `/` and `toSignal`'s
  // dedupe would swallow it — leaving the snapshot read frozen at its
  // pre-resolution (no-section) state until the user navigated away and back.
  private readonly currentSection = toSignal(
    this.router.events.pipe(
      filter((e): e is NavigationEnd => e instanceof NavigationEnd),
      map(() => findDeepestHelpSection(this.router.routerState.snapshot.root)),
    ),
    { initialValue: findDeepestHelpSection(this.router.routerState.snapshot.root) },
  );

  readonly routeUrl: Signal<string | undefined> = computed(() => {
    const section = this.currentSection();
    return section ? this.urlForSection(section) : undefined;
  });

  /**
   * Resolve a section to its documentation URL: its `HELP_URLS` path put after the
   * `documentationUrl` base (the build's value, which the backend's web setting of the same name
   * overrides at runtime). An unknown section, or no base, resolves to undefined and the help
   * affordance hides. A `helpUrls` block in an environment file is deprecated and never read.
   */
  urlForSection(section: string): string | undefined {
    if (!Object.hasOwn(HELP_URLS, section)) return undefined;
    const base = this.config.documentationUrl?.replace(/\/+$/, '');
    if (!base) return undefined;
    return `${base}${HELP_URLS[section]}`;
  }
}

function findDeepestHelpSection(snapshot: ActivatedRouteSnapshot): string | undefined {
  let lastHelp: string | undefined;
  let current: ActivatedRouteSnapshot | null = snapshot;
  while (current) {
    const data = current.data as { help?: HelpRouteData };
    if (data.help?.section) {
      lastHelp = data.help.section;
    }
    current = current.firstChild;
  }
  return lastHelp;
}
