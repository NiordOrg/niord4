import { Area, DateInterval, Desc, Message } from './message.model';
import {
  computeAreaHeadings,
  featuresForMessage,
  filterByAreaIds,
  isActiveAt,
  rootAreaOf,
  sortDescs,
} from './message.util';

describe('message.util', () => {
  describe('rootAreaOf()', () => {
    it('should walk parent chain to root', () => {
      const root: Area = { id: 1 };
      const child: Area = { id: 2, parent: root };
      const leaf: Area = { id: 3, parent: child };
      expect(rootAreaOf(leaf)).toBe(root);
    });

    it('should return self when no parent', () => {
      const area: Area = { id: 1 };
      expect(rootAreaOf(area)).toBe(area);
    });

    it('should return undefined for undefined input', () => {
      expect(rootAreaOf(undefined)).toBeUndefined();
    });
  });

  describe('sortDescs()', () => {
    const mkMsg = (descs: Desc[]): Message => ({ id: '1', mainType: 'NW', descs });

    it('should reorder lang to front in msg.descs', () => {
      const msg = mkMsg([
        { lang: 'da', title: 'DA' },
        { lang: 'en', title: 'EN' },
      ]);
      expect(sortDescs(msg, 'en').descs?.[0].lang).toBe('en');
    });

    it('should also reorder area descs', () => {
      const msg: Message = {
        id: '1',
        mainType: 'NW',
        descs: [{ lang: 'en' }],
        areas: [{ id: 1, descs: [{ lang: 'da' }, { lang: 'en' }] }],
      };
      expect(sortDescs(msg, 'en').areas?.[0].descs?.[0].lang).toBe('en');
    });

    it('should handle message with no parts/refs/attachments', () => {
      expect(() => sortDescs(mkMsg([{ lang: 'en' }]), 'en')).not.toThrow();
    });

    it('should not mutate original message', () => {
      const msg = mkMsg([{ lang: 'da' }, { lang: 'en' }]);
      sortDescs(msg, 'en');
      expect(msg.descs?.[0].lang).toBe('da');
    });
  });

  describe('computeAreaHeadings()', () => {
    it('should return heading for first message of group', () => {
      const area: Area = { id: 10 };
      const msgs: Message[] = [
        { id: '1', mainType: 'NW', areas: [area] },
        { id: '2', mainType: 'NW', areas: [area] },
      ];
      const headings = computeAreaHeadings(msgs);
      expect(headings.get('1')).toBeDefined();
      expect(headings.has('2')).toBe(false);
    });

    it('should return headings for subsequent different areas', () => {
      const msgs: Message[] = [
        { id: '1', mainType: 'NW', areas: [{ id: 10 }] },
        { id: '2', mainType: 'NW', areas: [{ id: 11 }] },
      ];
      const headings = computeAreaHeadings(msgs);
      expect(headings.get('1')).toBeDefined();
      expect(headings.get('2')).toBeDefined();
    });

    it('should respect maxLevels with nested areas', () => {
      const root: Area = { id: 1 };
      const mid: Area = { id: 2, parent: root };
      const leaf: Area = { id: 3, parent: mid };
      const msgs: Message[] = [{ id: '1', mainType: 'NW', areas: [leaf] }];
      expect(computeAreaHeadings(msgs, 2).get('1')?.id).toBe(2);
    });

    it('should skip messages with no areas', () => {
      expect(computeAreaHeadings([{ id: '1', mainType: 'NW' }]).has('1')).toBe(false);
    });

    // An area key is a number, so the run that has already been named has to be told from
    // "nothing named yet" by more than truthiness — area 0 is a key like any other.
    it('names a run whose area key is zero exactly once', () => {
      const area: Area = { id: 0 };
      const msgs: Message[] = [
        { id: '1', mainType: 'NW', areas: [area] },
        { id: '2', mainType: 'NW', areas: [area] },
      ];
      const headings = computeAreaHeadings(msgs);
      expect(headings.has('1')).toBe(true);
      expect(headings.has('2')).toBe(false);
    });
  });

  describe('filterByAreaIds()', () => {
    it('should return all messages when the selection is empty', () => {
      const msgs: Message[] = [{ id: '1', mainType: 'NW' }];
      expect(filterByAreaIds(msgs, new Set())).toEqual(msgs);
    });

    it('should match direct area id', () => {
      const msgs: Message[] = [
        { id: '1', mainType: 'NW', areas: [{ id: 10 }] },
        { id: '2', mainType: 'NW', areas: [{ id: 11 }] },
      ];
      const result = filterByAreaIds(msgs, new Set([10]));
      expect(result).toHaveLength(1);
      expect(result[0].id).toBe('1');
    });

    it('should match parent lineage', () => {
      const parent: Area = { id: 1 };
      const child: Area = { id: 2, parent };
      const msgs: Message[] = [{ id: '1', mainType: 'NW', areas: [child] }];
      expect(filterByAreaIds(msgs, new Set([1]))).toHaveLength(1);
    });

    it('should match when message has multiple areas', () => {
      const msgs: Message[] = [{ id: '1', mainType: 'NW', areas: [{ id: 10 }, { id: 11 }] }];
      expect(filterByAreaIds(msgs, new Set([11]))).toHaveLength(1);
    });
  });

  describe('isActiveAt()', () => {
    const NOW = 1_800_000_000_000;
    const HOUR = 3_600_000;
    const withIntervals = (...parts: DateInterval[][]): Message => ({
      id: '1',
      mainType: 'NW',
      parts: parts.map((eventDates) => ({ type: 'DETAILS', eventDates })),
    });

    // A warning the data cannot judge is never hidden.
    it('counts a message without event dates as in force', () => {
      expect(isActiveAt({ id: '1', mainType: 'NW' }, NOW)).toBe(true);
      expect(isActiveAt({ id: '1', mainType: 'NW', parts: [{ type: 'DETAILS' }] }, NOW)).toBe(true);
      expect(isActiveAt(withIntervals([]), NOW)).toBe(true);
    });

    it('is in force inside an interval', () => {
      expect(isActiveAt(withIntervals([{ fromDate: NOW - HOUR, toDate: NOW + HOUR }]), NOW)).toBe(
        true,
      );
    });

    it('is not in force before its only interval starts', () => {
      expect(
        isActiveAt(withIntervals([{ fromDate: NOW + HOUR, toDate: NOW + 2 * HOUR }]), NOW),
      ).toBe(false);
    });

    it('is not in force after its only interval ended', () => {
      expect(
        isActiveAt(withIntervals([{ fromDate: NOW - 2 * HOUR, toDate: NOW - HOUR }]), NOW),
      ).toBe(false);
    });

    it('reads an open end as reaching as far as it likes', () => {
      expect(isActiveAt(withIntervals([{ fromDate: NOW - HOUR }]), NOW)).toBe(true);
      expect(isActiveAt(withIntervals([{ toDate: NOW + HOUR }]), NOW)).toBe(true);
      expect(isActiveAt(withIntervals([{ fromDate: NOW + HOUR }]), NOW)).toBe(false);
      expect(isActiveAt(withIntervals([{ toDate: NOW - HOUR }]), NOW)).toBe(false);
    });

    it('is in force when any interval of any part holds the instant', () => {
      expect(
        isActiveAt(
          withIntervals(
            [{ fromDate: NOW + HOUR, toDate: NOW + 2 * HOUR }],
            [
              { fromDate: NOW - 3 * HOUR, toDate: NOW - 2 * HOUR },
              { fromDate: NOW - HOUR, toDate: NOW + HOUR },
            ],
          ),
          NOW,
        ),
      ).toBe(true);
    });

    it('includes both ends of an interval', () => {
      expect(isActiveAt(withIntervals([{ fromDate: NOW, toDate: NOW + HOUR }]), NOW)).toBe(true);
      expect(isActiveAt(withIntervals([{ fromDate: NOW - HOUR, toDate: NOW }]), NOW)).toBe(true);
    });
  });

  describe('featuresForMessage()', () => {
    it('should extract features from parts', () => {
      const msg: Message = {
        id: '1',
        mainType: 'NW',
        parts: [
          {
            type: 'DETAILS',
            geometry: {
              type: 'FeatureCollection',
              features: [{ type: 'Feature', properties: {} }],
            },
          },
        ],
      };
      expect(featuresForMessage(msg)).toHaveLength(1);
    });

    it('should return empty for no parts', () => {
      expect(featuresForMessage({ id: '1', mainType: 'NW' })).toEqual([]);
    });

    it('should return empty for parts with no geometry', () => {
      expect(featuresForMessage({ id: '1', mainType: 'NW', parts: [{ type: 'DETAILS' }] })).toEqual(
        [],
      );
    });
  });
});
