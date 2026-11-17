import { Injectable, inject, signal } from '@angular/core';

import { AdminSectionDescriptor } from '../section-descriptors';
import { SettingsApiService } from './api/settings-api.service';

/**
 * Resolves the runtime visibility of `settingGate`-bearing admin sections. The
 * shell calls `start(sections)` on mount: when the area has any gated section, the
 * anonymous web settings (`/settings/web`) are fetched ONCE and every distinct gate
 * key is read from that map; a gated section becomes visible only when its key
 * resolves truthy (boolean `true` or string `'true'`). Until then — and on a fetch
 * error or a missing key — the section stays hidden.
 *
 * Every gate key is in that map: a `web: true` setting (`adminIntegrationPageEnabled`)
 * or a value the backend computes (`secomEnabled`, from the running
 * `niord.secom.enabled`), so a node without SECOM answers `false`, with no 404 and
 * no stale row. The fetch is made per mount, so an edit of an editable gate shows
 * the next time the area opens.
 *
 * Ungated sections (no `settingGate`) are always visible. The gate hides only
 * the rail/menu surface; the route always resolves.
 *
 * Provide at the area-shell level (like `BatchStatusService`) so the overview
 * page, rendered in the shell's router-outlet, can inject the same instance.
 */
@Injectable()
export class SectionGatesService {
  private readonly settingsApi = inject(SettingsApiService);

  /** Map of gate key → resolved visibility. Absent key = not yet resolved (hidden). */
  private readonly gateState = signal<ReadonlyMap<string, boolean>>(new Map());

  private started = false;

  /**
   * Collects the distinct `settingGate` keys across the supplied sections and
   * resolves them all from one web-settings fetch. Idempotent: a second call
   * (re-mount) is a no-op.
   */
  start(sections: readonly AdminSectionDescriptor[]): void {
    if (this.started) {
      return;
    }
    this.started = true;
    const keys = [
      ...new Set(sections.map((s) => s.settingGate).filter((k): k is string => k !== undefined)),
    ];
    if (keys.length === 0) {
      return;
    }
    this.settingsApi.getWebSettings().subscribe({
      next: (settings) => this.resolve(keys, (key) => isTruthy(settings?.[key])),
      error: () => this.resolve(keys, () => false),
    });
  }

  /**
   * Whether a section's rail entry / overview card should render. Ungated sections
   * are always visible; a gated section is visible only once its key resolved
   * truthy (hidden before resolution, on error, or when the key is missing).
   */
  isVisible(section: AdminSectionDescriptor): boolean {
    if (!section.settingGate) {
      return true;
    }
    return this.gateState().get(section.settingGate) === true;
  }

  private resolve(keys: readonly string[], visible: (key: string) => boolean): void {
    const next = new Map(this.gateState());
    for (const key of keys) {
      next.set(key, visible(key));
    }
    this.gateState.set(next);
  }
}

/** A gate value is on when it is boolean `true` or the string `'true'`. */
function isTruthy(value: unknown): boolean {
  return value === true || value === 'true';
}
