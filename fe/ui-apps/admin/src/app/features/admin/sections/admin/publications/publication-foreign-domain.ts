import { Component, computed, inject, input } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';

import { DomainFacade } from '../../../../../core/domain/facades/domain.facade';
import { EmptyState } from '../../../../../shared/components/empty-state/empty-state';

/**
 * The refusal a publication another desk owns is shown instead of its page.
 *
 * ONE PANEL for all three screens — a series, a one-off and an issue are the
 * same refusal for the same reason, and the reader is owed the same way out of
 * each. It replaces the page rather than sitting above it: a page that renders
 * with every action quietly withdrawn reads as a screen that broke, and the
 * sentence explaining it is the one thing on the screen worth reading.
 *
 * IT NAMES THE DESK AND NOTHING ELSE. The publication's own name is another
 * desk's data, and the id is already in the address the reader typed — so
 * printing the name would re-leak the one field the refusal exists to withhold.
 *
 * A REFUSAL, NOT A REDIRECT. Somebody who followed a colleague's link is told
 * why the page is not theirs and offered the one step that changes it; a silent
 * bounce to the dashboard reads as a dead link.
 */
@Component({
  selector: 'app-publication-foreign-domain',
  imports: [EmptyState, TranslocoPipe],
  templateUrl: './publication-foreign-domain.html',
})
export class PublicationForeignDomain {
  /** The desk that owns the publication. Never this one — the caller decided that. */
  readonly ownerDomainId = input.required<string>();

  private readonly domains = inject(DomainFacade);

  /**
   * What that desk is called, falling back to its id.
   *
   * A desk that has since been deleted still names itself to anybody reading
   * this — `niord-annex` is an answer, and an empty sentence is not.
   */
  protected readonly ownerName = computed(
    () => this.domains.resolveById(this.ownerDomainId())?.name ?? this.ownerDomainId(),
  );

  /**
   * Whether the one action can be offered at all.
   *
   * The switch resolves the desk out of the same catalogue the picker uses, so a
   * desk the reader holds no role in — or one the catalogue does not list at all
   * — cannot be switched to: the button would either do nothing or leave the
   * reader at no desk. Saying that is the whole answer there, and it is a better
   * one than a button that fails.
   */
  protected readonly canSwitch = computed(() =>
    this.domains.editableDomains().some((domain) => domain.domainId === this.ownerDomainId()),
  );

  /**
   * What the refusal says under its title, in the version that is true.
   *
   * A reader who can switch is told which desk to switch to; one who cannot is
   * told that the desk is not theirs, rather than being offered a step that
   * would not work.
   */
  protected readonly bodyKey = computed(() =>
    this.canSwitch()
      ? 'admin.publications.foreignDomain.body'
      : 'admin.publications.foreignDomain.noAccess',
  );

  /**
   * Switch to the owning desk, after which this same address renders.
   *
   * The picker's own path, so the whole application changes desk exactly as it
   * does from the header — but it KEEPS THE ADDRESS, query string included. A
   * desk change from anywhere else leaves a publication page, because the record
   * belongs to the desk being left; here it is the opposite, and the address is
   * the whole point of the offer: the desk being chosen is the one that can
   * finally render this series, this issue, this one-off.
   */
  protected switchDesk(): void {
    this.domains.selectSessionDomain(this.ownerDomainId(), { keepAddress: true });
  }
}
