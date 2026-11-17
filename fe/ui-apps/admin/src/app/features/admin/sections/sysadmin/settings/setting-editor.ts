import {
  Component,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
  untracked,
} from '@angular/core';
import { form, FormField } from '@angular/forms/signals';
import { TranslocoPipe } from '@jsverse/transloco';

import { NotificationService } from '../../../../../core/notifications/notification.service';
import { Drawer } from '../../../../../shared/components/drawer/drawer';
import { jsonStringValid } from '../../../../../shared/forms/json-syntax.validator';
import { LengthCounter, tooLong } from '../../../../../shared/forms/length-counter/length-counter';
import { CodeTextInput } from '../../../components/code-text-input/code-text-input';
import { Setting, SettingsApiService } from '../../../services/api/settings-api.service';

export interface SettingEditorModel {
  readonly setting: Setting;
}

/** The value-control flavor chosen from the setting's raw `type` string. */
export type ValueKind = 'boolean' | 'integer' | 'decimal' | 'password' | 'text' | 'json';

/**
 * Maps the setting's raw `type` string to a value-control flavor using exact
 * string/operator matching (`=== 'Password'`, `=== undefined`, `== 'String'`,
 * lowercase `'json'`). Order matters — the first match wins. Any UNMATCHED
 * non-undefined type (e.g. `Date`/`Path`/a future type) deliberately falls
 * through to a plain text editor.
 */
export function valueKindFor(type: string | undefined): ValueKind {
  if (type === 'Boolean') {
    return 'boolean';
  }
  if (type === 'Long' || type === 'Integer') {
    return 'integer';
  }
  if (type === 'Float' || type === 'Double') {
    return 'decimal';
  }
  if (type === 'Password') {
    return 'password';
  }
  if (type === 'json') {
    return 'json';
  }
  // `undefined` (untyped) AND `'String'` both render text; so does every
  // unmatched type (plain-text fallback).
  return 'text';
}

/**
 * The longest plain text a text setting can hold.
 *
 * The column is varchar(2000), but the value is stored as JSON, so a text takes
 * its two quotes into it: 1,998 characters of text fill it. A quote, a backslash
 * or a control character is escaped there and takes two or more, so a text full
 * of them can still be refused by the server below this number.
 */
const TEXT_VALUE_MAX = 1998;

interface SettingFormModel {
  value: unknown;
}

/**
 * Edit form for a single editable system setting, hosted in the shared drawer.
 * Key and Description are display-only (read straight off the model — they are
 * never editable). The single live `value` control is type-dispatched: an 8-way
 * `@switch` renders the right widget, with the `json` branch using the shared
 * `CodeTextInput` CVA behind a JSON-syntax validator that blocks Save while it
 * fails to parse. Any unmatched type falls through to plain text.
 *
 * Save gating mirrors the other admin editors: no net change (reverting to the
 * opened value re-disables Save), invalid (the json parse error composes here),
 * or mid-save all disable Save. The page owns visibility and the deep copy; the
 * drawer's `[dirty]` gate fires the unsaved-changes confirm on a dirty close.
 */
@Component({
  selector: 'app-setting-editor',
  imports: [Drawer, FormField, CodeTextInput, LengthCounter, TranslocoPipe],
  templateUrl: './setting-editor.html',
  host: { class: 'contents' },
})
export class SettingEditor {
  private readonly api = inject(SettingsApiService);
  private readonly notifications = inject(NotificationService);

  /** `undefined` = closed; the page owns visibility. */
  readonly model = input.required<SettingEditorModel | undefined>();
  readonly saved = output<void>();
  readonly closed = output<void>();

  protected readonly editKind = signal<ValueKind>('text');
  protected readonly saving = signal(false);

  // A single dynamic `value` field (key/description are display-only, read off
  // the model). The @switch renders the right widget per `editKind`; the field
  // value is `unknown` because it morphs (boolean/number/string) with the kind.
  private readonly formModel = signal<SettingFormModel>({ value: null });
  protected readonly form = form(this.formModel);

  /**
   * Net change against the opened values. Signal Forms' `dirty` tracks user
   * interaction and never clears on revert, so it can't gate Save on "differs
   * from what was loaded" — a serialized baseline comparison does.
   */
  private readonly baselineJson = signal('');
  protected readonly changed = computed(
    () => JSON.stringify(this.formModel()) !== this.baselineJson(),
  );

  /**
   * True when the json value currently fails to parse — gated as a computed
   * (Signal Forms has no imperative add/removeValidator), it blocks Save and
   * drives the inline error.
   */
  protected readonly jsonInvalid = computed(
    () => this.editKind() === 'json' && !jsonStringValid(String(this.formModel().value ?? '')),
  );

  /**
   * True for the number kinds, whose empty value the backend reads as the
   * setting's default (L-180): the drawer says so under the input. An emptied
   * number is still sent as null; text, password, json and the others keep an
   * empty value as meant, so they show no such hint.
   */
  protected readonly emptyMeansDefault = computed(
    () => this.editKind() === 'integer' || this.editKind() === 'decimal',
  );

  protected readonly textValueMax = TEXT_VALUE_MAX;

  /** The text as typed, for the counter under a text value. */
  protected readonly textValue = computed(() => String(this.formModel().value ?? ''));

  /**
   * True when a text value is longer than the column holds (ERR7): it blocks
   * Save, and the counter under the input says by how much. Kept to the text
   * kind, the one that renders the counter.
   */
  protected readonly textTooLong = computed(
    () => this.editKind() === 'text' && tooLong(this.textValue(), TEXT_VALUE_MAX),
  );

  /** Save gates on a real change, a valid json value, a text that fits, and not mid-save. */
  protected readonly saveDisabled = computed(
    () => !this.changed() || this.jsonInvalid() || this.textTooLong() || this.saving(),
  );

  constructor() {
    effect(() => {
      const model = this.model();
      if (model) {
        // untracked: rebuild only when the drawer (re)opens — a tracked read
        // would rebuild the control mid-edit and wipe unsaved input.
        untracked(() => this.rebuildForm(model.setting));
      }
    });
  }

  private rebuildForm(setting: Setting): void {
    const kind = valueKindFor(setting.type);
    this.editKind.set(kind);
    const next: SettingFormModel = { value: wireToFormValue(kind, setting.value) };
    this.formModel.set(next);
    this.baselineJson.set(JSON.stringify(next));
    this.form().reset();
    this.saving.set(false);
  }

  protected save(): void {
    const model = this.model();
    // Guards the Enter-submit path on the same gate as the footer button — an
    // unchanged or invalid form never fires a save.
    if (!model || this.saveDisabled()) {
      return;
    }
    const source = model.setting;
    const setting: Setting = {
      key: source.key,
      description: source.description,
      type: source.type,
      value: formValueToWire(this.editKind(), this.formModel().value),
    };
    this.saving.set(true);
    this.api.updateSetting(setting).subscribe({
      next: () => {
        // Success: no toast — the page refetches and closes.
        this.saved.emit();
      },
      error: () => {
        // Stay on the drawer for retry.
        this.saving.set(false);
        this.notifications.error('admin.settings.toast.saveError');
      },
    });
  }
}

/** Map the wire value into the control's working representation for the kind. */
function wireToFormValue(kind: ValueKind, value: unknown): unknown {
  switch (kind) {
    case 'boolean':
      return value === true;
    case 'integer':
    case 'decimal':
      return value ?? null;
    case 'json':
      // The CVA holds the STRING form; pretty-print the parsed object/array.
      return value === undefined || value === null ? '' : JSON.stringify(value, null, 2);
    default:
      // password / text / fallback — a raw string.
      return value === undefined || value === null ? '' : String(value);
  }
}

/** Map the control's working representation back to the wire value for the PUT. */
function formValueToWire(kind: ValueKind, value: unknown): unknown {
  switch (kind) {
    case 'boolean':
      return value === true;
    case 'integer':
    case 'decimal':
      return value === '' || value === null || value === undefined ? null : Number(value);
    case 'json':
      // Guarded by the json-syntax validator (Save is blocked while invalid),
      // so a parse here is safe — send the parsed object the backend expects.
      return JSON.parse((value as string) === '' ? 'null' : (value as string));
    default:
      return value === undefined || value === null ? '' : String(value);
  }
}
