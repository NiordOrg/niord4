import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { Observable, of } from 'rxjs';

import { MessageTagsField } from './message-tags-field';
import { MESSAGE_TAGS_DATA_SOURCE } from './message-tags-data.token';
import { MESSAGE_TAGS_DIALOG_OPENER } from './message-tags-dialog.token';
import { provideTestTransloco } from '../../testing/transloco-testing';
import type { MessageTag } from '../../models/message-tag.model';

function mkTag(tagId: string, name: string): MessageTag {
  return { tagId, name, type: 'PRIVATE', messageCount: 0, locked: false };
}

interface SetupOptions {
  readonly dialogResult?: MessageTag | undefined;
  readonly searchResult?: readonly MessageTag[];
}

function setup(opts: SetupOptions = {}) {
  const search = vi.fn(() => of(opts.searchResult ?? []));
  const searchScoped = vi.fn(() => of(opts.searchResult ?? []));
  const resolveByIds = vi.fn(() => of([] as MessageTag[]));
  const open = vi.fn(
    (): Observable<MessageTag | undefined> => of(opts.dialogResult as MessageTag | undefined),
  );

  TestBed.configureTestingModule({
    providers: [
      provideTestTransloco(),
      { provide: MESSAGE_TAGS_DATA_SOURCE, useValue: { search, searchScoped, resolveByIds } },
      { provide: MESSAGE_TAGS_DIALOG_OPENER, useValue: { open } },
    ],
  });

  const fixture = TestBed.createComponent(MessageTagsField);
  fixture.detectChanges();
  const comp = fixture.componentInstance;

  return { fixture, comp, search, resolveByIds, open };
}

describe('MessageTagsField', () => {
  it('renders the tags magnifier button (shared control) and clicking it runs the dialog flow', () => {
    const tag = mkTag('t9', 'From dialog');
    const { fixture, comp, open } = setup({ dialogResult: tag });

    const button = (fixture.nativeElement as HTMLElement).querySelector(
      'button.icon-button',
    ) as HTMLButtonElement | null;
    expect(button).not.toBeNull();
    expect(button?.classList.contains('text-xs')).toBe(true);
    expect(button?.getAttribute('aria-label')).toBe('messages.filter.tag.openDialogTooltip');
    expect(button?.getAttribute('title')).toBe('messages.filter.tag.openDialogTooltip');

    button?.click();

    expect(open).toHaveBeenCalledWith({ includeLocked: false });
    expect(comp.value()).toEqual([tag]);
  });

  it('dialog magnifier opens with includeLocked from input (default false)', () => {
    const { comp, open } = setup({ dialogResult: undefined });
    (comp as unknown as { openDialog: () => void }).openDialog();
    expect(open).toHaveBeenCalledTimes(1);
    expect(open).toHaveBeenCalledWith({ includeLocked: false });
  });

  it('dialog returning a tag appends it to value', () => {
    const tag = mkTag('t1', 'Tag 1');
    const { comp, open } = setup({ dialogResult: tag });

    (comp as unknown as { openDialog: () => void }).openDialog();

    expect(open).toHaveBeenCalled();
    expect(comp.value()).toEqual([tag]);
  });

  it('dialog dismiss does not change value', () => {
    const { comp, open } = setup({ dialogResult: undefined });

    comp.value.set([mkTag('existing', 'Existing')]);
    (comp as unknown as { openDialog: () => void }).openDialog();

    expect(open).toHaveBeenCalled();
    expect(comp.value()).toEqual([mkTag('existing', 'Existing')]);
  });

  it('dialog returning a tag already present does not duplicate', () => {
    const existing = mkTag('t1', 'Tag 1');
    const { comp } = setup({ dialogResult: existing });
    comp.value.set([existing]);

    (comp as unknown as { openDialog: () => void }).openDialog();

    expect(comp.value()).toEqual([existing]);
  });

  it('idOf maps the tag to its tagId', () => {
    const { comp } = setup();
    expect((comp as unknown as { idOf: (t: MessageTag) => string }).idOf(mkTag('abc', 'X'))).toBe(
      'abc',
    );
  });
});
