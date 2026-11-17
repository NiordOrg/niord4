import { Component, computed, inject, input } from '@angular/core';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';

import { LanguageService } from '../../../../core/main-shell/services/language.service';
import type { MessagePart } from '../../../messages/models/message.model';
import { EditorFacade } from '../../services/editor-facade';
import { EditorFieldsService } from '../../services/editor-fields.service';
import { partTypeLabelKey } from '../../../../shared/utils/part-type-label-key';

/** A part type + the `editorFields` gate key that governs its selector's visibility. */
interface PartTypeOption {
  readonly type: string;
  readonly gate: string;
}

/** The part-type set + per-type visibility gate. */
const PART_TYPE_OPTIONS: readonly PartTypeOption[] = [
  { type: 'DETAILS', gate: 'part_types' },
  { type: 'TIME', gate: 'part_types' },
  { type: 'POSITIONS', gate: 'part_types' },
  { type: 'PROHIBITION', gate: 'prohibition' },
  { type: 'SIGNALS', gate: 'signals' },
  { type: 'NOTE', gate: 'part_types' },
];

/**
 * The per-part type switcher: a segmented control that retypes a message part. Each button renders
 * only when {@link EditorFieldsService.showPartType} passes for its gate (or the part is already
 * that type), and the active type is highlighted.
 *
 * Drives `facade.setPartType(partIndex, type)` (which re-derives server fields via `commitParts` →
 * adjust). Inputs are {@link partIndex} and the live {@link part} (so visibility/highlight track
 * the working copy).
 */
@Component({
  selector: 'app-part-type-switcher',
  imports: [TranslocoPipe],
  template: `
    <div
      class="flex flex-wrap gap-1"
      role="group"
      [attr.aria-label]="'editor.part.type' | transloco"
    >
      @for (option of visibleOptions(); track option.type) {
        <button
          type="button"
          class="rounded border px-2.5 py-1 text-xs disabled:opacity-40"
          [class.border-primary]="part().type === option.type"
          [class.bg-primary]="part().type === option.type"
          [class.text-fg-on-primary]="part().type === option.type"
          [class.font-semibold]="part().type === option.type"
          [class.border-surface-300]="part().type !== option.type"
          [attr.aria-pressed]="part().type === option.type"
          [disabled]="!facade.editableNow()"
          (click)="select(option.type)"
        >
          {{ label(option.type) }}
        </button>
      }
    </div>
  `,
  host: { class: 'contents' },
})
export class PartTypeSwitcher {
  /** The index of the part this switcher retypes. */
  readonly partIndex = input.required<number>();
  /** The live part (drives per-button visibility + the active highlight). */
  readonly part = input.required<MessagePart>();

  protected readonly facade = inject(EditorFacade);
  private readonly fields = inject(EditorFieldsService);
  private readonly transloco = inject(TranslocoService);
  private readonly language = inject(LanguageService);

  protected readonly visibleOptions = computed<readonly PartTypeOption[]>(() => {
    const part = this.part();
    return PART_TYPE_OPTIONS.filter((option) => this.fields.showPartType(part, option.gate));
  });

  protected label(type: string): string {
    this.language.selectedLanguage();
    const key = partTypeLabelKey(type);
    // The switcher only offers the catalog types, so `key` is always resolvable
    // here — the raw-type fallback just mirrors the renderer's unknown-type guard.
    return key ? this.transloco.translate(key) : type;
  }

  protected select(type: string): void {
    this.facade.setPartType(this.partIndex(), type);
  }
}
