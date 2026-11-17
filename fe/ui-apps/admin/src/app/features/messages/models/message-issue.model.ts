/**
 * Where an issue is in its own lifecycle.
 *
 * Declared here rather than imported from the publications admin: a message
 * screen reaching into the admin feature for a type is the first thread of a
 * dependency that has no reason to exist. The union is three strings and the
 * backend owns it.
 */
export type MessageIssueStatus = 'OPEN' | 'PUBLISHED' | 'RETIRED';

/**
 * How a message came to be in a publication issue.
 *
 * `FROZEN` was recorded when the issue was published — a fact about a document
 * that exists. `LIVE` was resolved when the panel asked — what an open issue
 * would contain if it closed at that moment.
 *
 * The two must never render the same. A `LIVE` row can still change: the message
 * can be amended, withdrawn or excluded by a curator before the cut-off, and
 * showing it as an accomplished publication is how an editor decides not to
 * correct something that has not gone out yet.
 *
 * `MISSED` names an issue that was released over a period holding this
 * message's publish date, without the message: the message was published into
 * that period after its release. It is a warning, not a membership — the
 * message is in no document of that issue until an administrator amends it.
 *
 * The backend owns the union. A value this build does not know is drawn as
 * none of the three, never as one of them.
 */
export type IssueMembership = 'FROZEN' | 'LIVE' | 'MISSED';

/**
 * One publication series a message appears in, on one basis.
 *
 * A row is one SERIES, not one issue. An in-force series re-lists every message
 * still in force in every edition, so a notice in force for two years belongs to
 * a hundred-odd weekly issues of it — all saying the same thing. The issue fields
 * describe the most recent issue in the group and `issueCount` says how many
 * there were.
 *
 * Names arrive keyed by language rather than pre-resolved, as every localizable
 * payload in this API does: one response serves both UI languages. `names` is the
 * issue's own name ("EfS uge 29, 2026") and is usually the whole label; a series
 * whose naming pattern renders only a period needs `seriesNames` beside it to say
 * what the issue is an issue OF.
 */
export interface MessageIssueRef {
  readonly issuePublicId: string;
  readonly seriesId?: string;
  readonly status: MessageIssueStatus;
  /** Typed as the open string it is on the wire, so a newer value still reaches the panel. */
  readonly membership: IssueMembership | (string & {});
  /** How many issues of this series the message is in, on this basis. */
  readonly issueCount?: number;
  readonly intervalFrom?: string | number;
  readonly intervalTo?: string | number;
  readonly publishedAt?: string | number;
  /** lang → the issue's name. */
  readonly names?: Readonly<Record<string, string>>;
  /** lang → the series' name. */
  readonly seriesNames?: Readonly<Record<string, string>>;
  /** lang → the published document's link, where one exists. */
  readonly links?: Readonly<Record<string, string>>;
}
