import { TestBed, type ComponentFixture } from '@angular/core/testing';
import { signal } from '@angular/core';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { LanguageService } from '../../../../core/main-shell/services/language.service';
import { MenuTrigger } from '../../../../shared/components/menu-trigger/menu-trigger';
import { provideTestTransloco } from '../../../../shared/testing/transloco-testing';
import { EditorFacade } from '../../services/editor-facade';
import { EditorFieldsService } from '../../services/editor-fields.service';
import { ManageOptionalFields } from './manage-optional-fields';

interface Scenario {
  isEditor: boolean;
  manageable: readonly string[];
  shown?: ReadonlySet<string>;
}

function setup(scenario: Scenario): {
  component: ManageOptionalFields;
  fixture: ComponentFixture<ManageOptionalFields>;
  toggleOptIn: ReturnType<typeof vi.fn>;
} {
  const toggleOptIn = vi.fn();
  const shown = scenario.shown ?? new Set<string>();
  const fields = {
    manageableFields: signal<readonly string[]>(scenario.manageable),
    showField: (id: string) => shown.has(id),
    toggleOptIn,
  };
  const facade = { isEditor: signal(scenario.isEditor) };
  const language = { selectedLanguage: signal('en') };

  TestBed.configureTestingModule({
    providers: [
      { provide: EditorFieldsService, useValue: fields as unknown as EditorFieldsService },
      { provide: EditorFacade, useValue: facade as unknown as EditorFacade },
      { provide: LanguageService, useValue: language as unknown as LanguageService },
      provideTestTransloco(),
    ],
  });

  const fixture = TestBed.createComponent(ManageOptionalFields);
  return { component: fixture.componentInstance, fixture, toggleOptIn };
}

describe('ManageOptionalFields', () => {
  beforeEach(() => vi.restoreAllMocks());

  it('lists every manageable field by its translated label key', () => {
    // The stub Transloco loader echoes the key, so a label equals its i18n key.
    const { component } = setup({
      isEditor: true,
      manageable: ['orig_info', 'event_dates', 'source'],
    });

    expect(component.items().map((i) => i.label)).toEqual([
      'msg.field.orig_info',
      'msg.field.event_dates',
      'msg.field.source',
    ]);
  });

  it('maps the `time` alias and an unmapped id to well-formed label keys', () => {
    const { component } = setup({ isEditor: true, manageable: ['time', 'made_up_field'] });

    expect(component.items().map((i) => i.label)).toEqual([
      'msg.field.event_dates',
      'msg.field.made_up_field',
    ]);
  });

  it('marks currently-shown fields as chosen (the selection dot)', () => {
    const { component } = setup({
      isEditor: true,
      manageable: ['charts', 'source'],
      shown: new Set(['source']),
    });

    const items = component.items();
    expect(items.find((i) => i.label === 'msg.field.charts')?.chosen).toBe(false);
    expect(items.find((i) => i.label === 'msg.field.source')?.chosen).toBe(true);
  });

  it('toggling a field forwards the visibility flip to the fields engine', () => {
    // The command re-opens the menu via the trigger — stub that so the microtask is a no-op.
    vi.spyOn(MenuTrigger.prototype, 'reopen').mockImplementation(() => undefined);
    const { component, fixture, toggleOptIn } = setup({
      isEditor: true,
      manageable: ['charts', 'source'],
    });
    fixture.detectChanges(); // resolve the viewChild(MenuTrigger) the command reads

    component
      .items()
      .find((i) => i.label === 'msg.field.source')
      ?.command?.();

    expect(toggleOptIn).toHaveBeenCalledTimes(1);
    expect(toggleOptIn).toHaveBeenCalledWith('source');
  });

  it('renders the trigger for an editor with fields to manage', () => {
    const { fixture } = setup({ isEditor: true, manageable: ['charts'] });
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('button')).not.toBeNull();
  });

  it('is hidden when there is nothing to manage', () => {
    const { fixture } = setup({ isEditor: true, manageable: [] });
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('button')).toBeNull();
  });

  it('is hidden for a non-editor even when fields are manageable', () => {
    const { fixture } = setup({ isEditor: false, manageable: ['charts', 'source'] });
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('button')).toBeNull();
  });
});
