import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Observable, of, throwError } from 'rxjs';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { AuthFacade } from '../../../../core/auth/facades/auth.facade';
import { NotificationService } from '../../../../core/notifications/notification.service';
import { ConfirmService } from '../../../../shared/services/confirm.service';
import { provideNiordIcons } from '../../../../shared/icons/niord-icons';
import { provideTestTransloco } from '../../../../shared/testing/transloco-testing';
import type {
  BasePromulgation,
  Message,
  PromulgationRequirement,
} from '../../../messages/models/message.model';
import { PromulgationsApiService } from '../../../messages/services/api/promulgations-api.service';
import { EditorFacade } from '../../services/editor-facade';
import { EditorFieldsService } from '../../services/editor-fields.service';
import { NavtexEdit } from '../navtex-edit/navtex-edit';
import { SafetynetEdit } from '../safetynet-edit/safetynet-edit';
import { TwitterEdit } from '../twitter-edit/twitter-edit';
import { PROMULGATION_EDITORS } from '../promulgation-editor.token';
import { PromulgationField } from './promulgation-field';

function entry(
  typeId: string,
  serviceId: string,
  name: string,
  requirement: PromulgationRequirement,
  promulgate: boolean,
  extra: Record<string, unknown> = {},
): BasePromulgation {
  return {
    type: { typeId, serviceId, name, requirement },
    promulgate,
    ...extra,
  } as BasePromulgation;
}

function message(promulgations: BasePromulgation[] | undefined): Message {
  return { id: 'm', status: 'DRAFT', promulgations } as Message;
}

class StubFacade {
  readonly message = signal<Message | undefined>(undefined);
  readonly loadedBaseline = signal<Message | undefined>(undefined);
  readonly editableNow = signal(true);

  setPromulgate = vi.fn((typeId: string, promulgate: boolean) => {
    this.mutate(typeId, (p) => ({ ...p, promulgate }));
  });
  patchPromulgation = vi.fn((typeId: string, partial: Record<string, unknown>) => {
    this.mutate(typeId, (p) => ({ ...p, ...partial }));
  });
  applyGeneratedPromulgation = vi.fn();

  private mutate(typeId: string, fn: (p: BasePromulgation) => BasePromulgation): void {
    const current = this.message();
    if (!current) {
      return;
    }
    this.message.set({
      ...current,
      promulgations: (current.promulgations ?? []).map((p) =>
        p.type.typeId === typeId ? fn(p) : p,
      ),
    });
  }
}

class StubFields {
  isExpanded = () => true;
  edited = () => false;
  registerLabel = vi.fn();
  unregisterLabel = vi.fn();
  toggle = vi.fn();
  expand = vi.fn();
  collapse = vi.fn();
  focusNext = vi.fn();
  focusPrevious = vi.fn();
}

@Component({
  imports: [PromulgationField],
  template: `<app-promulgation-field />`,
})
class HostComponent {}

function setup(initial: Message | undefined) {
  const facade = new StubFacade();
  if (initial) {
    facade.message.set(initial);
    facade.loadedBaseline.set(structuredClone(initial));
  }
  const confirm = { confirmNeutral: vi.fn((opts: { accept: () => void }) => opts.accept()) };
  const generatePromulgation = vi.fn<
    (typeId: string, msg: Message) => Observable<BasePromulgation>
  >((typeId) => of({ type: { typeId }, text: 'GENERATED' } as unknown as BasePromulgation));
  const reformat = vi.fn<
    (serviceId: string, entry: BasePromulgation) => Observable<BasePromulgation>
  >(() => of({ text: 'FORMATTED' } as unknown as BasePromulgation));
  const notify = { error: vi.fn() };

  TestBed.resetTestingModule();
  TestBed.configureTestingModule({
    providers: [
      provideTestTransloco(),
      provideNiordIcons(),
      { provide: EditorFacade, useValue: facade },
      { provide: EditorFieldsService, useValue: new StubFields() },
      { provide: AuthFacade, useValue: { hasRole: () => signal(true) } },
      { provide: ConfirmService, useValue: confirm },
      { provide: NotificationService, useValue: notify },
      { provide: PromulgationsApiService, useValue: { generatePromulgation, reformat } },
      {
        provide: PROMULGATION_EDITORS,
        useValue: { serviceId: 'navtex', component: NavtexEdit },
        multi: true,
      },
      {
        provide: PROMULGATION_EDITORS,
        useValue: { serviceId: 'safetynet', component: SafetynetEdit },
        multi: true,
      },
      {
        provide: PROMULGATION_EDITORS,
        useValue: { serviceId: 'twitter', component: TwitterEdit },
        multi: true,
      },
    ],
  });

  const fixture = TestBed.createComponent(HostComponent);
  fixture.autoDetectChanges();
  const el = fixture.nativeElement as HTMLElement;
  const headingCheckboxes = () =>
    Array.from(el.querySelectorAll<HTMLInputElement>('[role="tab"] input[type="checkbox"]'));
  const genAllButton = () =>
    Array.from(el.querySelectorAll('button')).find((b) =>
      b.textContent?.includes('term.generate_all'),
    );
  return {
    fixture,
    el,
    facade,
    confirm,
    generatePromulgation,
    reformat,
    notify,
    headingCheckboxes,
    genAllButton,
  };
}

const THREE: Message = message([
  entry('n', 'navtex', 'NAVTEX', 'OPTIONAL', true),
  entry('s', 'safetynet', 'SafetyNET', 'MANDATORY', false),
  entry('tw', 'twitter', 'Twitter', 'MANDATORY', true),
]);

describe('PromulgationField', () => {
  afterEach(() => {
    document.querySelectorAll('.cdk-overlay-container').forEach((c) => c.replaceChildren());
  });

  it('renders no row when the message carries no promulgations', async () => {
    const { fixture, el } = setup(message(undefined));
    await fixture.whenStable();
    expect(el.querySelector('[data-field-row="promulgation"]')).toBeNull();
  });

  it('renders one tab per promulgation entry', async () => {
    const { fixture, el } = setup(THREE);
    await fixture.whenStable();
    expect(el.querySelectorAll('[role="tab"]').length).toBe(3);
  });

  it('disables the checkbox only for a MANDATORY channel that is promulgating (asymmetry)', async () => {
    const { fixture, headingCheckboxes } = setup(THREE);
    await fixture.whenStable();
    const [navtex, safetynet, twitter] = headingCheckboxes();
    expect(navtex.disabled).toBe(false); // OPTIONAL — toggles both ways
    expect(safetynet.disabled).toBe(false); // MANDATORY + off — can still check ON
    expect(twitter.disabled).toBe(true); // MANDATORY + on — can never uncheck
  });

  it('marks a MANDATORY-unpromulgated heading invalid', async () => {
    const { fixture, el } = setup(THREE);
    await fixture.whenStable();
    const tabs = Array.from(el.querySelectorAll('[role="tab"]'));
    const safetynetTab = tabs[1];
    expect(safetynetTab.querySelector('.text-danger')).not.toBeNull();
  });

  it('disables + greys the body of an unchecked channel', async () => {
    const { fixture, el } = setup(THREE);
    await fixture.whenStable();
    const fieldsets = Array.from(el.querySelectorAll('fieldset'));
    // Order matches the entries; the SafetyNET (index 1) channel is off.
    expect(fieldsets[1].disabled).toBe(true);
    expect(fieldsets[1].classList.contains('opacity-50')).toBe(true);
    expect(fieldsets[0].disabled).toBe(false);
  });

  it('does not switch the active tab when a heading checkbox is clicked', async () => {
    const { fixture, el, facade, headingCheckboxes } = setup(THREE);
    await fixture.whenStable();
    // NAVTEX is the default active tab; click the SafetyNET heading checkbox.
    headingCheckboxes()[1].click();
    await fixture.whenStable();
    const selected = el.querySelector('[role="tab"][aria-selected="true"]');
    expect(selected?.textContent).toContain('NAVTEX');
    expect(facade.setPromulgate).toHaveBeenCalledWith('s', true);
  });

  it('renders nothing for an unknown serviceId', async () => {
    const { fixture, el } = setup(message([entry('x', 'mystery', 'Mystery', 'OPTIONAL', true)]));
    await fixture.whenStable();
    expect(el.querySelector('app-navtex-edit')).toBeNull();
    expect(el.querySelector('app-safetynet-edit')).toBeNull();
    expect(el.querySelector('app-twitter-edit')).toBeNull();
    expect(el.querySelectorAll('[role="tab"]').length).toBe(1);
  });

  it('Generate-all confirms once and generates every promulgating channel (off ones skipped)', async () => {
    const { fixture, confirm, generatePromulgation, genAllButton } = setup(THREE);
    await fixture.whenStable();
    genAllButton()!.click();
    await fixture.whenStable();
    expect(confirm.confirmNeutral).toHaveBeenCalledTimes(1);
    const generatedTypeIds = generatePromulgation.mock.calls.map((c) => c[0]);
    expect(generatedTypeIds).toEqual(['n', 'tw']); // 's' is off → skipped
  });

  it('isolates a per-entry Generate-all failure (siblings still applied, one toast)', async () => {
    const { fixture, facade, notify, generatePromulgation, genAllButton } = setup(THREE);
    generatePromulgation.mockImplementation((typeId) =>
      typeId === 'n'
        ? throwError(() => new Error('boom'))
        : of({ type: { typeId }, text: 'GENERATED' } as unknown as BasePromulgation),
    );
    await fixture.whenStable();
    genAllButton()!.click();
    await fixture.whenStable();
    // 'n' failed → one toast; 'tw' succeeded → applied; 's' off → skipped.
    expect(notify.error).toHaveBeenCalledTimes(1);
    expect(facade.applyGeneratedPromulgation).toHaveBeenCalledTimes(1);
    expect(facade.applyGeneratedPromulgation).toHaveBeenCalledWith('tw', expect.anything());
  });

  it('per-tab Generate confirms, generates, and applies the response', async () => {
    const { fixture, el, confirm, generatePromulgation, facade } = setup(THREE);
    await fixture.whenStable();
    // The active (NAVTEX) panel's Generate button.
    const generate = Array.from(el.querySelectorAll('app-navtex-edit button')).find((b) =>
      b.textContent?.includes('term.generate'),
    ) as HTMLButtonElement;
    generate.click();
    await fixture.whenStable();
    expect(confirm.confirmNeutral).toHaveBeenCalledTimes(1);
    expect(generatePromulgation).toHaveBeenCalledWith('n', expect.objectContaining({ id: 'm' }));
    expect(facade.applyGeneratedPromulgation).toHaveBeenCalledWith(
      'n',
      expect.objectContaining({ text: 'GENERATED' }),
    );
  });

  it('posts the full working message (sibling unsaved edits included) on a single-tab Generate', async () => {
    const initial = message([
      entry('n', 'navtex', 'NAVTEX', 'OPTIONAL', true, { text: 'NAV' }),
      entry('s', 'safetynet', 'SafetyNET', 'OPTIONAL', true, { text: 'SIB-EDIT' }),
    ]);
    const { fixture, el, generatePromulgation } = setup(initial);
    await fixture.whenStable();
    const generate = Array.from(el.querySelectorAll('app-navtex-edit button')).find((b) =>
      b.textContent?.includes('term.generate'),
    ) as HTMLButtonElement;
    generate.click();
    await fixture.whenStable();
    const postedMessage = generatePromulgation.mock.calls[0][1] as Message;
    const sibling = postedMessage.promulgations?.find((p) => p.type.typeId === 's') as
      | (BasePromulgation & { text?: string })
      | undefined;
    expect(sibling?.text).toBe('SIB-EDIT');
  });

  it('reformats only the body text via the reformat endpoint', async () => {
    const initial = message([entry('n', 'navtex', 'NAVTEX', 'OPTIONAL', true, { text: 'OLD' })]);
    const { fixture, el, reformat, facade } = setup(initial);
    await fixture.whenStable();
    const format = Array.from(el.querySelectorAll('app-navtex-edit button')).find((b) =>
      b.textContent?.includes('term.format'),
    ) as HTMLButtonElement;
    format.click();
    await fixture.whenStable();
    const [serviceId, sentEntry] = reformat.mock.calls[0] as [string, BasePromulgation];
    expect(serviceId).toBe('navtex');
    expect(sentEntry.type.typeId).toBe('n');
    expect(facade.patchPromulgation).toHaveBeenCalledWith('n', { text: 'FORMATTED' });
  });
});
