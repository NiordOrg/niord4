import { inject, Injectable } from '@angular/core';

import { MessageDescription } from '../models/message.model';
import { LanguageService } from '../../../core/main-shell/services/language.service';
import { projectDesc } from '../../../shared/utils/project-desc';

@Injectable({ providedIn: 'root' })
export class MessageDescriptionService {
  private readonly languageService = inject(LanguageService);

  /** Returns a new display projection, never an object from the editable description list. */
  getDescription<T extends { readonly lang?: string }>(
    descs: readonly T[] | undefined,
    lang?: string,
  ): T | undefined {
    return projectDesc(
      descs,
      lang ?? this.languageService.selectedLanguage(),
      this.languageService.defaultLanguage,
    );
  }

  getTitle(descs: MessageDescription[] | undefined, lang?: string): string {
    return this.getDescription(descs, lang)?.title ?? '';
  }

  getPublication(descs: MessageDescription[] | undefined, lang?: string): string {
    return this.getDescription(descs, lang)?.publication ?? '';
  }

  getInternalPublication(descs: MessageDescription[] | undefined, lang?: string): string {
    return this.getDescription(descs, lang)?.internalPublication ?? '';
  }

  getSource(descs: MessageDescription[] | undefined, lang?: string): string {
    return this.getDescription(descs, lang)?.source ?? '';
  }
}
