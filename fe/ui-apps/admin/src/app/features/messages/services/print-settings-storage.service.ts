import { isPlatformBrowser } from '@angular/common';
import { Injectable, PLATFORM_ID, inject } from '@angular/core';

import {
  DEFAULT_PRINT_SETTINGS,
  PAPER_SIZES,
  type PageOrientation,
  type PaperSize,
  type PrintSettings,
} from '../../../shared/models/print/print-settings.model';

const LS_KEY = 'niord.printSettings';
const KNOWN_PAPER_SIZES = new Set<PaperSize>(PAPER_SIZES);
const KNOWN_ORIENTATIONS: ReadonlySet<PageOrientation> = new Set<PageOrientation>([
  'portrait',
  'landscape',
]);

/**
 * Persists the subset of PrintSettings that the user is allowed to remember
 * across sessions — `pageSize`, `pageOrientation`, `mapThumbnails`. `report`
 * and `reportParams` are deliberately NOT persisted (they default per-session).
 */
@Injectable({ providedIn: 'root' })
export class PrintSettingsStorageService {
  private readonly platformId = inject(PLATFORM_ID);

  load(): PrintSettings {
    if (!isPlatformBrowser(this.platformId)) {
      return { ...DEFAULT_PRINT_SETTINGS };
    }
    try {
      const raw = window.localStorage.getItem(LS_KEY);
      if (!raw) return { ...DEFAULT_PRINT_SETTINGS };
      const parsed = JSON.parse(raw) as Partial<PrintSettings> | null;
      if (!parsed || typeof parsed !== 'object') return { ...DEFAULT_PRINT_SETTINGS };
      return {
        ...DEFAULT_PRINT_SETTINGS,
        pageSize: this.pickPaperSize(parsed.pageSize),
        pageOrientation: this.pickOrientation(parsed.pageOrientation),
        mapThumbnails: typeof parsed.mapThumbnails === 'boolean' ? parsed.mapThumbnails : false,
      };
    } catch {
      return { ...DEFAULT_PRINT_SETTINGS };
    }
  }

  save(settings: PrintSettings): void {
    if (!isPlatformBrowser(this.platformId)) return;
    const subset = {
      pageSize: settings.pageSize,
      pageOrientation: settings.pageOrientation,
      mapThumbnails: settings.mapThumbnails,
    };
    try {
      window.localStorage.setItem(LS_KEY, JSON.stringify(subset));
    } catch {
      // ignore quota / storage errors
    }
  }

  private pickPaperSize(value: unknown): PaperSize {
    return typeof value === 'string' && KNOWN_PAPER_SIZES.has(value as PaperSize)
      ? (value as PaperSize)
      : DEFAULT_PRINT_SETTINGS.pageSize;
  }

  private pickOrientation(value: unknown): PageOrientation {
    return typeof value === 'string' && KNOWN_ORIENTATIONS.has(value as PageOrientation)
      ? (value as PageOrientation)
      : DEFAULT_PRINT_SETTINGS.pageOrientation;
  }
}
