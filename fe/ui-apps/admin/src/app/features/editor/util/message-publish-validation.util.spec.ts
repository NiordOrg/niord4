import { describe, expect, it } from 'vitest';

import type { Message } from '../../messages/models/message.model';
import { canPublish } from './message-publish-validation.util';

const base: Message = {
  id: 'm',
  type: 'COASTAL_WARNING',
  messageSeries: { seriesId: 's' },
  descs: [{ lang: 'en', title: 'A title' }],
};

describe('canPublish', () => {
  it('is ok when type + series + title are present and no MANDATORY promulgation is unset', () => {
    const result = canPublish(base);
    expect(result.ok).toBe(true);
    expect(result.missingFieldKeys).toEqual([]);
    expect(result.missingPromulgations).toEqual([]);
  });

  it('flags a missing type', () => {
    const result = canPublish({ ...base, type: undefined });
    expect(result.ok).toBe(false);
    expect(result.missingFieldKeys).toContain('editor.status.field.type');
  });

  it('flags a missing series', () => {
    expect(canPublish({ ...base, messageSeries: undefined }).missingFieldKeys).toContain(
      'editor.status.field.series',
    );
  });

  it('flags a missing or whitespace-only title', () => {
    expect(canPublish({ ...base, descs: [] }).missingFieldKeys).toContain(
      'editor.status.field.title',
    );
    expect(
      canPublish({ ...base, descs: [{ lang: 'en', title: '   ' }] }).missingFieldKeys,
    ).toContain('editor.status.field.title');
  });

  it('flags each unset MANDATORY promulgation by name (OPTIONAL / set ones are fine)', () => {
    const result = canPublish({
      ...base,
      promulgations: [
        {
          type: { typeId: 'navtex', serviceId: 'navtex', name: 'NAVTEX', requirement: 'MANDATORY' },
          promulgate: false,
        },
        {
          type: { typeId: 'tw', serviceId: 'tw', name: 'Twitter', requirement: 'OPTIONAL' },
          promulgate: false,
        },
        {
          type: { typeId: 'audio', serviceId: 'audio', name: 'Audio', requirement: 'MANDATORY' },
          promulgate: true,
        },
      ],
    });
    expect(result.ok).toBe(false);
    expect(result.missingPromulgations).toEqual(['NAVTEX']);
  });

  it('is ok when there are no promulgations at all', () => {
    expect(canPublish({ ...base, promulgations: [] }).ok).toBe(true);
  });
});
