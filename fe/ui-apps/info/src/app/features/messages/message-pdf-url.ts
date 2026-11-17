/**
 * The addresses of the two PDFs the backend renders for this site.
 *
 * Both are same-origin `/rest/message-reports/…` paths and both answer anonymously, so the
 * reader is simply sent to one in a new tab; nothing is fetched, parsed or rendered here.
 *
 * The list endpoint takes the **administration** search parameter names, not the public API's:
 * `lang` for the language, as the public API has it too, but `area` where a public read says
 * `areaId`. It also has no equivalent of the public API's "only the message series of the
 * published domains" rule, so a bare call renders every published message in the area —
 * notices from domains this site does not serve included. Naming the series the reader is
 * actually looking at is what keeps the sheet and the screen the same list.
 *
 * The list names the backend's `public` report: the standard layout without the front page the
 * editors' prints open with — a search-criteria dump in English, with internal ids — so the sheet
 * opens with the notices themselves. Nothing names a page size or an orientation: the backend's
 * own default is portrait A4, the layout the printed notices have always used, and offering a
 * choice would mean asking the reader a question in words this site does not have. One message
 * names no report; the standard one prints it without a front page already.
 *
 * The site's container lets these two paths through with no query but the one written here, the
 * `public` report included — the two `map $args` blocks of `nginx/default.conf.template` — so a
 * parameter added here is added there in the same change. `scripts/info-print-query.test.mjs`
 * runs these functions against those maps and fails until it is.
 */

const REPORTS = '/rest/message-reports';

/** The report the backend defines for this site's printed lists. */
const PUBLIC_REPORT = 'public';

/** What a printed list is: the reader's filter, in the names the report endpoint reads. */
export interface MessageListPdfFilter {
  /** The language the reader is reading in. */
  readonly language: string;
  /** `NW`, `NM`, or both — empty means the endpoint's own answer, which is both. */
  readonly mainTypes: readonly string[];
  /** The ticked sub-areas if there are any, otherwise the chosen root area. */
  readonly areaIds: readonly string[];
  /** The message series the visible list came from; empty leaves the sheet unnarrowed. */
  readonly messageSeriesIds: readonly string[];
  /** The publication whose messages the list is, when the reader is browsing one. */
  readonly publicationId?: string;
}

/** The PDF of a filtered message list. */
export function messageListPdfUrl(filter: MessageListPdfFilter): string {
  const params = new URLSearchParams();
  params.set('lang', filter.language);
  params.set('report', PUBLIC_REPORT);
  if (filter.publicationId) params.set('publication', filter.publicationId);
  for (const mainType of filter.mainTypes) params.append('mainType', mainType);
  for (const areaId of filter.areaIds) params.append('area', areaId);
  for (const seriesId of filter.messageSeriesIds) params.append('messageSeries', seriesId);
  return `${REPORTS}/report.pdf?${params.toString()}`;
}

/**
 * The PDF of one message. The id sits in the path in front of the `.pdf` suffix, so it is
 * encoded: a short id can carry a space, and a reference followed from another message is a
 * short id rather than a UUID. The container refuses an id whose encoding carries a slash or a
 * percent sign (`%2F`, `%25`) in this path; no UUID and no short id has either.
 */
export function messagePdfUrl(messageId: string, language: string): string {
  const params = new URLSearchParams({ lang: language });
  return `${REPORTS}/message/${encodeURIComponent(messageId)}.pdf?${params.toString()}`;
}
