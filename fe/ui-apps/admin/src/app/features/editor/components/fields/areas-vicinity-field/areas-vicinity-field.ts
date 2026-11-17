import { Component, computed, inject, input } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';
import type { FieldTree } from '@angular/forms/signals';

import { DomainFacade } from '../../../../../core/domain/facades/domain.facade';
import { LanguageService } from '../../../../../core/main-shell/services/language.service';
import { Icon } from '../../../../../shared/icons/icon';
import {
  LocalizedInputs,
  type LocalizedValueInput,
} from '../../../../../shared/forms/localized-inputs/localized-inputs';
import { AreasField } from '../../../../messages/components/areas-field/areas-field';
import type { MessageArea } from '../../../../messages/models/message.model';
import type { AreaRef } from '../../../../messages/models/messages-filter-params.model';
import { EditorFacade } from '../../../services/editor-facade';
import { EditorFieldsService } from '../../../services/editor-fields.service';
import { EditorFieldRow } from '../../editor-field-row/editor-field-row';

/** Prune a picker `AreaRef` to the `MessageArea` shape the message carries (drops search-only fields). */
function toMessageArea(area: AreaRef): MessageArea {
  return {
    id: area.id,
    active: area.active,
    parent: area.parent ? toMessageArea(area.parent) : undefined,
    descs: area.descs?.map((desc) => ({ lang: desc.lang, name: desc.name })),
  };
}

/**
 * The always-shown `areas` field row: the message's areas + the per-language vicinity text.
 *
 * The AREAS picker (reused `AreasField`, domain-scoped, multiple) is structural — its selection
 * flows through `facade.setAreas` (immediate adjust, since the area drives the auto-generated
 * title). The per-language
 * **vicinity** inputs are host-form-backed: {@link vicinitiesField} (`form.vicinities`) is the ONE
 * write path, so a keystroke updates the form (the host fires the debounced adjust) and blur flushes
 * it via `facade.flushAdjust`. Blank-input **copy-on-focus** copies
 * another language's vicinity through the same form field. **Copy locations** (enabled with any area)
 * and **Compute from locations** (enabled once a part defines geometry) route to the facade's
 * `copyAreaLocations` / `computeAreas` actions.
 */
@Component({
  selector: 'app-areas-vicinity-field',
  imports: [TranslocoPipe, Icon, AreasField, LocalizedInputs, EditorFieldRow],
  template: `
    <app-editor-field-row
      fieldId="areas"
      label="msg.field.areas"
      [edited]="fields.edited('areas')"
      [empty]="!areaSummary() && !primaryVicinity()"
      emptyLabel="editor.field.areas.empty"
    >
      <span fieldViewer>
        {{ areaSummary() }}
        @if (primaryVicinity()) {
          <span class="text-fg-subtle">· {{ primaryVicinity() }}</span>
        }
      </span>

      <div fieldEditor class="flex flex-col gap-3">
        <app-areas-field
          [value]="areaRefs()"
          [domainId]="domainId()"
          [disabled]="!facade.editableNow()"
          placeholder="editor.field.areas.pickerPlaceholder"
          (valueChange)="onAreasChange($event)"
        />

        <div class="flex flex-wrap gap-2">
          <button
            type="button"
            class="inline-flex items-center gap-1.5 rounded border border-surface-300 px-2.5 py-1 text-xs disabled:opacity-50 dark:border-surface-600"
            [disabled]="!facade.editableNow() || !hasAreas()"
            (click)="facade.copyAreaLocations()"
          >
            <app-icon name="lucideCopy" />
            <span>{{ 'editor.areas.copyLocations' | transloco }}</span>
          </button>
          <button
            type="button"
            class="inline-flex items-center gap-1.5 rounded border border-surface-300 px-2.5 py-1 text-xs disabled:opacity-50 dark:border-surface-600"
            [disabled]="!facade.editableNow() || !definesGeometry()"
            (click)="facade.computeAreas()"
          >
            <app-icon name="lucideMapPin" />
            <span>{{ 'editor.areas.computeFromLocations' | transloco }}</span>
          </button>
        </div>

        <app-localized-inputs
          [value]="vicinitiesControl().value()"
          [disabled]="!facade.editableNow()"
          placeholderKey="editor.field.areas.vicinityPlaceholder"
          (valueInput)="onVicinityInput($event)"
          (inputFocus)="onVicinityFocus($event)"
          (inputBlur)="facade.flushAdjust()"
        />
      </div>
    </app-editor-field-row>
  `,
  host: { class: 'contents' },
})
export class AreasVicinityField {
  /** The `vicinities` (per-language) slice of the host's form model. */
  readonly vicinitiesField = input.required<FieldTree<Record<string, string>>>();

  protected readonly facade = inject(EditorFacade);
  protected readonly fields = inject(EditorFieldsService);
  private readonly language = inject(LanguageService);
  private readonly domain = inject(DomainFacade);

  protected readonly vicinitiesControl = computed(() => this.vicinitiesField()());

  /** The message's areas, widened to the picker's `AreaRef` shape for the `[value]` binding. */
  protected readonly areaRefs = computed<readonly AreaRef[]>(
    () => this.facade.message()?.areas ?? [],
  );

  /** Session domain scopes the area search. */
  protected readonly domainId = computed<string | null>(
    () => this.domain.sessionDomain()?.domainId ?? null,
  );

  // The collapsed summaries split by content kind: area NAMES are catalog entity names, shown in the
  // current UI language (the app-wide convention); the vicinity text is message-authored content, so
  // it reads the PRIMARY desc (`descs[0]`).
  protected readonly areaSummary = computed<string>(() => {
    const lang = this.language.selectedLanguage();
    return (this.facade.message()?.areas ?? [])
      .map((area) => {
        const descs = area.descs ?? [];
        return (descs.find((entry) => entry.lang === lang) ?? descs[0])?.name ?? '';
      })
      .filter((name) => name.length > 0)
      .join(', ');
  });

  protected readonly primaryVicinity = computed<string>(
    () => this.facade.message()?.descs?.[0]?.vicinity ?? '',
  );

  protected readonly hasAreas = computed<boolean>(
    () => (this.facade.message()?.areas?.length ?? 0) > 0,
  );

  /** Whether any part defines geometry — gates the Compute-from-locations action. */
  protected readonly definesGeometry = computed<boolean>(() =>
    (this.facade.message()?.parts ?? []).some((part) => (part.geometry?.features?.length ?? 0) > 0),
  );

  protected onAreasChange(areas: readonly AreaRef[]): void {
    this.facade.setAreas(areas.map(toMessageArea));
  }

  protected onVicinityInput({ lang, value }: LocalizedValueInput): void {
    this.vicinitiesControl().value.update((vicinities) => ({ ...vicinities, [lang]: value }));
  }

  /** Copy-on-focus: a blank vicinity input adopts another language's non-blank value. */
  protected onVicinityFocus(lang: string): void {
    const vicinities = this.vicinitiesControl().value();
    if (vicinities[lang]) {
      return;
    }
    const donor = Object.entries(vicinities).find(([key, text]) => key !== lang && !!text);
    if (donor) {
      this.vicinitiesControl().value.update((current) => ({ ...current, [lang]: donor[1] }));
    }
  }
}
