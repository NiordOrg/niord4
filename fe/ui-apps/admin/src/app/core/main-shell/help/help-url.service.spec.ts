import { TestBed } from '@angular/core/testing';
import { NavigationEnd, Router, type ActivatedRouteSnapshot } from '@angular/router';
import { describe, expect, it } from 'vitest';
import { Subject } from 'rxjs';

import type { AppConfig } from '../../config/app-config.interface';
import { APP_CONFIG } from '../../config/app-config.token';
import { HELP_URLS, HelpUrlService } from './help-url.service';

const BASE = 'https://docs.example';
const EDITOR = `${BASE}/editor-manual/manual.html`;

/**
 * Builds a minimal ActivatedRouteSnapshot chain (root → ... → leaf) where
 * each entry carries the supplied `help.section` data. Only the fields the
 * service reads (`data` + `firstChild`) are populated.
 */
function buildSnapshotChain(sections: readonly (string | null)[]): ActivatedRouteSnapshot {
  const nodes = sections.map((section) => {
    const data: Record<string, unknown> = section ? { help: { section } } : {};
    return {
      data,
      firstChild: null as ActivatedRouteSnapshot | null,
    } as unknown as ActivatedRouteSnapshot;
  });
  for (let i = 0; i < nodes.length - 1; i++) {
    (nodes[i] as { firstChild: ActivatedRouteSnapshot | null }).firstChild = nodes[i + 1];
  }
  return nodes[0];
}

interface RouterStub {
  readonly events: Subject<NavigationEnd>;
  readonly routerState: { snapshot: { root: ActivatedRouteSnapshot } };
  readonly url: string;
}

function setup(
  initialSnapshot: ActivatedRouteSnapshot = buildSnapshotChain([null]),
  config: Partial<AppConfig> = { documentationUrl: BASE },
) {
  const events = new Subject<NavigationEnd>();
  const router: RouterStub = {
    events,
    routerState: { snapshot: { root: initialSnapshot } },
    url: '/',
  };

  TestBed.configureTestingModule({
    providers: [
      { provide: Router, useValue: router },
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', ...config } },
    ],
  });

  const service = TestBed.inject(HelpUrlService);
  return {
    service,
    fireNavigation: (next: ActivatedRouteSnapshot) => {
      (router.routerState.snapshot as { root: ActivatedRouteSnapshot }).root = next;
      // urlAfterRedirects content doesn't matter — the service ignores it.
      events.next(new NavigationEnd(1, '/', '/') as unknown as NavigationEnd);
    },
  };
}

describe('HelpUrlService', () => {
  it('routeUrl is undefined when no route data carries help.section', () => {
    const { service } = setup();
    expect(service.routeUrl()).toBeUndefined();
  });

  it('reads help.section from the initial snapshot if already populated at construction', () => {
    const snapshot = buildSnapshotChain([null, 'home']);
    const { service } = setup(snapshot);
    expect(service.routeUrl()).toBe(`${EDITOR}#home`);
  });

  it('picks up help.section on the first NavigationEnd after construction (the dedupe-trap fix)', () => {
    // Initial snapshot has no children yet — this mirrors the timing of a
    // hard refresh on `/`, where the router hasn't resolved its route tree
    // by the time HelpUrlService is constructed. The first NavigationEnd
    // delivers the resolved tree; the toSignal must re-emit even though
    // router.url stayed `/` the whole time.
    const { service, fireNavigation } = setup();
    expect(service.routeUrl()).toBeUndefined();
    fireNavigation(buildSnapshotChain([null, 'home']));
    expect(service.routeUrl()).toBe(`${EDITOR}#home`);
  });

  it('returns the deepest help.section when multiple route levels declare one', () => {
    // root no-section → child has `messages` → grandchild has `home`.
    // Service walks down and picks the deepest non-empty section.
    const snapshot = buildSnapshotChain([null, 'messages', 'home']);
    const { service } = setup(snapshot);
    expect(service.routeUrl()).toBe(`${EDITOR}#home`);
  });

  it('resolves to undefined when the deepest section is not in HELP_URLS', () => {
    const { service, fireNavigation } = setup();
    fireNavigation(buildSnapshotChain([null, 'unmapped']));
    expect(service.routeUrl()).toBeUndefined();
  });

  it('updates when the user navigates to a different route', () => {
    const { service, fireNavigation } = setup();
    fireNavigation(buildSnapshotChain([null, 'home']));
    expect(service.routeUrl()).toBe(`${EDITOR}#home`);
    fireNavigation(buildSnapshotChain([null, 'messages']));
    expect(service.routeUrl()).toBe(`${EDITOR}#messages`);
  });

  it('urlForSection returns the URL for an explicit section regardless of route', () => {
    const { service } = setup();
    expect(service.urlForSection('home')).toBe(`${EDITOR}#home`);
    expect(service.urlForSection('messages')).toBe(`${EDITOR}#messages`);
    expect(service.urlForSection('unmapped')).toBeUndefined();
  });

  it('puts every HELP_URLS path after the documentationUrl base', () => {
    const { service } = setup(undefined, { documentationUrl: 'https://docs.niord.org' });
    for (const [section, path] of Object.entries(HELP_URLS)) {
      expect(service.urlForSection(section)).toBe(`https://docs.niord.org${path}`);
    }
  });

  it('trims trailing slashes off the base', () => {
    const { service } = setup(undefined, { documentationUrl: 'https://docs.niord.org//' });
    expect(service.urlForSection('home')).toBe(
      'https://docs.niord.org/editor-manual/manual.html#home',
    );
  });

  it('resolves nothing when no documentationUrl base is configured', () => {
    const { service } = setup(buildSnapshotChain([null, 'home']), {});
    expect(service.urlForSection('home')).toBeUndefined();
    expect(service.routeUrl()).toBeUndefined();
  });

  it('resolves no inherited object property as a section', () => {
    const { service } = setup();
    expect(service.urlForSection('constructor')).toBeUndefined();
    expect(service.urlForSection('toString')).toBeUndefined();
  });

  it('points the renamed and new sections at the anchors the guides carry', () => {
    const { service } = setup();
    expect(service.urlForSection('compare-messages')).toBe(`${EDITOR}#compare-messages`);
    expect(service.urlForSection('editor-start')).toBe(`${EDITOR}#blank-editor-page`);
    expect(service.urlForSection('admin-manual')).toBe(
      `${BASE}/admin-manual/manual.html#admin-page`,
    );
    expect(service.urlForSection('publication-categories')).toBe(
      `${BASE}/admin-manual/manual.html#publication-categories`,
    );
    expect(service.urlForSection('sysadmin-manual')).toBe(
      `${BASE}/sysadmin-manual/manual.html#sysadmin-page`,
    );
    expect(service.urlForSection('firing-exercise-schedule')).toBe(
      `${EDITOR}#firing-exercise-schedule`,
    );
  });

  it('has no section for SECOM and none under the old comparison key', () => {
    const { service } = setup();
    expect(service.urlForSection('secom')).toBeUndefined();
    expect(service.urlForSection('compare-action')).toBeUndefined();
  });

  it('ignores the deprecated helpUrls an environment file may still carry', () => {
    const { service } = setup(buildSnapshotChain([null, 'home']), {
      documentationUrl: BASE,
      helpUrls: {
        home: 'https://other.example/home.html',
        'compare-action': '/editor-manual/manual.html#compare-action',
      },
    });
    expect(service.routeUrl()).toBe(`${EDITOR}#home`);
    expect(service.urlForSection('home')).toBe(`${EDITOR}#home`);
    expect(service.urlForSection('compare-action')).toBeUndefined();
  });
});
