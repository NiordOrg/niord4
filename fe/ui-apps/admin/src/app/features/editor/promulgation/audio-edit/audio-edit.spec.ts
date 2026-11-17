import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { describe, expect, it, vi } from 'vitest';

import { provideTestTransloco } from '../../../../shared/testing/transloco-testing';
import type { MessageStatus } from '../../../messages/models/message.model';
import type { AudioPromulgation } from '../../../messages/promulgation/audio-view/audio-promulgation.model';
import type { PromulgationEditorContext } from '../promulgation-editor.token';
import { AudioEdit } from './audio-edit';

const base = {
  type: { typeId: 'audio', serviceId: 'audio', name: 'Audio' },
  promulgate: true,
} as const;

function makeContext(
  overrides: Partial<PromulgationEditorContext> = {},
): PromulgationEditorContext {
  return {
    patch: vi.fn(),
    generate: vi.fn(),
    reformat: vi.fn(),
    busy: () => false,
    messageStatus: signal<MessageStatus | undefined>('DRAFT'),
    ...overrides,
  };
}

function setup(promulgation: AudioPromulgation, context: PromulgationEditorContext) {
  TestBed.configureTestingModule({ providers: [provideTestTransloco()] });
  const fixture = TestBed.createComponent(AudioEdit);
  fixture.componentRef.setInput('promulgation', promulgation);
  fixture.componentRef.setInput('context', context);
  fixture.detectChanges();
  return fixture;
}

describe('AudioEdit', () => {
  it('reflects the promulgation text in the textarea', () => {
    const fixture = setup({ ...base, text: 'Broadcast script' }, makeContext());
    const textarea: HTMLTextAreaElement = fixture.nativeElement.querySelector('textarea');
    expect(textarea.value).toBe('Broadcast script');
  });

  it('patches text through the context on input (single write path)', () => {
    const context = makeContext();
    const fixture = setup({ ...base, text: '' }, context);
    const textarea: HTMLTextAreaElement = fixture.nativeElement.querySelector('textarea');
    textarea.value = 'Updated broadcast';
    textarea.dispatchEvent(new Event('input'));
    expect(context.patch).toHaveBeenCalledWith('audio', { text: 'Updated broadcast' });
  });

  it('fires generate through the context for the entry typeId', () => {
    const context = makeContext();
    const fixture = setup({ ...base }, context);
    const button: HTMLButtonElement = fixture.nativeElement.querySelector('button');
    button.click();
    expect(context.generate).toHaveBeenCalledWith('audio');
  });

  it('disables the textarea and the Generate button while the context is busy', () => {
    const fixture = setup({ ...base }, makeContext({ busy: () => true }));
    const textarea: HTMLTextAreaElement = fixture.nativeElement.querySelector('textarea');
    const button: HTMLButtonElement = fixture.nativeElement.querySelector('button');
    expect(textarea.disabled).toBe(true);
    expect(button.disabled).toBe(true);
  });
});
