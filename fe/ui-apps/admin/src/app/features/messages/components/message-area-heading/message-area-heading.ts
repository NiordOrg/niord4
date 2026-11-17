import { Component, Injector, computed, inject, input } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';

import { AuthFacade } from '../../../../core/auth/facades/auth.facade';
import { NO_AREA_SENTINEL_ID } from '../../models/message.model';
import type { MessageArea } from '../../models/message.model';
import { MessageAreaSortLauncher } from '../../services/message-area-sort.launcher';
import { MessageAreas } from '../message-areas/message-areas';

@Component({
  selector: 'app-message-area-heading',
  imports: [MessageAreas, TranslocoPipe],
  templateUrl: './message-area-heading.html',
})
export class MessageAreaHeading {
  private readonly auth = inject(AuthFacade);
  // Resolve the launcher lazily on click: it pulls the message-list facade, which this leaf
  // presentational component (rendered in every list view) must not force-construct at build time.
  private readonly injector = inject(Injector);

  readonly areaHeading = input.required<MessageArea>();

  readonly isNoArea = computed(() => this.areaHeading().id === NO_AREA_SENTINEL_ID);
  readonly areasArray = computed(() => [this.areaHeading()]);

  /**
   * Editors can reorder a real area's messages (not the "General"/no-area bucket). The heading's sort
   * first collects the messages into a temporary tag, which the backend grants only to the `user` role
   * in the session domain, so an editor without it (a role on the base client only) is not offered it.
   */
  protected readonly canSort = computed(
    () => this.auth.hasRole('editor')() && this.auth.hasDomainUserRole() && !this.isNoArea(),
  );

  protected sort(): void {
    this.injector.get(MessageAreaSortLauncher).sortAreaHeading(this.areaHeading());
  }
}
