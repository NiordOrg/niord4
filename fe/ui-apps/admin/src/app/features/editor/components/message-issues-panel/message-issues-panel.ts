import { httpResource } from '@angular/common/http';
import { Component, computed, inject, input } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';

import { ApiUrlResolver } from '../../../../core/config/absolute-api-url';
import { APP_CONFIG } from '../../../../core/config/app-config.token';
import { LanguageService } from '../../../../core/main-shell/services/language.service';
import { ErrorBanner } from '../../../../shared/components/error-banner/error-banner';
import { ProgressBar } from '../../../../shared/components/progress-bar/progress-bar';
import { Icon } from '../../../../shared/icons/icon';
import { LocaleDatePipe } from '../../../../shared/pipes/locale-date.pipe';
import type { MessageIssueRef } from '../../../messages/models/message-issue.model';

/**
 * What a row claims about the message's membership.
 *
 * `published` is a document that exists and is on the site; `withdrawn` is one
 * that existed and has been taken down — an issue can be retired after release,
 * and a message frozen into it is no longer out there. `pending` is a
 * resolution made when the panel loaded and can still change. `missed` is a
 * released issue whose period holds the message's publish date and which went
 * out without it: a warning, since an amend of that issue is the only way in.
 * `unknown` is a membership this build has no word for, and claims nothing.
 */
type MembershipState = 'published' | 'withdrawn' | 'pending' | 'missed' | 'unknown';

/**
 * The badge tint and label for each claim; `unknown` has none.
 *
 * The missed badge wears the warning tint the publication screens use for a
 * missing issue: something is wrong and can be acted on.
 */
const MEMBERSHIP_BADGE: Readonly<
  Record<Exclude<MembershipState, 'unknown'>, { class: string; key: string }>
> = {
  published: { class: 'is-issue-published', key: 'editor.status.publications.published' },
  // No state class: a withdrawn issue is neither settled-green nor pending-draft,
  // and the neutral badge is what the rest of the app uses for exactly that.
  withdrawn: { class: '', key: 'editor.status.publications.withdrawn' },
  pending: { class: 'is-issue-open', key: 'editor.status.publications.pending' },
  missed: { class: 'is-gap-MISSING', key: 'editor.status.publications.missed' },
};

/** One series row, with its label already resolved for the active language. */
interface IssueRow {
  readonly key: string;
  readonly label: string;
  readonly seriesName?: string;
  readonly link?: string;
  readonly state: MembershipState;
  readonly badgeClass: string;
  /** Absent for a membership this build does not know: no badge is drawn. */
  readonly badgeKey?: string;
  readonly stamp?: string | number;
  /** Set only when the row stands for more than one issue. */
  readonly issueCount?: number;
}

/**
 * Which publication issues this message is in — the inverse of an issue's member list.
 *
 * The question an editor asks before amending a message is "has this gone out
 * yet, and where", and until now the only way to answer it was to open every
 * issue in the publications admin and read its members — a question asked
 * constantly, answerable only with admin rights.
 *
 * The row kinds are not styles of the same claim. A `FROZEN` row is a record:
 * the issue was published with this message in it, and that cannot change — but
 * the issue itself can be retired afterwards, and then the document is off the
 * site, so a frozen row reads as withdrawn rather than as published. A `LIVE`
 * row is a resolution made when the panel loaded: what an open issue would
 * contain if it closed now. It can still change — the message can be amended,
 * withdrawn, or excluded by a curator before the cut-off — so it is labelled as
 * pending rather than as an accomplished publication. A `MISSED` row is an issue
 * released over a period that holds the message's publish date, without it: the
 * message was published into the period after its release, so it is in no
 * document of that issue, and the row is a warning with a hint on how an
 * administrator takes it in. Collapsing any of these would tell an editor a
 * correction is too late when it is not, or that a notice is on the site when
 * it is not.
 *
 * One row is one SERIES on one basis. An in-force series re-lists every message
 * still in force in every edition, so a long-lived notice belongs to a hundred-odd
 * weekly issues of it; the backend collapses those into one counted row and this
 * shows the most recent of them.
 */
@Component({
  selector: 'app-message-issues-panel',
  imports: [TranslocoPipe, LocaleDatePipe, Icon, ErrorBanner, ProgressBar],
  template: `
    <div class="flex flex-col gap-3 p-4">
      <!--
        Three states, told apart by shape and not only by wording. "Could not
        load" and "not in any publication" read alike as two identical grey
        paragraphs — and telling those two apart is the one thing this panel
        exists to do, so the failure gets a banner with a way out of it and the
        wait gets a progress bar.
      -->
      @if (resource.error()) {
        <app-error-banner
          [title]="'editor.status.publications.error' | transloco"
          [retryLabel]="'editor.load.retry' | transloco"
          (retry)="reload()"
        />
      } @else if (resource.isLoading()) {
        <app-progress-bar [visible]="true" />
        <p class="text-sm text-fg-muted">{{ 'editor.status.publications.loading' | transloco }}</p>
      } @else if (rows().length === 0) {
        <p class="text-sm text-fg-muted">{{ 'editor.status.publications.empty' | transloco }}</p>
      } @else {
        <ul class="flex flex-col gap-3">
          @for (row of rows(); track row.key) {
            <li class="flex flex-col gap-1">
              <span class="flex items-center gap-2">
                @if (row.badgeKey) {
                  <span class="status-badge" [class]="row.badgeClass">
                    {{ row.badgeKey | transloco }}
                  </span>
                }
                @if (row.link) {
                  <a
                    class="min-w-0 truncate text-sm font-medium text-primary hover:underline"
                    [href]="row.link"
                    target="_blank"
                    rel="noopener"
                    >{{ row.label }}</a
                  >
                  <app-icon name="lucideExternalLink" class="shrink-0 text-fg-subtle" />
                } @else {
                  <span class="min-w-0 truncate text-sm font-medium text-fg">{{ row.label }}</span>
                }
              </span>
              <span class="text-xs text-fg-subtle">
                @if (row.seriesName) {
                  {{ row.seriesName }}
                }
                @if (row.stamp) {
                  · {{ row.stamp | localeDate: 'date' }}
                }
                @if (row.issueCount) {
                  ·
                  {{
                    'editor.status.publications.issueCount' | transloco: { count: row.issueCount }
                  }}
                }
              </span>
            </li>
          }
        </ul>
        @if (hasPending()) {
          <p class="text-xs text-fg-subtle">
            {{ 'editor.status.publications.pendingHint' | transloco }}
          </p>
        }
        @if (hasMissed()) {
          <p class="text-xs text-fg-subtle">
            {{ 'editor.status.publications.missedHint' | transloco }}
          </p>
        }
      }
    </div>
  `,
})
export class MessageIssuesPanel {
  /** The message's uid. Absent while the message is still loading, or for an unsaved draft. */
  readonly messageUid = input<string>();

  private readonly apiBaseUrl = inject(APP_CONFIG).apiBaseUrl;
  private readonly language = inject(LanguageService);
  private readonly apiUrls = inject(ApiUrlResolver);

  // No uid, no request: an unsaved message has no membership anywhere, and asking
  // about a blank one would be a round-trip that can only answer "nothing".
  protected readonly resource = httpResource<readonly MessageIssueRef[]>(
    () => {
      const uid = this.messageUid();
      return uid
        ? `${this.apiBaseUrl}/publication-issues/by-message/${encodeURIComponent(uid)}`
        : undefined;
    },
    { defaultValue: [] },
  );

  // value() throws in the resource's error state, so the read is guarded by hasValue().
  private readonly issues = computed<readonly MessageIssueRef[]>(() =>
    this.resource.hasValue() ? this.resource.value() : [],
  );

  protected readonly rows = computed<readonly IssueRow[]>(() => {
    const lang = this.language.selectedLanguage();
    return this.issues().map((issue) => {
      const frozen = issue.membership === 'FROZEN';
      const state = membershipState(issue);
      const badge = state === 'unknown' ? undefined : MEMBERSHIP_BADGE[state];
      return {
        key: `${issue.seriesId ?? issue.issuePublicId}/${issue.membership}`,
        // The issue's own name is the label; its id is the fallback rather than a
        // placeholder, because an id is still something an editor can search on.
        label: pick(issue.names, lang) ?? issue.issuePublicId,
        seriesName: pick(issue.seriesNames, lang),
        // Absolute, because the address is reported root-relative from the host
        // that serves the document and the editor is not always on that host.
        // Only a frozen row is in the document: a missed issue's document is
        // exactly the one that does not carry the message.
        link: frozen ? this.documentLink(pick(issue.links, lang)) : undefined,
        state,
        badgeClass: badge?.class ?? '',
        badgeKey: badge?.key,
        // A published issue is dated by when it came out; any other row by when
        // its period opened — for an open issue the only date it has, and for a
        // missed one the start of the period the message's date lies in.
        stamp: frozen ? (issue.publishedAt ?? issue.intervalFrom) : issue.intervalFrom,
        // Shown only when the row stands for more than one issue: "1 issue" beside
        // a row that already names that issue is a word that says nothing.
        issueCount: (issue.issueCount ?? 1) > 1 ? issue.issueCount : undefined,
      };
    });
  });

  protected readonly hasPending = computed(() =>
    this.rows().some((row) => row.state === 'pending'),
  );

  protected readonly hasMissed = computed(() => this.rows().some((row) => row.state === 'missed'));

  /** One issue's document address, followable from wherever the editor is served. */
  private documentLink(link: string | undefined): string | undefined {
    return link === undefined ? undefined : this.apiUrls.absoluteApiUrl(link);
  }

  /** Re-ask after a failed load. The panel's only recovery, and it needs a control. */
  protected reload(): void {
    this.resource.reload();
  }
}

/**
 * What one row claims, from its membership and its issue's status.
 *
 * Membership and status are separate fields on purpose: a message can be frozen
 * into an issue that has since been retired, and reading only the membership
 * would badge that as published — telling an editor a notice is on the site
 * when it was taken down.
 *
 * Each membership is named; nothing falls through to `pending`. The panel once
 * drew every row that was not frozen as pending, which is how a new value from
 * the server would have read as "will be in this issue".
 */
function membershipState(issue: MessageIssueRef): MembershipState {
  switch (issue.membership) {
    case 'FROZEN':
      return issue.status === 'RETIRED' ? 'withdrawn' : 'published';
    case 'LIVE':
      return 'pending';
    case 'MISSED':
      return 'missed';
    default:
      return 'unknown';
  }
}

/**
 * The value for the active language, falling back to any other.
 *
 * A fallback rather than a blank: an issue named in only one language is still an
 * issue this message is in, and dropping the row's label to show nothing would
 * hide a real publication behind a translation gap.
 */
function pick(
  byLang: Readonly<Record<string, string>> | undefined,
  lang: string,
): string | undefined {
  if (!byLang) {
    return undefined;
  }
  return byLang[lang] ?? Object.values(byLang).find((value) => !!value);
}
