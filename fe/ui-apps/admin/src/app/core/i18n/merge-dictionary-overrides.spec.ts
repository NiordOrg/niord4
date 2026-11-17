import type { Translation } from '@jsverse/transloco';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { mergeDictionaryOverrides } from './merge-dictionary-overrides';

describe('mergeDictionaryOverrides', () => {
  beforeEach(() => {
    // The dev-only diagnostic warns on dropped overrides — silence it in tests.
    vi.spyOn(console, 'warn').mockImplementation(() => undefined);
  });

  it('keeps the compiled value when an override drops a placeholder (different param name)', () => {
    const base: Translation = { 'msg.list.load_more': 'Load {{from}} - {{to}} of {{total}}' };
    const merged = mergeDictionaryOverrides(base, {
      // Dictionary param names — none match the compiled ones.
      'msg.list.load_more': 'Load {{fromMessageNo}} - {{toMessageNo}} of {{totalMessageNo}}',
    });
    expect(merged['msg.list.load_more']).toBe('Load {{from}} - {{to}} of {{total}}');
  });

  it('keeps the compiled value when an override is a plain label with no placeholder', () => {
    const base: Translation = { 'msg.field.published': 'Published {{date}}' };
    const merged = mergeDictionaryOverrides(base, { 'msg.field.published': 'Published' });
    expect(merged['msg.field.published']).toBe('Published {{date}}');
  });

  it('lets an override win when it preserves every compiled placeholder', () => {
    const base: Translation = { 'menu.action.tag.add_named': 'Add to {{name}}' };
    const merged = mergeDictionaryOverrides(base, {
      'menu.action.tag.add_named': 'Add message to "{{name}}"',
    });
    expect(merged['menu.action.tag.add_named']).toBe('Add message to "{{name}}"');
  });

  it('lets an override win when the base has no placeholders (plain relabel)', () => {
    const base: Translation = { 'menu.action.print': 'Print' };
    const merged = mergeDictionaryOverrides(base, { 'menu.action.print': 'Print report' });
    expect(merged['menu.action.print']).toBe('Print report');
  });

  it('keeps the compiled value when an override adds a placeholder the base does not have', () => {
    const base: Translation = { greeting: 'Hi {{name}}' };
    const merged = mergeDictionaryOverrides(base, { greeting: 'Hi {{name}} ({{role}})' });
    // The app passes no `role` for this key, so the override would render "Hi Ann ()".
    expect(merged['greeting']).toBe('Hi {{name}}');
  });

  it('keeps a plain compiled label over an override that carries a token (the old key meaning)', () => {
    // The old application named one tag with this key; the app uses it as a submenu heading and
    // passes nothing, so the override would read 'Remove from ""'.
    const base: Translation = { 'menu.action.tag.remove_named': 'Remove from…' };
    const merged = mergeDictionaryOverrides(base, {
      'menu.action.tag.remove_named': 'Remove from "{{tag}}"',
    });
    expect(merged['menu.action.tag.remove_named']).toBe('Remove from…');
  });

  it('names every refused key in the development warning', () => {
    const warn = vi.mocked(console.warn);
    warn.mockClear();
    mergeDictionaryOverrides(
      {
        'msg.field.published': 'Published {{date}}',
        'menu.action.tag.remove_named': 'Remove from…',
      },
      {
        'msg.field.published': 'Published',
        'menu.action.tag.remove_named': 'Remove from "{{tag}}"',
      },
    );
    expect(warn).toHaveBeenCalledTimes(1);
    expect(warn.mock.calls[0][0]).toContain('msg.field.published, menu.action.tag.remove_named');
  });

  it('resolves a nested compiled key when guarding a dotted override key', () => {
    const base = { editor: { section: { message_part: 'Part {{n}}' } } } as unknown as Translation;
    const merged = mergeDictionaryOverrides(base, {
      'editor.section.message_part': 'Part {{part}}',
    }) as Record<string, unknown>;
    // The dropped override is NOT added as a flat key; the nested base value is left intact.
    expect(merged['editor.section.message_part']).toBeUndefined();
    expect((merged['editor'] as { section: { message_part: string } }).section.message_part).toBe(
      'Part {{n}}',
    );
  });

  it('keeps an override for a key that does not exist in the base bundle', () => {
    const base: Translation = {};
    const merged = mergeDictionaryOverrides(base, { 'brand.tagline': 'Sail safe' });
    expect(merged['brand.tagline']).toBe('Sail safe');
  });
});
