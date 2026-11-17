import { Component, computed, inject, input, model } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';

import { DomainFacade } from '../../../../../core/domain/facades/domain.facade';
import type { Domain } from '../../../../../core/domain/models/domain.model';
import { Icon } from '../../../../../shared/icons/icon';
import type { Availability } from '../../../services/api/publication-series-api.service';
import { AVAILABILITIES, availabilityKey } from './publication-status';
import { availabilityNeedsDomain } from './series-rules';

/**
 * Who, besides the owner, may see and cite this publication.
 *
 * Three answers and a list, and the list only means anything under the middle
 * one. It is not a permission: everything shared is READ-ONLY where it is shared
 * — the owning desk stays the only one that can change or withdraw it — so the
 * copy talks about being able to cite a publication rather than about access.
 *
 * THE OWNER IS NOT IN THE LIST, and cannot be ticked into it: it is already the
 * owner, and a tickbox saying the desk has shared a publication with itself is a
 * control that can only be wrong. Inactive domains stay on offer and are marked
 * — a domain that is switched off is still a real desk, and hiding it would drop
 * a share nobody could then see or undo.
 *
 * The shared domain multi-select is deliberately not reused here. Its value is a
 * list of `Domain` objects, so an id would have to be resolved through the
 * catalogue and back — and a share naming a domain this catalogue does not carry
 * would be silently dropped on the round trip, which is exactly the share
 * somebody would never notice going missing. Ids in, ids out.
 */
@Component({
  selector: 'app-publication-availability-field',
  imports: [Icon, TranslocoPipe],
  templateUrl: './availability-field.html',
})
export class PublicationAvailabilityField {
  private readonly domainFacade = inject(DomainFacade);

  readonly value = model.required<Availability>();
  /** The shared-with list, by id. Only applies while `value` is SELECTED_DOMAINS. */
  readonly domainIds = model<readonly string[]>([]);
  /** The owner, excluded from the list below. */
  readonly ownerId = input<string | undefined>(undefined);
  readonly disabled = input(false);

  /** Radio group name, so two of these on one screen do not share a selection. */
  readonly name = input('publication-availability');

  protected readonly options = AVAILABILITIES;

  protected readonly optionKey = availabilityKey;

  /** Every domain but the owner's. */
  protected readonly others = computed<readonly Domain[]>(() => {
    const owner = this.ownerId()?.trim();
    return this.domainFacade.availableDomains().filter((domain) => domain.domainId !== owner);
  });

  protected readonly showList = computed(() => this.value() === 'SELECTED_DOMAINS');

  /**
   * Shares with a desk the catalogue does not carry.
   *
   * `/domains/all` answers with the ACTIVE domains, so a domain deactivated
   * after a publication was shared with it simply stops appearing — and a list
   * that renders only what it recognises shows an admin nothing to explain why
   * a fourth desk can still cite the publication. Each of these gets a row of
   * its own: greyed, with no tickbox to imply it can be turned back on from
   * here, and a remove control so it CAN be dropped.
   *
   * Untouched otherwise. The payload sends the list back as it stands, so a
   * share nobody removed survives a save made for another reason.
   */
  protected readonly unknownIds = computed<readonly string[]>(() => {
    const known = new Set(this.others().map((domain) => domain.domainId));
    const owner = this.ownerId()?.trim();
    return this.domainIds().filter((id) => !known.has(id) && id !== owner);
  });

  /** Nothing to show at all: no catalogue rows and no orphaned shares either. */
  protected readonly listEmpty = computed(
    () => this.others().length === 0 && this.unknownIds().length === 0,
  );

  /**
   * "Selected domains", with nothing selected.
   *
   * The one state this control can be in that says less than the option beside
   * it: a publication shared with nobody is OWNER_ONLY under a name that claims
   * otherwise. Named here, next to the radios, and folded into both editors'
   * save gates through the same predicate.
   */
  protected readonly needsDomain = computed(() =>
    availabilityNeedsDomain({ availability: this.value() }, this.domainIds()),
  );

  protected isSelected(domainId: string): boolean {
    return this.domainIds().includes(domainId);
  }

  /** Drop one share. The only thing that can be done to an orphaned one. */
  protected remove(domainId: string): void {
    this.domainIds.update((current) => current.filter((id) => id !== domainId));
  }

  protected select(option: Availability): void {
    this.value.set(option);
  }

  /**
   * Tick or untick one desk.
   *
   * Held in the CATALOGUE's order rather than in click order, so the stored list
   * reads the same for the next person to open the drawer — and an id the
   * catalogue does not carry is kept where it is rather than being dropped by an
   * ordering pass it has no place in.
   */
  protected toggle(domainId: string): void {
    const order = this.others().map((domain) => domain.domainId);
    this.domainIds.update((current) => {
      const next = current.includes(domainId)
        ? current.filter((id) => id !== domainId)
        : [...current, domainId];
      const known = order.filter((id) => next.includes(id));
      const unknown = next.filter((id) => !order.includes(id));
      return [...known, ...unknown];
    });
  }
}
