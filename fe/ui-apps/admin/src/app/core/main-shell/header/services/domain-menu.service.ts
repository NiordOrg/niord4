import { computed, inject, Injectable, Signal } from '@angular/core';
import { translateSignal } from '@jsverse/transloco';
import type { MenuItem } from '../../../../shared/models/menu-item.model';

import { AuthFacade } from '../../../auth/facades/auth.facade';
import { DomainFacade } from '../../../domain/facades/domain.facade';

@Injectable({ providedIn: 'root' })
export class DomainMenuService {
  private readonly domainFacade = inject(DomainFacade);
  private readonly authFacade = inject(AuthFacade);
  private readonly noDomainLabel = translateSignal('core.domain.header.noDomain');

  readonly menuItem: Signal<MenuItem | null> = computed(() => {
    const available = this.domainFacade.availableDomains();
    if (available.length === 0) {
      return null;
    }
    const selected = this.domainFacade.sessionDomain();
    const selectedId = selected?.domainId;
    const isAuthenticated = this.authFacade.isAuthenticated();
    const triggerColor = selected?.color;

    return {
      label: selected?.name ?? selected?.domainId ?? this.noDomainLabel(),
      icon: 'lucideGlobe',
      // When the active domain has a color, fill the icon's background disc with it
      // (a color badge wrapped around the icon). The glyph itself flips between white
      // and black based on the domain color's perceived brightness so it stays legible
      // on either.
      iconClass: triggerColor ? 'rounded-full p-1' : undefined,
      iconStyle: triggerColor
        ? {
            'background-color': triggerColor,
            color: contrastingTextColor(triggerColor),
          }
        : null,
      items: available.map((domain) => {
        // `hasRolesFor(domainId)` → pencil if the user has any role for this domain
        // (editor/admin/sysadmin), eye otherwise. Anonymous users have no roles anywhere,
        // so every item reads as view-only.
        const isEditable = isAuthenticated && this.authFacade.hasRolesFor(domain.domainId);
        const isSelected = domain.domainId === selectedId;
        return {
          label: domain.name ?? domain.domainId,
          icon: isEditable ? 'lucidePencil' : 'lucideEye',
          iconClass: 'text-surface-400',
          // Selected row gets bold + a tinted background so it stands out against
          // the otherwise uniform list. styleClass goes on the <li>, the highlight
          // covers the whole row width.
          styleClass: isSelected ? 'font-semibold bg-surface-100 dark:bg-surface-800' : undefined,
          command: () => this.domainFacade.selectSessionDomain(domain.domainId),
        };
      }),
    };
  });
}

/**
 * Picks black or white depending on which gives better contrast against `bg`.
 * Uses the YIQ luma approximation — cheap, well-known threshold for "is this
 * background light or dark" decisions. Falls back to white on unparseable input
 * since the menubar's default icon color is already white.
 */
function contrastingTextColor(bg: string): 'white' | 'black' {
  const hex = /^#([0-9a-f]{3}|[0-9a-f]{6})$/i.exec(bg.trim());
  if (!hex) return 'white';
  let r: number;
  let g: number;
  let b: number;
  if (hex[1].length === 3) {
    r = parseInt(hex[1][0] + hex[1][0], 16);
    g = parseInt(hex[1][1] + hex[1][1], 16);
    b = parseInt(hex[1][2] + hex[1][2], 16);
  } else {
    r = parseInt(hex[1].slice(0, 2), 16);
    g = parseInt(hex[1].slice(2, 4), 16);
    b = parseInt(hex[1].slice(4, 6), 16);
  }
  const luma = (r * 299 + g * 587 + b * 114) / 1000;
  return luma > 186 ? 'black' : 'white';
}
