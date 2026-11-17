import { Component, input } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';
import { Select } from '../../../../shared/forms/select/select';
import { ModelLanguageSelection } from '../../services/model-language-selection';

@Component({
  selector: 'app-model-language-picker',
  host: { class: 'block' },
  imports: [Select, TranslocoPipe],
  template: `
    @if (selection().languages().length > 1) {
      <div class="flex flex-wrap items-center gap-2">
        <span class="text-sm font-normal text-fg-muted">{{
          'admin.modelLanguage' | transloco
        }}</span>
        <app-select
          class="w-40"
          [options]="selection().languages()"
          optionLabel="label"
          optionValue="value"
          [value]="selection().value()"
          (valueChange)="selection().select($event)"
          [ariaLabel]="'admin.modelLanguage' | transloco"
        />
      </div>
    }
  `,
})
export class ModelLanguagePicker {
  readonly selection = input.required<ModelLanguageSelection>();
}
