import { Component, computed, inject, input } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';
import type { FieldTree } from '@angular/forms/signals';

import { LanguageService } from '../../../../../core/main-shell/services/language.service';
import { Icon } from '../../../../../shared/icons/icon';
import {
  LocalizedInputs,
  type LocalizedValueInput,
} from '../../../../../shared/forms/localized-inputs/localized-inputs';
import { EditorFacade } from '../../../services/editor-facade';
import { EditorFieldsService } from '../../../services/editor-fields.service';
import { MessageDescService } from '../../../services/message-desc.service';
import { EditorFieldRow } from '../../editor-field-row/editor-field-row';
import { LanguageFlag } from '../../../../../shared/components/language-flag/language-flag';

/**
 * The `title` field row: the per-language message title, optionally auto-generated.
 *
 * Binds two slices of the ONE form model — {@link titlesField} (`form.titles`,
 * `FieldTree<Record<lang, string>>`) and {@link autoTitleField} (`form.autoTitle`).
 * The per-language {@link LocalizedInputs} write back into the titles record signal (controlled
 * component), so edits flow through the host's single sync point; `autoTitle` locks the inputs and
 * the facade's `adjust` copy-back fills the server-generated titles while locked. The viewer shows
 * the CHOSEN content language's title (falling back to the first desc, with a foreign-language flag,
 * when that language has none) + a lock glyph when auto-generated.
 * While locked the editor inputs render the copied-back server titles (from `message.descs`, since
 * the form model only re-populates on load, never on adjust) rather than the stale form values.
 */
@Component({
  selector: 'app-title-field',
  imports: [LanguageFlag, TranslocoPipe, Icon, LocalizedInputs, EditorFieldRow],
  template: `
    <app-editor-field-row
      fieldId="title"
      label="msg.field.title"
      [edited]="fields.edited('title')"
      [empty]="!primaryTitle()"
      emptyLabel="editor.field.title.empty"
    >
      <span fieldViewer class="inline-flex items-center gap-1.5">
        <span>{{ primaryTitle() }}</span>
        @if (foreignLanguage(); as foreign) {
          <app-language-flag
            class="h-3.5 w-5 rounded-xs opacity-60"
            [lang]="foreign.lang"
            [flagPath]="foreign.flagPath"
          />
        }
        @if (autoControl().value()) {
          <app-icon name="lucideLock" class="text-fg-subtle" />
        }
      </span>

      <div fieldEditor class="flex flex-col gap-2">
        <label class="inline-flex items-center gap-2 text-sm">
          <input
            type="checkbox"
            class="h-4 w-4"
            [checked]="autoControl().value()"
            [disabled]="!facade.editableNow()"
            (change)="onAutoToggle($event)"
          />
          <span>{{ 'editor.auto_generate' | transloco }}</span>
        </label>
        <app-localized-inputs
          [value]="displayTitles()"
          [disabled]="!facade.editableNow() || autoControl().value()"
          placeholderKey="editor.field.title.placeholder"
          (valueInput)="onTitleInput($event)"
        />
      </div>
    </app-editor-field-row>
  `,
  host: { class: 'contents' },
})
export class TitleField {
  /** The `titles` (per-language) slice of the host's form model. */
  readonly titlesField = input.required<FieldTree<Record<string, string>>>();
  /** The `autoTitle` boolean slice of the host's form model. */
  readonly autoTitleField = input.required<FieldTree<boolean>>();

  protected readonly facade = inject(EditorFacade);
  protected readonly fields = inject(EditorFieldsService);
  private readonly language = inject(LanguageService);
  private readonly desc = inject(MessageDescService);

  protected readonly titlesControl = computed(() => this.titlesField()());
  protected readonly autoControl = computed(() => this.autoTitleField()());

  /** The desc for the chosen content language, falling back to the first desc when it has none. */
  private readonly primaryDesc = computed(() =>
    this.desc.descForLangOrDefault(this.facade.message()?.descs, this.language.selectedLanguage()),
  );

  /** The chosen-language desc title for the collapsed viewer. */
  protected readonly primaryTitle = computed<string>(() => this.primaryDesc()?.title ?? '');

  /** The viewer desc's language when it differs from the current UI language (a fallback), else undefined. */
  protected readonly foreignLanguage = computed(() => {
    const desc = this.primaryDesc();
    const current = this.language.selectedLanguage();
    if (!desc?.lang || desc.lang === current) {
      return undefined;
    }
    const option = this.language.modelLanguages.find((entry) => entry.value === desc.lang);
    return { lang: desc.lang, flagPath: option?.flagPath };
  });

  /**
   * Per-language values fed to the inputs. While `autoTitle` locks the group the form model is NOT
   * refreshed by an adjust (it only re-populates on load), so read the copied-back server titles
   * straight off `message.descs`; otherwise the form model's titles are authoritative.
   */
  protected readonly displayTitles = computed<Record<string, string>>(() => {
    if (!this.autoControl().value()) {
      return this.titlesControl().value();
    }
    const out: Record<string, string> = {};
    for (const desc of this.facade.message()?.descs ?? []) {
      if (desc.lang) {
        out[desc.lang] = desc.title ?? '';
      }
    }
    return out;
  });

  protected onAutoToggle(event: Event): void {
    const checked = (event.target as HTMLInputElement).checked;
    this.autoControl().value.set(checked);
    if (!checked) {
      // Unlocking: seed the now-editable inputs from the latest (possibly adjust-generated) server
      // titles so editing continues from what the viewer/preview shows, not a stale load-time value
      // (the inputs must reflect `desc.title`, which the adjust already refreshed).
      const seeded: Record<string, string> = { ...this.titlesControl().value() };
      for (const desc of this.facade.message()?.descs ?? []) {
        if (desc.lang) {
          seeded[desc.lang] = desc.title ?? '';
        }
      }
      this.titlesControl().value.set(seeded);
    }
  }

  protected onTitleInput({ lang, value }: LocalizedValueInput): void {
    this.titlesControl().value.update((titles) => ({ ...titles, [lang]: value }));
  }
}
