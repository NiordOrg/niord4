import { computed, inject, Injectable, Signal } from '@angular/core';
import type { MenuItem } from '../../../../shared/models/menu-item.model';

import { LanguageService } from '../../services/language.service';

// Render the flag image via the menu item's icon slot. The menu only emits the
// `<span class="…" [ngStyle]="iconStyle">` element when `item.icon` is truthy, so the
// sizing/positioning utilities are passed through `icon`, and the per-language flag
// image goes through `iconStyle` (inline background-image). The label stays plain text.
// A language the build ships no flag for gets no icon: its label already names it.
//
// Two sizes: the root flag is the only visual signifier on the desktop bar (the label
// is hidden), so it's slightly bigger; submenu rows show flag + text and use the
// smaller size for visual balance with the label.
const FLAG_BASE = 'inline-block bg-no-repeat bg-center bg-contain';
const FLAG_ICON_ROOT_CLASS = `${FLAG_BASE} w-6 h-4`;
const FLAG_ICON_SUBMENU_CLASS = `${FLAG_BASE} w-5 h-3.5`;

@Injectable({ providedIn: 'root' })
export class LanguageMenuService {
  private readonly languageService = inject(LanguageService);

  readonly menuItem: Signal<MenuItem> = computed(() => {
    const selectedValue = this.languageService.selectedLanguage();
    const languages = this.languageService.uiLanguages;
    const selected = languages.find((lang) => lang.value === selectedValue);
    return {
      label: selected?.label ?? selectedValue,
      icon: selected?.flagPath ? FLAG_ICON_ROOT_CLASS : undefined,
      iconStyle: selected?.flagPath ? flagBackgroundStyle(selected.flagPath) : null,
      items: languages.map((lang) => ({
        label: lang.label,
        icon: lang.flagPath ? FLAG_ICON_SUBMENU_CLASS : undefined,
        iconStyle: lang.flagPath ? flagBackgroundStyle(lang.flagPath) : null,
        styleClass: lang.value === selectedValue ? 'font-semibold' : undefined,
        command: () => this.languageService.selectLanguage(lang.value),
      })),
    };
  });
}

function flagBackgroundStyle(flagPath: string): Record<string, string> {
  return { 'background-image': `url('${flagPath}')` };
}
