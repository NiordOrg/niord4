import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';

import { provideTestTransloco } from '../../../../shared/testing/transloco-testing';
import type { Domain } from '../../../domain/models/domain.model';
import { AuthFacade } from '../../../auth/facades/auth.facade';
import { DomainFacade } from '../../../domain/facades/domain.facade';
import { DomainMenuService } from './domain-menu.service';

function setup(color: string | undefined) {
  const domain: Domain = { domainId: 'd1', name: 'Domain One', color };
  const domainFacade = {
    availableDomains: signal<readonly Domain[]>([domain]),
    sessionDomain: signal<Domain | undefined>(domain),
    selectSessionDomain: () => undefined,
  } as unknown as DomainFacade;
  const authFacade = {
    isAuthenticated: signal(false),
    hasRolesFor: () => false,
  } as unknown as AuthFacade;

  // Some tests probe several colors in one `it`; reset first so each `setup()` can
  // reconfigure the (otherwise already-instantiated) shared TestBed module.
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({
    providers: [
      provideTestTransloco(),
      { provide: DomainFacade, useValue: domainFacade },
      { provide: AuthFacade, useValue: authFacade },
    ],
  });
  return TestBed.inject(DomainMenuService);
}

/** The trigger glyph color the YIQ helper resolves for a given domain color. */
function glyphColor(color: string): string | undefined {
  const service = setup(color);
  const style = service.menuItem()?.iconStyle;
  return style ? (style as Record<string, string>)['color'] : undefined;
}

describe('DomainMenuService — contrastingTextColor (YIQ) branch', () => {
  it('picks BLACK on a light background (luma > 186)', () => {
    expect(glyphColor('#ffffff')).toBe('black');
    expect(glyphColor('#ffff00')).toBe('black'); // yellow, luma ~226
  });

  it('picks WHITE on a dark background (luma <= 186)', () => {
    expect(glyphColor('#000000')).toBe('white');
    expect(glyphColor('#000080')).toBe('white'); // navy, luma ~15
  });

  it('expands 3-digit hex before deciding', () => {
    expect(glyphColor('#fff')).toBe('black');
    expect(glyphColor('#000')).toBe('white');
  });

  it('honours the luma > 186 threshold exactly', () => {
    // #bbbbbb -> 187 (> 186 -> black); #bababa -> 186 (not > 186 -> white).
    expect(glyphColor('#bbbbbb')).toBe('black');
    expect(glyphColor('#bababa')).toBe('white');
  });

  it('falls back to WHITE on unparseable input (matches the default white glyph)', () => {
    expect(glyphColor('red')).toBe('white');
    expect(glyphColor('rgb(0,0,0)')).toBe('white');
  });

  it('emits no icon style at all when the domain has no color', () => {
    const service = setup(undefined);
    const item = service.menuItem();
    expect(item?.iconStyle).toBeNull();
    expect(item?.iconClass).toBeUndefined();
  });
});
