import { describe, expect, it } from 'vitest';

import type { Message } from '../../messages/models/message.model';
import { applyAdjustment } from './message-adjust.util';

describe('applyAdjustment (autoTitle title copy-back)', () => {
  const server: Message = {
    id: 's',
    descs: [{ lang: 'en', title: 'new', source: 'newsrc' }],
  };

  it('copies the title but NEVER the source when autoTitle is on (source is not auto-generated)', () => {
    const working: Message = {
      id: 'm',
      autoTitle: true,
      descs: [{ lang: 'en', title: 'old', source: 'oldsrc' }],
    };
    const result = applyAdjustment(working, server);
    expect(result.descs?.[0].title).toBe('new');
    expect(result.descs?.[0].source).toBe('oldsrc'); // source is preserved, not copied
  });

  it('refreshes nothing when autoTitle is off', () => {
    const working: Message = {
      id: 'm',
      autoTitle: false,
      descs: [{ lang: 'en', title: 'keep', source: 'keepsrc' }],
    };
    const result = applyAdjustment(working, server);
    expect(result.descs?.[0].title).toBe('keep');
    expect(result.descs?.[0].source).toBe('keepsrc');
  });

  it('leaves a working-only language untouched (per-language guard)', () => {
    const working: Message = {
      id: 'm',
      autoTitle: true,
      descs: [
        { lang: 'en', title: 'old-en' },
        { lang: 'da', title: 'old-da' },
      ],
    };
    const result = applyAdjustment(working, server);
    expect(result.descs?.find((d) => d.lang === 'en')?.title).toBe('new');
    expect(result.descs?.find((d) => d.lang === 'da')?.title).toBe('old-da');
  });

  it('copies the server shortId only when the message number is hand-editable', () => {
    const editable: Message = {
      id: 'm',
      messageSeries: { seriesId: 's', numberSequenceType: 'MANUAL', shortFormat: 'NW-{}' },
      shortId: 'old',
    };
    expect(applyAdjustment(editable, { id: 's', shortId: 'srv' }).shortId).toBe('srv');

    const fixed: Message = { id: 'm', messageSeries: { seriesId: 's' }, shortId: 'old' };
    expect(applyAdjustment(fixed, { id: 's', shortId: 'srv' }).shortId).toBe('old');
  });

  it('always follows the server editorFields visibility map', () => {
    const working: Message = { id: 'm', editorFields: { charts: false } };
    const server: Message = { id: 's', editorFields: { charts: true, source: true } };
    expect(applyAdjustment(working, server).editorFields).toBe(server.editorFields);

    const mapless: Message = { id: 's' };
    expect(applyAdjustment(working, mapless).editorFields).toEqual({ charts: false });
  });

  it('copies promulgations only when requested', () => {
    const working: Message = { id: 'm', promulgations: [] };
    const withProms: Message = {
      id: 's',
      promulgations: [{ type: { typeId: 't', serviceId: 't', name: 'T' }, promulgate: true }],
    };
    expect(applyAdjustment(working, withProms).promulgations).toEqual([]);
    expect(applyAdjustment(working, withProms, { inclPromulgations: true }).promulgations).toBe(
      withProms.promulgations,
    );
  });
});
