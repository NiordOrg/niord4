import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';

import { provideNiordIcons } from '../../../../shared/icons/niord-icons';
import { provideTestTransloco } from '../../../../shared/testing/transloco-testing';
import type { MessageStatus } from '../../../messages/models/message.model';
import type { TwitterPromulgation } from '../../../messages/promulgation/twitter-view/twitter-promulgation.model';
import type { PromulgationEditorContext } from '../promulgation-editor.token';
import { TwitterEdit } from './twitter-edit';

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

const TWEET: TwitterPromulgation = {
  type: { typeId: 't', serviceId: 'twitter', name: 'Twitter' },
  promulgate: true,
  tweet: 'hello',
};

async function setup(context = makeContext()) {
  TestBed.configureTestingModule({ providers: [provideTestTransloco(), provideNiordIcons()] });
  const fixture = TestBed.createComponent(TwitterEdit);
  fixture.componentRef.setInput('promulgation', TWEET);
  fixture.componentRef.setInput('context', context);
  fixture.autoDetectChanges();
  await fixture.whenStable();
  return { fixture, el: fixture.nativeElement as HTMLElement, context };
}

describe('TwitterEdit', () => {
  it('caps the tweet textarea at 140 characters and binds the value', async () => {
    const { el } = await setup();
    const textarea = el.querySelector('textarea')!;
    expect(textarea.maxLength).toBe(140);
    expect(textarea.value).toBe('hello');
  });

  it('routes a tweet edit to context.patch({ tweet })', async () => {
    const { el, context } = await setup();
    const textarea = el.querySelector('textarea')!;
    textarea.value = 'updated';
    textarea.dispatchEvent(new Event('input'));
    expect(context.patch).toHaveBeenCalledWith('t', { tweet: 'updated' });
  });

  it('exposes only a Generate action (no Format)', async () => {
    const { el, context } = await setup();
    const buttons = el.querySelectorAll('button');
    expect(buttons.length).toBe(1);
    buttons[0].click();
    expect(context.generate).toHaveBeenCalledWith('t');
  });

  it('disables Generate while busy', async () => {
    const { el } = await setup(makeContext({ busy: vi.fn(() => true) }));
    expect(el.querySelector('button')!.disabled).toBe(true);
  });
});
