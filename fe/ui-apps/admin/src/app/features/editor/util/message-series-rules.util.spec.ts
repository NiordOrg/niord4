import { describe, expect, it } from 'vitest';

import type { DomainMessageSeriesRef } from '../../../core/domain/models/domain.model';
import type { Message } from '../../messages/models/message.model';
import {
  isMessageNumberEditable,
  isShortIdEditable,
  rehydrateMessageSeries,
  resolveDomainSeries,
  seriesPermitsType,
} from './message-series-rules.util';

const domainSeries: DomainMessageSeriesRef[] = [
  { seriesId: 'manual', mainType: 'NW', numberSequenceType: 'MANUAL' },
  { seriesId: 'fmt', mainType: 'NW', numberSequenceType: 'MANUAL', shortFormat: 'NW-{}' },
  { seriesId: 'restricted', mainType: 'NW', types: ['LOCAL_WARNING'] },
];

describe('message series rules util', () => {
  describe('seriesPermitsType', () => {
    it('permits every type when there is no restriction', () => {
      expect(seriesPermitsType({ seriesId: 'a' }, 'ANY')).toBe(true);
    });

    it('honours a types restriction', () => {
      expect(seriesPermitsType({ seriesId: 'r', types: ['LOCAL_WARNING'] }, 'LOCAL_WARNING')).toBe(
        true,
      );
      expect(
        seriesPermitsType({ seriesId: 'r', types: ['LOCAL_WARNING'] }, 'COASTAL_WARNING'),
      ).toBe(false);
    });
  });

  describe('resolveDomainSeries', () => {
    it('resolves to a full series object', () => {
      expect(resolveDomainSeries('manual', domainSeries)?.numberSequenceType).toBe('MANUAL');
    });

    it('returns undefined for an unknown series', () => {
      expect(resolveDomainSeries('nope', domainSeries)).toBeUndefined();
    });
  });

  describe('rehydrateMessageSeries', () => {
    it('replaces a pruned series with the full domain object', () => {
      const message: Message = { id: 'm', messageSeries: { seriesId: 'fmt' } };
      expect(rehydrateMessageSeries(message, domainSeries).messageSeries?.shortFormat).toBe(
        'NW-{}',
      );
    });

    it('leaves a message with no series untouched (same reference)', () => {
      const message: Message = { id: 'm' };
      expect(rehydrateMessageSeries(message, domainSeries)).toBe(message);
    });

    it('leaves a series from another domain untouched (same reference)', () => {
      const message: Message = { id: 'm', messageSeries: { seriesId: 'foreign' } };
      expect(rehydrateMessageSeries(message, domainSeries)).toBe(message);
    });
  });

  describe('short-id editability', () => {
    it('MANUAL without shortFormat → short-id editable only', () => {
      const series = { seriesId: 'm', numberSequenceType: 'MANUAL' };
      expect(isShortIdEditable(series)).toBe(true);
      expect(isMessageNumberEditable(series)).toBe(false);
    });

    it('MANUAL with shortFormat → message-number editable only', () => {
      const series = { seriesId: 'm', numberSequenceType: 'MANUAL', shortFormat: 'X' };
      expect(isShortIdEditable(series)).toBe(false);
      expect(isMessageNumberEditable(series)).toBe(true);
    });

    it('non-MANUAL or absent → neither', () => {
      expect(isShortIdEditable({ seriesId: 'm', numberSequenceType: 'YEAR' })).toBe(false);
      expect(isMessageNumberEditable(undefined)).toBe(false);
    });
  });
});
