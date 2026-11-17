import { computed, inject, Injectable, signal } from '@angular/core';
import { LanguageService } from '../../../core/main-shell/services/language.service';
import { projectDesc } from '../../../shared/utils/project-desc';

export interface ModelText {
  readonly value: string;
  readonly lang?: string;
}

/** View-scoped model text selection, independent of the UI language and editable records. */
@Injectable()
export class ModelLanguageSelection {
  private readonly language = inject(LanguageService);
  private readonly chosen = signal<string | undefined>(undefined);

  readonly languages = computed(() => this.language.modelLanguages);
  readonly value = computed(() => {
    const codes = this.languages().map((language) => language.value);
    const chosen = this.chosen();
    if (chosen && codes.includes(chosen)) return chosen;
    const ui = this.language.selectedLanguage();
    if (codes.includes(ui)) return ui;
    return codes.includes(this.language.defaultLanguage) ? this.language.defaultLanguage : codes[0];
  });

  select(value: unknown): void {
    if (
      typeof value === 'string' &&
      this.languages().some((language) => language.value === value)
    ) {
      this.chosen.set(value);
    }
  }

  /** Resolve one field so its language follows the actual text, including fallback. */
  text<T extends { readonly lang?: string }>(
    descs: readonly T[] | undefined,
    field: keyof T,
  ): ModelText {
    const candidates = descs?.filter((desc) => {
      const value = desc[field];
      return typeof value === 'string' && value.trim() !== '';
    });
    const desc = projectDesc(candidates, this.value(), this.language.defaultLanguage);
    const value = desc?.[field];
    return { value: typeof value === 'string' ? value : '', lang: desc?.lang };
  }
}
