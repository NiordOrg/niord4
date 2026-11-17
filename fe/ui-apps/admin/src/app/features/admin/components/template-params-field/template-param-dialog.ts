import { inject, Component, computed, output, signal } from '@angular/core';
import { form, FormField, required, validate } from '@angular/forms/signals';
import { TranslocoPipe } from '@jsverse/transloco';

import { LanguageService } from '../../../../core/main-shell/services/language.service';
import { Dialog } from '../../../../shared/components/dialog/dialog';
import { LocalizedTextInput } from '../localized-text-input/localized-text-input';
import type {
  TemplateParam,
  TemplateParamDesc,
} from '../../services/api/param-types-admin-api.service';
import { padAndOrderDescs } from '../../utils/localized-descs';

/** Validation error kind: the id is one another row of the list holds. */
const ID_TAKEN = 'idTaken';

interface ParamRow {
  lang: string;
  name: string;
}

interface ParamFormModel {
  paramId: string;
  type: string;
  mandatory: boolean;
  positionList: boolean;
  descs: ParamRow[];
}

/**
 * Add/edit modal for a single nested template parameter. Opens seeded from a copy
 * (OK commits, Cancel discards). Holds a typed form: `paramId` (required, and unique
 * among the list's rows), the
 * referenced param-type `type` (required, picked from the supplied editable names),
 * `mandatory`/`positionList` flags, and an N-language `descs` name array (the
 * current-language name required).
 */
@Component({
  selector: 'app-template-param-dialog',
  imports: [Dialog, FormField, LocalizedTextInput, TranslocoPipe],
  templateUrl: './template-param-dialog.html',
  host: { class: 'contents' },
})
export class TemplateParamDialog {
  private readonly languageSettings = inject(LanguageService);
  readonly committed = output<TemplateParam>();

  protected readonly visible = signal(false);
  protected readonly typeOptions = signal<string[]>([]);
  protected readonly editMode = signal(false);

  /** The ids the list's other rows hold (the row being edited left out). */
  private readonly takenIds = signal<readonly string[]>([]);

  private readonly formModel = signal<ParamFormModel>({
    paramId: '',
    type: 'text',
    mandatory: false,
    positionList: false,
    descs: [],
  });
  protected readonly form = form(this.formModel, (path) => {
    required(path.paramId);
    // The execute form keys its values by paramId, so two rows with one id would
    // share one value. Compared exactly, as the server compares them.
    validate(path.paramId, ({ value }) =>
      this.takenIds().includes(value()) ? { kind: ID_TAKEN } : null,
    );
    required(path.type);
  });

  /** The id is one another row of the list already holds; the field says so. */
  protected readonly idTaken = computed(() =>
    this.form
      .paramId()
      .errors()
      .some((error) => error.kind === ID_TAKEN),
  );

  protected readonly headerKey = computed(() =>
    this.editMode()
      ? 'admin.paramTypes.paramDialog.editTitle'
      : 'admin.paramTypes.paramDialog.addTitle',
  );

  /** The first configured language's name is required. */
  protected readonly firstNameBlank = computed(
    () => (this.formModel().descs[0]?.name ?? '').trim() === '',
  );
  protected readonly okDisabled = computed(() => this.form().invalid() || this.firstNameBlank());

  /**
   * Open seeded from `param` (edit) or a skeleton (add). `typeNames` is the editable type pool;
   * `takenIds` the ids of the list's other rows, which OK refuses.
   */
  open(
    param: TemplateParam | null,
    typeNames: readonly string[],
    takenIds: readonly string[] = [],
  ): void {
    this.typeOptions.set([...typeNames]);
    this.takenIds.set([...takenIds]);
    this.editMode.set(param !== null);
    const padded = padAndOrderDescs(
      param?.descs ?? [],
      this.languageSettings.modelLanguages.map((language) => language.value),
      (lang): TemplateParamDesc => ({ lang, name: '' }),
    );
    this.formModel.set({
      paramId: param?.paramId ?? '',
      // Empty pool → empty type so the required validator blocks OK (no bogus ref).
      type: param?.type ?? typeNames[0] ?? '',
      mandatory: param?.mandatory ?? false,
      positionList: param?.positionList ?? false,
      descs: padded.map((desc) => ({ lang: desc.lang, name: desc.name })),
    });
    this.form().reset();
    this.visible.set(true);
  }

  protected ok(): void {
    if (this.okDisabled()) {
      return;
    }
    const value = this.formModel();
    this.committed.emit({
      paramId: value.paramId,
      type: value.type,
      mandatory: value.mandatory,
      positionList: value.positionList,
      descs: value.descs.map((desc) => ({ lang: desc.lang, name: desc.name })),
    });
    this.visible.set(false);
  }

  protected cancel(): void {
    this.visible.set(false);
  }
}
