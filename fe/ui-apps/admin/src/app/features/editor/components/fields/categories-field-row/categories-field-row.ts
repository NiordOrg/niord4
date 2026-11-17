import { Component, computed, inject } from '@angular/core';
import { Router } from '@angular/router';
import { TranslocoPipe } from '@jsverse/transloco';

import { DomainFacade } from '../../../../../core/domain/facades/domain.facade';
import { LanguageService } from '../../../../../core/main-shell/services/language.service';
import { Icon } from '../../../../../shared/icons/icon';
import { CategoriesField } from '../../../../messages/components/categories-field/categories-field';
import type { CategoryRef } from '../../../../messages/models/messages-filter-params.model';
import { EditorFacade } from '../../../services/editor-facade';
import { EditorFieldsService } from '../../../services/editor-fields.service';
import { TemplateExecutionSeedStore } from '../../../services/template-execution-seed-store';
import { EditorFieldRow } from '../../editor-field-row/editor-field-row';

/**
 * The always-shown `categories` field row.
 *
 * The CATEGORIES picker (reused `CategoriesField`, domain-scoped, multiple) is structural — its
 * selection flows through `facade.setCategories` (mark dirty only, no adjust). The
 * category-template **execution** launcher is the "Run template…" button (shown when the domain
 * exposes executable templates), which seeds a clone of the working message into the execution view
 * and enters the
 * `execute-template` route; running there re-renders the whole message and applies it back (with a
 * confirm when there are unsaved edits). The viewer lists the selected category names.
 */
@Component({
  selector: 'app-categories-field-row',
  imports: [CategoriesField, EditorFieldRow, Icon, TranslocoPipe],
  template: `
    <app-editor-field-row
      fieldId="categories"
      label="msg.field.categories"
      [edited]="fields.edited('categories')"
      [empty]="categories().length === 0"
      emptyLabel="editor.field.categories.empty"
    >
      <span fieldViewer>{{ categoryNames() }}</span>

      <div fieldEditor class="flex flex-col gap-2">
        <app-categories-field
          [value]="categories()"
          [domainId]="domainId()"
          [disabled]="!facade.editableNow()"
          placeholder="editor.field.categories.pickerPlaceholder"
          (valueChange)="onCategoriesChange($event)"
        />
        @if (hasTemplates() && facade.editableNow()) {
          <button
            type="button"
            class="link-button is-subtle inline-flex items-center gap-1 self-start text-xs"
            (click)="runTemplate()"
          >
            <app-icon name="lucidePlay" />
            <span>{{ 'editor.template.run' | transloco }}</span>
          </button>
        }
      </div>
    </app-editor-field-row>
  `,
  host: { class: 'contents' },
})
export class CategoriesFieldRow {
  protected readonly facade = inject(EditorFacade);
  protected readonly fields = inject(EditorFieldsService);
  private readonly language = inject(LanguageService);
  private readonly domain = inject(DomainFacade);
  private readonly router = inject(Router);
  private readonly seedStore = inject(TemplateExecutionSeedStore);

  /** Whether the active domain exposes executable templates. */
  protected readonly hasTemplates = computed<boolean>(() => this.domain.capabilities().templates);

  protected readonly categories = computed<readonly CategoryRef[]>(
    () => this.facade.message()?.categories ?? [],
  );

  /** Session domain scopes the category search. */
  protected readonly domainId = computed<string | null>(
    () => this.domain.sessionDomain()?.domainId ?? null,
  );

  protected readonly categoryNames = computed<string>(() => {
    const lang = this.language.selectedLanguage();
    return this.categories()
      .map((category) => {
        const descs = category.descs ?? [];
        return (descs.find((entry) => entry.lang === lang) ?? descs[0])?.name ?? '';
      })
      .filter((name) => name.length > 0)
      .join(', ');
  });

  protected onCategoriesChange(categories: readonly CategoryRef[]): void {
    this.facade.setCategories(categories);
  }

  /** Seed a clone of the working message and enter the execution view (Cancel returns here). */
  protected runTemplate(): void {
    const message = this.facade.message();
    if (!message) {
      return;
    }
    this.seedStore.set(structuredClone(message), this.router.url);
    void this.router.navigate(['/editor/execute-template']);
  }
}
