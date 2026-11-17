import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { PrintSettingsStorageService } from './print-settings-storage.service';

function setup(): PrintSettingsStorageService {
  TestBed.configureTestingModule({});
  return TestBed.inject(PrintSettingsStorageService);
}

describe('PrintSettingsStorageService', () => {
  beforeEach(() => window.localStorage.clear());
  afterEach(() => window.localStorage.clear());

  it('load() returns defaults when no key is stored', () => {
    expect(setup().load()).toEqual({
      pageSize: 'A4',
      pageOrientation: 'portrait',
      mapThumbnails: false,
    });
  });

  it('load() returns defaults on parse error', () => {
    window.localStorage.setItem('niord.printSettings', 'not-json');
    expect(setup().load().pageSize).toBe('A4');
  });

  it('load() validates the parsed values against the known enums', () => {
    window.localStorage.setItem(
      'niord.printSettings',
      JSON.stringify({ pageSize: 'banana', pageOrientation: 'sideways', mapThumbnails: 'yes' }),
    );
    expect(setup().load()).toEqual({
      pageSize: 'A4',
      pageOrientation: 'portrait',
      mapThumbnails: false,
    });
  });

  it('load() round-trips valid values', () => {
    window.localStorage.setItem(
      'niord.printSettings',
      JSON.stringify({ pageSize: 'B5', pageOrientation: 'landscape', mapThumbnails: true }),
    );
    expect(setup().load()).toEqual({
      pageSize: 'B5',
      pageOrientation: 'landscape',
      mapThumbnails: true,
    });
  });

  it('save() persists only the subset (no report, no reportParams)', () => {
    setup().save({
      pageSize: 'A3',
      pageOrientation: 'landscape',
      mapThumbnails: true,
      report: 'should-not-be-persisted',
    });
    const raw = window.localStorage.getItem('niord.printSettings');
    expect(raw).not.toBeNull();
    const parsed = JSON.parse(raw as string) as Record<string, unknown>;
    expect(parsed).toEqual({
      pageSize: 'A3',
      pageOrientation: 'landscape',
      mapThumbnails: true,
    });
    expect(parsed['report']).toBeUndefined();
  });
});
