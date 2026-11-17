import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { TranslocoService } from '@jsverse/transloco';
import type { MessageStatus, PromulgationEditorContext } from '@niord/admin/api';
import { provideTestTransloco } from '@niord/admin/api/testing';
import { describe, expect, it, vi } from 'vitest';

import english from '../../assets/branding/en.json';
import { BulletinEdit } from './bulletin-edit';
import type { BulletinPromulgation } from './bulletin-view';

/** The example's English branding. */
const branding: Readonly<Record<string, string>> = english;

const bulletin = {
  type: { typeId: 'xx-bulletin', serviceId: 'xx-bulletin', name: 'Harbour bulletin' },
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

function render(
  promulgation: BulletinPromulgation,
  context: PromulgationEditorContext,
): HTMLTextAreaElement {
  TestBed.configureTestingModule({ providers: [provideTestTransloco()] });
  TestBed.inject(TranslocoService).setTranslation(branding, 'en');
  const fixture = TestBed.createComponent(BulletinEdit);
  fixture.componentRef.setInput('promulgation', promulgation);
  fixture.componentRef.setInput('context', context);
  fixture.detectChanges();
  return (fixture.nativeElement as HTMLElement).querySelector('textarea') as HTMLTextAreaElement;
}

describe('BulletinEdit (example country)', () => {
  it("shows the bulletin's text, labelled by the branding", () => {
    const textarea = render({ ...bulletin, text: 'Quay 3 closed' }, makeContext());

    expect(textarea.value).toBe('Quay 3 closed');
    expect(textarea.getAttribute('aria-label')).toBe(branding['country.bulletin.text']);
  });

  it("patches the text through the context for the entry's typeId", () => {
    const context = makeContext();
    const textarea = render({ ...bulletin }, context);

    textarea.value = 'Quay 4 open';
    textarea.dispatchEvent(new Event('input'));

    expect(context.patch).toHaveBeenCalledWith('xx-bulletin', { text: 'Quay 4 open' });
  });

  it('disables the textarea while the context is busy', () => {
    expect(render({ ...bulletin }, makeContext({ busy: () => true })).disabled).toBe(true);
  });
});
