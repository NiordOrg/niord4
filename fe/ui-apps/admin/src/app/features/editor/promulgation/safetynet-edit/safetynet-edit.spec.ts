import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';

import { provideNiordIcons } from '../../../../shared/icons/niord-icons';
import { provideTestTransloco } from '../../../../shared/testing/transloco-testing';
import type { MessageStatus } from '../../../messages/models/message.model';
import type { SafetynetPromulgation } from '../../../messages/promulgation/safetynet-view/safetynet-promulgation.model';
import type { PromulgationEditorContext } from '../promulgation-editor.token';
import { SafetynetEdit } from './safetynet-edit';

function makeContext(
  overrides: Partial<PromulgationEditorContext> = {},
): PromulgationEditorContext {
  return {
    patch: vi.fn(),
    generate: vi.fn(),
    reformat: vi.fn(),
    busy: vi.fn(() => false),
    messageStatus: signal<MessageStatus | undefined>('DRAFT'),
    ...overrides,
  };
}

const SAFETYNET: SafetynetPromulgation = {
  type: { typeId: 's', serviceId: 'safetynet', name: 'SafetyNET' },
  promulgate: true,
  areas: [{ name: 'Baltic' }, { name: 'North Sea' }],
  areaName: 'Baltic',
  priority: 'SAFETY',
  text: 'BODY',
};

async function setup(promulgation = SAFETYNET, context = makeContext()) {
  TestBed.configureTestingModule({ providers: [provideTestTransloco(), provideNiordIcons()] });
  const fixture = TestBed.createComponent(SafetynetEdit);
  fixture.componentRef.setInput('promulgation', promulgation);
  fixture.componentRef.setInput('context', context);
  fixture.autoDetectChanges();
  await fixture.whenStable();
  return { fixture, el: fixture.nativeElement as HTMLElement, context };
}

describe('SafetynetEdit', () => {
  it('renders an area radio per offered area, with the chosen one selected', async () => {
    const { el } = await setup();
    const radios = el.querySelectorAll<HTMLInputElement>('input[type="radio"]');
    expect(radios.length).toBe(2);
    expect(radios[0].checked).toBe(true);
    expect(radios[1].checked).toBe(false);
  });

  it('patches areaName when another area is chosen', async () => {
    const { el, context } = await setup();
    const second = el.querySelectorAll<HTMLInputElement>('input[type="radio"]')[1];
    second.checked = true;
    second.dispatchEvent(new Event('change'));
    expect(context.patch).toHaveBeenCalledWith('s', { areaName: 'North Sea' });
  });

  it('offers only SAFETY and URGENCY priorities', async () => {
    const { el } = await setup();
    const options = Array.from(el.querySelectorAll('option')).map((o) => o.value);
    expect(options).toEqual(['SAFETY', 'URGENCY']);
  });

  it('flags a promulgating channel with no area and no offered areas as Undefined', async () => {
    const { el } = await setup({
      type: { typeId: 's', serviceId: 'safetynet', name: 'SafetyNET' },
      promulgate: true,
      areas: [],
    });
    expect(el.textContent).toContain('editor.promulgation.safetynet.areaUndefined');
  });

  it('hides the SafetyNET id unless the message is PUBLISHED', async () => {
    const { el } = await setup();
    expect(el.querySelector('input[type="text"]')).toBeNull();
  });

  it('shows the SafetyNET id on a PUBLISHED message and patches it', async () => {
    const { el, context } = await setup(
      SAFETYNET,
      makeContext({ messageStatus: signal<MessageStatus | undefined>('PUBLISHED') }),
    );
    const idInput = el.querySelector<HTMLInputElement>('input[type="text"]')!;
    expect(idInput).not.toBeNull();
    idInput.value = '130/17';
    idInput.dispatchEvent(new Event('input'));
    expect(context.patch).toHaveBeenCalledWith('s', { safetyNetId: '130/17' });
  });

  it('invokes Format (replaces only text server-side) through the context', async () => {
    const { el, context } = await setup();
    const format = Array.from(el.querySelectorAll('button')).at(-1)!;
    format.click();
    expect(context.reformat).toHaveBeenCalledWith('safetynet', 's');
  });
});
