import { Component, computed, inject, input } from '@angular/core';
import { translateSignal } from '@jsverse/transloco';

import { MessageArea, NO_AREA_SENTINEL_ID } from '../../models/message.model';
import { LanguageService } from '../../../../core/main-shell/services/language.service';

const MAX_AREA_LEVELS = 2;

@Component({
  selector: 'app-message-areas',
  host: { class: 'contents' },
  templateUrl: './message-areas.html',
})
export class MessageAreas {
  private readonly languageService = inject(LanguageService);
  private readonly generalLabel = translateSignal('messages.area.general');

  readonly areas = input<MessageArea[]>();

  readonly formattedAreas = computed(() => {
    const areaList = this.areas();
    if (!areaList || areaList.length === 0) {
      return [];
    }
    const lang = this.languageService.selectedLanguage();
    const generalLabel = this.generalLabel();
    return areaList.map((area) => this.buildAreaPath(area, lang, generalLabel));
  });

  private buildAreaPath(area: MessageArea, lang: string, generalLabel: string): string[] {
    const parts: string[] = [];
    let current: MessageArea | undefined = area;

    while (current) {
      const name = this.getAreaName(current, lang, generalLabel);
      if (name) {
        parts.unshift(name);
      }
      current = current.parent;
    }
    // Keep only the broadest levels (root + its child); deeper levels are dropped.
    return parts.slice(0, MAX_AREA_LEVELS);
  }

  private getAreaName(area: MessageArea, lang: string, generalLabel: string): string {
    if (area.id === NO_AREA_SENTINEL_ID) {
      return generalLabel;
    }
    if (!area.descs || area.descs.length === 0) {
      return '';
    }
    const desc = area.descs.find((d) => d.lang === lang) ?? area.descs[0];
    return desc?.name ?? '';
  }
}
