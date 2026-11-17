import type { DomainMessageSeriesRef } from '../../../core/domain/models/domain.model';
import type { Message, MessageSeries } from '../../messages/models/message.model';

/** Whether `series` permits `type` (no / empty `types` restriction permits every type). */
export function seriesPermitsType(series: DomainMessageSeriesRef, type: string): boolean {
  return !series.types || series.types.length === 0 || series.types.includes(type);
}

/** Resolve a series id to a full series object off the domain list. */
export function resolveDomainSeries(
  seriesId: string,
  domainSeries: readonly DomainMessageSeriesRef[],
): MessageSeries | undefined {
  const series = domainSeries.find((candidate) => candidate.seriesId === seriesId);
  if (!series) {
    return undefined;
  }
  return {
    seriesId: series.seriesId,
    mainType: series.mainType,
    types: series.types ? [...series.types] : undefined,
    numberSequenceType: series.numberSequenceType,
    shortFormat: series.shortFormat,
  };
}

/**
 * Replace a pruned `messageSeries` with the full domain series object (matched by `seriesId`), so
 * the dropdown shows it selected and the numbering config is available. Null-guarded: a message
 * with no series, or a series from another domain, is returned untouched. The original `types`
 * are kept when the domain series carries none.
 */
export function rehydrateMessageSeries(
  message: Message,
  domainSeries: readonly DomainMessageSeriesRef[],
): Message {
  const existing = message.messageSeries;
  if (!existing?.seriesId) {
    return message;
  }
  const resolved = resolveDomainSeries(existing.seriesId, domainSeries);
  if (!resolved) {
    return message;
  }
  return {
    ...message,
    messageSeries: { ...existing, ...resolved, types: resolved.types ?? existing.types },
  };
}

/** Short-id is hand-editable for a MANUAL series without a short-format. */
export function isShortIdEditable(series: MessageSeries | undefined): boolean {
  return !!series && series.numberSequenceType === 'MANUAL' && !series.shortFormat;
}

/** Message-number is hand-editable for a MANUAL series WITH a short-format. */
export function isMessageNumberEditable(series: MessageSeries | undefined): boolean {
  return !!series && series.numberSequenceType === 'MANUAL' && !!series.shortFormat;
}
