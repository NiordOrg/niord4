import { TestBed } from '@angular/core/testing';
import { TranslocoService } from '@jsverse/transloco';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { provideTestTransloco } from '../../shared/testing/transloco-testing';
import { NotificationService } from './notification.service';

function setup(translations: Record<string, string> = {}) {
  TestBed.configureTestingModule({ providers: [provideTestTransloco()] });
  const transloco = TestBed.inject(TranslocoService);
  transloco.setTranslation(translations, 'en', { merge: true });
  return { service: TestBed.inject(NotificationService) };
}

describe('NotificationService', () => {
  afterEach(() => vi.useRealTimers());

  describe('convenience methods', () => {
    it('success() stores a translated summary with severity + 3000ms TTL', () => {
      const { service } = setup({ 'tag.added': 'Added' });
      service.success('tag.added');
      expect(service.messages()).toHaveLength(1);
      expect(service.messages()[0]).toMatchObject({
        severity: 'success',
        summary: 'Added',
        life: 3000,
      });
    });

    it('info() uses 3000ms TTL', () => {
      const { service } = setup();
      service.info('any.key');
      expect(service.messages()[0]).toMatchObject({ severity: 'info', life: 3000 });
    });

    it('warn() uses 5000ms TTL', () => {
      const { service } = setup();
      service.warn('any.key');
      expect(service.messages()[0]).toMatchObject({ severity: 'warn', life: 5000 });
    });

    it('error() uses 5000ms TTL', () => {
      const { service } = setup();
      service.error('any.key');
      expect(service.messages()[0]).toMatchObject({ severity: 'error', life: 5000 });
    });

    it('forwards interpolation params to Transloco', () => {
      const { service } = setup({ 'tag.added.named': 'Added message to {{name}}' });
      service.success('tag.added.named', { name: 'T1' });
      expect(service.messages()[0].summary).toBe('Added message to T1');
    });
  });

  describe('translation misses (locked decision: render the raw key)', () => {
    it('renders the raw key when the translation is missing', () => {
      const { service } = setup();
      service.error('this.key.does.not.exist');
      expect(service.messages()[0].summary).toBe('this.key.does.not.exist');
    });
  });

  describe('notify(opts)', () => {
    it('translates both summary and detail keys', () => {
      const { service } = setup({
        'err.summary': 'Failed',
        'err.detail': 'Something went wrong',
      });
      service.notify({ severity: 'error', summaryKey: 'err.summary', detailKey: 'err.detail' });
      expect(service.messages()[0]).toMatchObject({
        severity: 'error',
        summary: 'Failed',
        detail: 'Something went wrong',
        life: 5000,
      });
    });

    it('omits detail when detailKey is undefined', () => {
      const { service } = setup({ 'err.summary': 'Failed' });
      service.notify({ severity: 'error', summaryKey: 'err.summary' });
      expect(service.messages()[0].summary).toBe('Failed');
      expect(service.messages()[0].detail).toBeUndefined();
    });

    it('honors the life override', () => {
      const { service } = setup();
      service.notify({ severity: 'error', summaryKey: 'any', life: 12000 });
      expect(service.messages()[0].life).toBe(12000);
    });

    it('uses the severity default TTL when life is unset', () => {
      const { service } = setup();
      service.notify({ severity: 'success', summaryKey: 'any' });
      expect(service.messages()[0].life).toBe(3000);
    });

    it('forwards summaryParams and detailParams separately', () => {
      const { service } = setup({ 'with.summary': 'Hi {{who}}', 'with.detail': 'From {{from}}' });
      service.notify({
        severity: 'info',
        summaryKey: 'with.summary',
        summaryParams: { who: 'World' },
        detailKey: 'with.detail',
        detailParams: { from: 'Niord' },
      });
      expect(service.messages()[0]).toMatchObject({ summary: 'Hi World', detail: 'From Niord' });
    });
  });

  describe('lifecycle', () => {
    it('assigns each toast a distinct id and dismiss() removes only that toast', () => {
      const { service } = setup();
      service.info('a');
      service.info('b');
      const [first, second] = service.messages();
      expect(first.id).not.toBe(second.id);

      service.dismiss(first.id);
      expect(service.messages().map((m) => m.id)).toEqual([second.id]);
    });

    it('auto-dismisses after its life elapses; life 0 stays sticky', () => {
      vi.useFakeTimers();
      const { service } = setup();
      service.notify({ severity: 'success', summaryKey: 'a', life: 3000 });
      service.notify({ severity: 'error', summaryKey: 'b', life: 0 });
      expect(service.messages()).toHaveLength(2);

      vi.advanceTimersByTime(3000);
      const remaining = service.messages();
      expect(remaining).toHaveLength(1);
      expect(remaining[0].summary).toBe('b'); // the sticky one survives
    });
  });
});
