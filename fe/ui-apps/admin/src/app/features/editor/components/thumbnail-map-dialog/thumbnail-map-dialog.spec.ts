import { TestBed } from '@angular/core/testing';
import { TranslocoService } from '@jsverse/transloco';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { APP_CONFIG } from '../../../../core/config/app-config.token';
import { DomainFacade } from '../../../../core/domain/facades/domain.facade';
import { NotificationService } from '../../../../core/notifications/notification.service';
import { MapShellService } from '@niord/map/map-shell.service';
import { provideTestTransloco } from '../../../../shared/testing/transloco-testing';
import { ThumbnailMapDialog } from './thumbnail-map-dialog';

interface DialogInternals {
  snapshot(): Promise<void>;
}

/** A minimal ol/Map stand-in that drives the real capture util's rendercomplete → renderSync loop. */
function fakeMap(size: [number, number] | undefined): unknown {
  const handlers: Record<string, () => void> = {};
  return {
    once: (type: string, cb: () => void) => (handlers[type] = cb),
    renderSync: () => handlers['rendercomplete']?.(),
    getSize: () => size,
    getViewport: () => document.createElement('div'),
  };
}

function setup(map: unknown) {
  const notify = { success: vi.fn(), info: vi.fn(), warn: vi.fn(), error: vi.fn() };
  TestBed.configureTestingModule({
    imports: [ThumbnailMapDialog],
    providers: [
      provideTestTransloco(),
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
      { provide: NotificationService, useValue: notify },
      // The dialog reads the domain default map view at construction (the details
      // layer + fitExtent still overrides it whenever the message has geometry).
      { provide: DomainFacade, useValue: { mapDefaults: () => ({}) } as unknown as DomainFacade },
    ],
  });
  // The dialog provides its own MapShellService — override it with a stub exposing the given map.
  TestBed.overrideComponent(ThumbnailMapDialog, {
    set: { providers: [{ provide: MapShellService, useValue: { map: () => map } }] },
  });
  TestBed.inject(TranslocoService).load('en').subscribe();

  const fixture = TestBed.createComponent(ThumbnailMapDialog);
  const cmp = fixture.componentInstance;
  const internals = cmp as unknown as DialogInternals;
  const captured: string[] = [];
  cmp.thumbnailCaptured.subscribe((url) => captured.push(url));
  // NB: deliberately no detectChanges — keeps the lazy `@if (visible())` OL map from rendering.
  return { cmp, internals, captured, notify };
}

afterEach(() => vi.restoreAllMocks());

describe('ThumbnailMapDialog', () => {
  it('captures the map, emits the data URL, and closes on a successful snapshot', async () => {
    // jsdom has no real 2D canvas — stub the context + toDataURL so the real capture util resolves.
    const ctx = { setTransform: vi.fn(), drawImage: vi.fn(), globalAlpha: 1 };
    vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue(
      ctx as unknown as CanvasRenderingContext2D,
    );
    vi.spyOn(HTMLCanvasElement.prototype, 'toDataURL').mockReturnValue(
      'data:image/png;base64,MOCK',
    );

    const { cmp, internals, captured, notify } = setup(fakeMap([256, 256]));
    cmp.visible.set(true);

    await internals.snapshot();

    expect(captured).toEqual(['data:image/png;base64,MOCK']);
    expect(cmp.visible()).toBe(false);
    expect(notify.error).not.toHaveBeenCalled();
  });

  it('does nothing when the map is not ready', async () => {
    const { internals, captured, notify } = setup(undefined);

    await internals.snapshot();

    expect(captured).toEqual([]);
    expect(notify.error).not.toHaveBeenCalled();
  });

  it('surfaces an error toast and stays open when the capture fails', async () => {
    // A map with no size makes the capture util reject.
    const { cmp, internals, captured, notify } = setup(fakeMap(undefined));
    cmp.visible.set(true);

    await internals.snapshot();

    expect(captured).toEqual([]);
    expect(notify.error).toHaveBeenCalledWith('editor.layout.thumbnailCaptureError');
    expect(cmp.visible()).toBe(true);
  });
});
