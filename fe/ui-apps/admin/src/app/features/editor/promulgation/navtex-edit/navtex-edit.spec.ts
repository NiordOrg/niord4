import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';

import { provideNiordIcons } from '../../../../shared/icons/niord-icons';
import { provideTestTransloco } from '../../../../shared/testing/transloco-testing';
import type { MessageStatus } from '../../../messages/models/message.model';
import type { NavtexPromulgation } from '../../../messages/promulgation/navtex-view/navtex-promulgation.model';
import type { PromulgationEditorContext } from '../promulgation-editor.token';
import { NavtexEdit } from './navtex-edit';

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

const NAVTEX: NavtexPromulgation = {
  type: { typeId: 'n', serviceId: 'navtex', name: 'NAVTEX' },
  promulgate: true,
  preamble: 'ZCZC',
  text: 'BODY',
  priority: 'ROUTINE',
  transmitters: { Baltico: true, Rogaland: false },
};

async function setup(promulgation = NAVTEX, context = makeContext()) {
  TestBed.configureTestingModule({ providers: [provideTestTransloco(), provideNiordIcons()] });
  const fixture = TestBed.createComponent(NavtexEdit);
  fixture.componentRef.setInput('promulgation', promulgation);
  fixture.componentRef.setInput('context', context);
  fixture.autoDetectChanges();
  await fixture.whenStable();
  return { fixture, el: fixture.nativeElement as HTMLElement, context };
}

describe('NavtexEdit', () => {
  it('renders a read-only, tab-order-excluded preamble', async () => {
    const { el } = await setup();
    const preamble = el.querySelectorAll('textarea')[0];
    expect(preamble.readOnly).toBe(true);
    expect(preamble.tabIndex).toBe(-1);
    expect(preamble.value).toBe('ZCZC');
  });

  it('renders one checkbox per transmitter key, reflecting the map', async () => {
    const { el } = await setup();
    const checkboxes = el.querySelectorAll<HTMLInputElement>('input[type="checkbox"]');
    expect(checkboxes.length).toBe(2);
    expect(checkboxes[0].checked).toBe(true);
    expect(checkboxes[1].checked).toBe(false);
  });

  it('lists the transmitters by name, whatever order the map holds them in', async () => {
    // The server fills the map from a HashMap, so its key order is no order anyone chose.
    const { el } = await setup({
      ...NAVTEX,
      transmitters: { Zulu: false, Rogaland: true, Baltico: false, alpha: true },
    });
    const labels = Array.from(el.querySelectorAll('label')).map((l) => l.textContent?.trim());
    expect(labels).toEqual(['alpha', 'Baltico', 'Rogaland', 'Zulu']);
    const checked = Array.from(el.querySelectorAll<HTMLInputElement>('input[type="checkbox"]')).map(
      (c) => c.checked,
    );
    expect(checked).toEqual([true, false, true, false]);
  });

  it('patches a merged transmitter map when a transmitter is toggled', async () => {
    const { el, context } = await setup();
    const second = el.querySelectorAll<HTMLInputElement>('input[type="checkbox"]')[1];
    second.checked = true;
    second.dispatchEvent(new Event('change'));
    expect(context.patch).toHaveBeenCalledWith('n', {
      transmitters: { Baltico: true, Rogaland: true },
    });
  });

  it('offers the NONE..VITAL priority options (NONE included)', async () => {
    const { el } = await setup();
    const options = Array.from(el.querySelectorAll('option')).map((o) => o.value);
    expect(options).toEqual(['NONE', 'ROUTINE', 'IMPORTANT', 'VITAL']);
  });

  it('patches the body text on input', async () => {
    const { el, context } = await setup();
    const body = el.querySelectorAll('textarea')[1];
    body.value = 'NEW BODY';
    body.dispatchEvent(new Event('input'));
    expect(context.patch).toHaveBeenCalledWith('n', { text: 'NEW BODY' });
  });

  it('invokes Generate and Format through the context', async () => {
    const { el, context } = await setup();
    const buttons = el.querySelectorAll('button');
    buttons[0].click();
    buttons[1].click();
    expect(context.generate).toHaveBeenCalledWith('n');
    expect(context.reformat).toHaveBeenCalledWith('navtex', 'n');
  });
});
