import { Component, signal } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { type ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { describe, expect, it } from 'vitest';

import { APP_CONFIG } from '../../../../core/config/app-config.token';
import { provideTestTransloco } from '../../../../shared/testing/transloco-testing';
import type { ParamType, TemplateParam } from '../../services/api/param-types-admin-api.service';
import { TemplateParamDialog } from './template-param-dialog';
import { TemplateParamsField, type TemplateParamReorder } from './template-params-field';
import { LanguageSettingsService } from '../../../../core/i18n/language-settings.service';
import { ModelLanguageSelection, type ModelText } from '../../services/model-language-selection';

/** Minimal protected/private surface we reach for behavior assertions. */
interface FieldInternals {
  moveUp(index: number): void;
  moveDown(index: number): void;
  remove(index: number): void;
  add(): void;
  edit(index: number): void;
  onRowReorder(event: TemplateParamReorder): void;
  currentName(param: TemplateParam): ModelText;
  kindKey(param: TemplateParam): string;
  editableTypeNames(): readonly string[];
}

@Component({
  imports: [TemplateParamsField],
  template: `<app-template-params-field
    [(templateParams)]="params"
    [types]="types()"
    (paramsChanged)="changes = changes + 1"
  />`,
})
class Host {
  readonly params = signal<TemplateParam[]>([]);
  readonly types = signal('STANDARD,LIST,COMPOSITE');
  changes = 0;
}

function param(paramId: string, type = 'text', descs: TemplateParam['descs'] = []): TemplateParam {
  return { paramId, type, mandatory: false, positionList: false, descs };
}

const ALL_TYPES: ParamType[] = [
  { id: 1, type: 'STANDARD', name: 'text' },
  { id: 2, type: 'LIST', name: 'sea-state' },
  { id: 3, type: 'COMPOSITE', name: 'position' },
];

/**
 * Drain the effect scheduler + microtask queue so the param-types httpResource resolves
 * (zoneless). The project is zoneless, so we cannot use fakeAsync — flush manually instead.
 */
async function settle(): Promise<void> {
  TestBed.tick();
  await Promise.resolve();
  TestBed.tick();
  await Promise.resolve();
}

async function setup(configure?: (host: Host) => void) {
  TestBed.configureTestingModule({
    providers: [
      ModelLanguageSelection,
      provideTestTransloco(),
      provideHttpClient(),
      provideHttpClientTesting(),
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
    ],
  });
  TestBed.inject(LanguageSettingsService).configure({
    modelLanguages: ['da', 'en', 'kl'],
    defaultLanguage: 'en',
  });
  const fixture = TestBed.createComponent(Host);
  configure?.(fixture.componentInstance);
  fixture.detectChanges();
  await settle();

  // The component eagerly fetches all param types via httpResource; seed it so
  // kind labels and the editable-type pool are populated.
  const httpMock = TestBed.inject(HttpTestingController);
  httpMock.expectOne('/rest/templates/parameter-types').flush(ALL_TYPES);
  await settle();

  const field = fixture.debugElement.query(By.directive(TemplateParamsField))
    .componentInstance as unknown as FieldInternals;
  return { fixture, host: fixture.componentInstance, field, httpMock };
}

describe('TemplateParamsField — reorder logic', () => {
  it('inherits the model-language selection without emitting edits or changing parameters', async () => {
    const { fixture, host, field, httpMock } = await setup((host) =>
      host.params.set([
        param('condition', 'text', [
          { lang: 'en', name: 'Condition' },
          { lang: 'kl', name: 'Pissuseq' },
        ]),
      ]),
    );
    const original = structuredClone(host.params());
    TestBed.inject(ModelLanguageSelection).select('kl');
    fixture.detectChanges();
    expect(field.currentName(host.params()[0])).toEqual({ value: 'Pissuseq', lang: 'kl' });
    expect(host.params()).toEqual(original);
    expect(host.changes).toBe(0);
    httpMock.verify();
  });

  it('moveUp swaps with the previous row and propagates the new order to the host model', async () => {
    const { host, field } = await setup((h) => h.params.set([param('a'), param('b'), param('c')]));

    field.moveUp(1);

    expect(host.params().map((p) => p.paramId)).toEqual(['b', 'a', 'c']);
    expect(host.changes).toBe(1);
  });

  it('moveUp on the first row is a no-op (no swap, no change event)', async () => {
    const { host, field } = await setup((h) => h.params.set([param('a'), param('b')]));

    field.moveUp(0);

    expect(host.params().map((p) => p.paramId)).toEqual(['a', 'b']);
    expect(host.changes).toBe(0);
  });

  it('moveDown swaps with the next row', async () => {
    const { host, field } = await setup((h) => h.params.set([param('a'), param('b'), param('c')]));

    field.moveDown(0);

    expect(host.params().map((p) => p.paramId)).toEqual(['b', 'a', 'c']);
    expect(host.changes).toBe(1);
  });

  it('moveDown on the last row is a no-op (no swap, no change event)', async () => {
    const { host, field } = await setup((h) => h.params.set([param('a'), param('b')]));

    field.moveDown(1);

    expect(host.params().map((p) => p.paramId)).toEqual(['a', 'b']);
    expect(host.changes).toBe(0);
  });
});

describe('TemplateParamsField — onRowReorder (drag)', () => {
  it('moves the dragged row to the drop index', async () => {
    const { host, field } = await setup((h) => h.params.set([param('a'), param('b'), param('c')]));

    field.onRowReorder({ dragIndex: 0, dropIndex: 2 } as TemplateParamReorder);

    expect(host.params().map((p) => p.paramId)).toEqual(['b', 'c', 'a']);
    expect(host.changes).toBe(1);
  });

  it('returns early when dragIndex === dropIndex (no mutation, no change event)', async () => {
    const { host, field } = await setup((h) => h.params.set([param('a'), param('b')]));

    field.onRowReorder({ dragIndex: 1, dropIndex: 1 } as TemplateParamReorder);

    expect(host.params().map((p) => p.paramId)).toEqual(['a', 'b']);
    expect(host.changes).toBe(0);
  });

  it('returns early when an index is null', async () => {
    const { host, field } = await setup((h) => h.params.set([param('a'), param('b')]));

    field.onRowReorder({ dragIndex: null, dropIndex: 1 } as TemplateParamReorder);

    expect(host.params().map((p) => p.paramId)).toEqual(['a', 'b']);
    expect(host.changes).toBe(0);
  });
});

describe('TemplateParamsField — remove', () => {
  it('splices out the row at the given index and propagates', async () => {
    const { host, field } = await setup((h) => h.params.set([param('a'), param('b'), param('c')]));

    field.remove(1);

    expect(host.params().map((p) => p.paramId)).toEqual(['a', 'c']);
    expect(host.changes).toBe(1);
  });
});

describe('TemplateParamsField — kind label mapping', () => {
  it('editableTypeNames is filtered to the names whose kind is in [types]', async () => {
    const { fixture, field } = await setup((h) => h.types.set('STANDARD,LIST'));
    fixture.detectChanges();

    // 'text' (STANDARD) and 'sea-state' (LIST) qualify; 'position' (COMPOSITE) is excluded.
    expect([...field.editableTypeNames()].sort()).toEqual(['sea-state', 'text']);
  });

  it('editableTypeNames includes COMPOSITE only when [types] permits it', async () => {
    const { field } = await setup(); // default types = STANDARD,LIST,COMPOSITE

    expect([...field.editableTypeNames()].sort()).toEqual(['position', 'sea-state', 'text']);
  });

  it('kindKey maps a referenced type name to its kind i18n key', async () => {
    const { field } = await setup();

    expect(field.kindKey(param('p', 'text'))).toBe('admin.paramTypes.kind.STANDARD');
    expect(field.kindKey(param('p', 'position'))).toBe('admin.paramTypes.kind.COMPOSITE');
  });

  it('kindKey is empty for an unknown referenced type name', async () => {
    const { field } = await setup();

    expect(field.kindKey(param('p', 'nonexistent'))).toBe('');
  });
});

describe('TemplateParamsField — currentName ordering', () => {
  it('returns the current-language (en) desc name first', async () => {
    const { field } = await setup();

    const name = field.currentName(
      param('p', 'text', [
        { lang: 'da', name: 'Position' },
        { lang: 'en', name: 'Position EN' },
      ]),
    );

    expect(name).toEqual({ value: 'Position EN', lang: 'en' });
  });

  it('returns empty text without a language when the param has no descs', async () => {
    const { field } = await setup();

    expect(field.currentName(param('p', 'text', []))).toEqual({ value: '', lang: undefined });
  });
});

/** The parameter dialog's surface the duplicate-id cases reach. */
interface DialogInternals {
  form: { paramId: () => { value: { set(value: string): void } } };
  formModel: { update(fn: (model: { descs: { name: string }[] }) => unknown): void };
  okDisabled(): boolean;
  idTaken(): boolean;
  ok(): void;
}

function dialogOf(fixture: ComponentFixture<Host>): DialogInternals {
  return fixture.debugElement.query(By.directive(TemplateParamDialog))
    .componentInstance as unknown as DialogInternals;
}

/** Type a parameter id and a name in every language, so only the id rule can hold OK back. */
function fill(dialog: DialogInternals, paramId: string): void {
  dialog.form.paramId().value.set(paramId);
  dialog.formModel.update((model) => ({
    ...model,
    descs: model.descs.map((desc) => ({ ...desc, name: 'Name' })),
  }));
}

describe('TemplateParamsField — parameter ids are unique in the list (L-181)', () => {
  it('a new parameter cannot take an id another row holds', async () => {
    const { fixture, host, field } = await setup((h) => h.params.set([param('a'), param('b')]));
    field.add();
    fixture.detectChanges();
    const dialog = dialogOf(fixture);

    fill(dialog, 'a');
    expect(dialog.idTaken()).toBe(true);
    expect(dialog.okDisabled()).toBe(true);
    dialog.ok();
    expect(host.params().map((p) => p.paramId)).toEqual(['a', 'b']);
    expect(host.changes).toBe(0);

    fill(dialog, 'c');
    expect(dialog.idTaken()).toBe(false);
    expect(dialog.okDisabled()).toBe(false);
    dialog.ok();
    expect(host.params().map((p) => p.paramId)).toEqual(['a', 'b', 'c']);
  });

  it('an edited parameter keeps its own id but cannot take another row id', async () => {
    const { fixture, field } = await setup((h) => h.params.set([param('a'), param('b')]));
    field.edit(1);
    fixture.detectChanges();
    const dialog = dialogOf(fixture);

    fill(dialog, 'b');
    expect(dialog.idTaken()).toBe(false);
    expect(dialog.okDisabled()).toBe(false);

    fill(dialog, 'a');
    expect(dialog.idTaken()).toBe(true);
    expect(dialog.okDisabled()).toBe(true);
  });

  it('says why OK is held back, on the id field', async () => {
    const { fixture, field } = await setup((h) => h.params.set([param('a')]));
    field.add();
    fixture.detectChanges();
    const dialog = dialogOf(fixture);

    fill(dialog, 'a');
    fixture.detectChanges();
    const input = document.querySelector('#tp-param-id') as HTMLInputElement;
    const line = document.querySelector('#tp-param-id-error');
    expect(line?.textContent).toContain('admin.paramTypes.paramDialog.idTaken');
    expect(input.getAttribute('aria-invalid')).toBe('true');
    expect(input.getAttribute('aria-describedby')).toBe('tp-param-id-error');
  });

  it('ids that differ only in case are two ids, as the server reads them', async () => {
    const { fixture, field } = await setup((h) => h.params.set([param('kind')]));
    field.add();
    fixture.detectChanges();
    const dialog = dialogOf(fixture);

    fill(dialog, 'Kind');
    expect(dialog.idTaken()).toBe(false);
    expect(dialog.okDisabled()).toBe(false);
  });
});

describe('TemplateParamsField — two-way model', () => {
  it('reflects a host model write and pushes mutations back to the host', async () => {
    const { host, field, httpMock } = await setup((h) => h.params.set([param('a'), param('b')]));

    // Host -> field: a fresh write is observable through the field's behavior.
    field.moveDown(0);
    expect(host.params().map((p) => p.paramId)).toEqual(['b', 'a']);

    // Field -> host: removing keeps the host model the single source of truth.
    field.remove(0);
    expect(host.params().map((p) => p.paramId)).toEqual(['a']);

    httpMock.verify();
  });
});
