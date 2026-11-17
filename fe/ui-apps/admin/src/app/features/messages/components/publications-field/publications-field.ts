import { Component, inject, input, signal } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';
import { Observable, map, tap } from 'rxjs';

import { LanguageService } from '../../../../core/main-shell/services/language.service';
import { EntityMultiselectControl } from '../../../../shared/forms/entity-multiselect-control/entity-multiselect-control';
import { EntityMultiselectField } from '../../../../shared/forms/entity-multiselect-field';
import { LocaleDatePipe } from '../../../../shared/pipes/locale-date.pipe';
import type {
  PublicationDescription,
  PublicationRef,
} from '../../models/messages-filter-params.model';
import { PUBLICATION_PICKER_STATUSES } from '../../models/messages-filter-params.model';
import { PublicationsApiService } from '../../services/api/publications-api.service';

/** The badge tint for each issue status; a withdrawn one gets the neutral badge. */
const STATUS_CLASS: Readonly<Record<string, string>> = {
  OPEN: 'is-issue-open',
  PUBLISHED: 'is-issue-published',
  RETIRED: '',
};

/** How many rows one browse page holds. Paged, not a window: `page` fetches the rest. */
const BROWSE_PAGE_SIZE = 50;

/**
 * The publication picker: a chip combobox over both halves of the publication
 * catalogue, with a status badge, the series it belongs to and the date it went out
 * on every row.
 *
 * The badge is the reason the rows are not plain labels. Publications are no longer a
 * flat list of things that exist — an issue is open, out, or withdrawn, and the three
 * are cited differently: an open one is the week being worked on, a withdrawn one is a
 * document that has been taken off the site. A picker that renders all three as the
 * same line of text invites a citation into something nobody may read any more.
 */
@Component({
  selector: 'app-publications-field',
  templateUrl: './publications-field.html',
  imports: [EntityMultiselectControl, LocaleDatePipe, TranslocoPipe],
})
export class PublicationsField extends EntityMultiselectField<PublicationRef, string> {
  private readonly api = inject(PublicationsApiService);
  private readonly languageService = inject(LanguageService);

  /** Narrows the search to one message-publication channel; omitted returns both. */
  readonly messagePublication = input<'EXTERNAL' | 'INTERNAL' | undefined>(undefined);
  /** Publication `type` filter; `null` omits the param (reaches `LINK`-type publications). */
  readonly type = input<string | null>('MESSAGE_REPORT');
  /** Publication statuses accepted by the search. */
  readonly statuses = input<readonly string[]>(PUBLICATION_PICKER_STATUSES);

  /**
   * The domain the picker is being used from — visible-from, not owner.
   *
   * Supplied by the consumer rather than read here, because the two consumers
   * mean different domains: the message editor cites from the desk being worked
   * at, and the messages-list filter narrows within the domain being searched.
   * `null` or absent offers the whole catalogue.
   *
   * Resolution by id is deliberately NOT scoped — see `resolveByIds` — so a chip
   * restored from a link renders whatever it names.
   */
  readonly domain = input<string | null>(null);

  /**
   * Render each row as the publication's NAME alone — no status badge, no series,
   * no date.
   *
   * For the surfaces where the picker offers one narrow, already-qualified set and
   * the extra columns answer a question nobody is asking there: the citation dialog
   * offers released issues only, so every badge on the list says the same word, and
   * a row three facts wide inside a 36rem dialog crops the one fact the reader picks
   * by. The messages filter keeps the full row, where the set spans open, out and
   * withdrawn issues and the badge is what tells them apart.
   */
  readonly nameOnly = input(false);

  /**
   * How many further rows matched beyond the page shown.
   *
   * Rendered rather than dropped: the picker used to ask for one oversized window
   * that the server truncated to its own cap, so a user who could not find a
   * publication had no way of knowing whether it was missing or merely cut off.
   */
  protected readonly overflow = signal(0);

  override readonly optionLabel = (pub: PublicationRef): string => this.titleOf(pub);

  /**
   * The panel grows to its rows rather than to the box.
   *
   * A full row is a badge, a title, the series and a date, and the box it hangs
   * off is a filter chip in a bar shared with a dozen others — pinned to that
   * width the title was cropped to a few characters and the qualifiers that make
   * two weekly issues tell apart fell off the end.
   */
  override readonly panelWidth = 'content' as const;

  protected idOf(item: PublicationRef): string {
    return item.publicationId;
  }
  /** A restore stub carries only `publicationId`; a resolved ref carries `descs` (the title). */
  protected override isResolved(item: PublicationRef): boolean {
    return item.descs != null;
  }
  /**
   * Single-select browses the catalogue on focus; multi-select (the message filter) keeps
   * type-to-search. That split is the right one either way: picking ONE publication to
   * insert means recognising a title you can't necessarily spell, whereas the filter would
   * be dumping the whole catalogue into a panel every time it is tabbed into.
   */
  override get browsable(): boolean {
    return !this.multiple();
  }

  protected searchEntities(query: string): Observable<readonly PublicationRef[]> {
    return this.api
      .search(query, {
        messagePublication: this.messagePublication(),
        type: this.type(),
        statuses: this.statuses(),
        domain: this.domain(),
        // A type-ahead shows the few best guesses; browsing shows a page of the
        // catalogue. Neither asks for all of it — the count below the field says
        // what is beyond the page.
        maxSize: query ? undefined : BROWSE_PAGE_SIZE,
      })
      .pipe(
        tap((page) => this.overflow.set(Math.max(0, page.total - page.items.length))),
        map((page) => page.items),
      );
  }
  protected resolveByIds(ids: readonly string[]): Observable<readonly PublicationRef[]> {
    return this.api.getByIds(ids);
  }

  /** The i18n key for a row's status badge, or `undefined` for a legacy row that has none. */
  protected statusKey(pub: PublicationRef): string | undefined {
    const status = pub.status;
    return status && status in STATUS_CLASS
      ? `admin.publications.issueStatus.${status}`
      : undefined;
  }

  protected statusClass(pub: PublicationRef): string {
    return STATUS_CLASS[pub.status ?? ''] ?? '';
  }

  /**
   * What the series a row belongs to is called, or `undefined` for a row with no series.
   *
   * The same fallback ladder the title takes, ending at the id. The id is a database
   * key — `weekly-ntm` — so it names the series only to somebody who already knows the
   * catalogue; it is kept as the last resort rather than the first, because a row from
   * the legacy half carries no names at all and the key is then the only thing there is.
   */
  protected seriesLabel(pub: PublicationRef): string | undefined {
    const names = pub.seriesNames;
    if (names) {
      const active = names[this.languageService.selectedLanguage()];
      if (active) {
        return active;
      }
      for (const option of this.languageService.modelLanguages) {
        const name = names[option.value];
        if (name) {
          return name;
        }
      }
      const any = Object.values(names).find((name) => !!name);
      if (any) {
        return any;
      }
    }
    return pub.publicationSeriesId;
  }

  /**
   * The row's title in the active language, falling back through the configured ones.
   *
   * A fallback rather than a blank, and the id only as the last resort: a publication
   * named in one language is still one an editor may need to cite, and rendering it as
   * a bare identifier hides a real publication behind a translation gap.
   */
  private titleOf(pub: PublicationRef): string {
    const descs = pub.descs ?? [];
    if (descs.length === 0) {
      return pub.publicationId;
    }
    const titled = (lang: string): PublicationDescription | undefined =>
      descs.find((desc) => desc.lang === lang && !!desc.title);
    const active = titled(this.languageService.selectedLanguage());
    if (active?.title) {
      return active.title;
    }
    for (const option of this.languageService.modelLanguages) {
      const desc = titled(option.value);
      if (desc?.title) {
        return desc.title;
      }
    }
    return descs.find((desc) => !!desc.title)?.title ?? pub.publicationId;
  }
}
