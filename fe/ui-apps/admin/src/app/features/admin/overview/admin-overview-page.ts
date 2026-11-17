import { Component, inject } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { TranslocoPipe } from '@jsverse/transloco';

import { Icon } from '../../../shared/icons/icon';
import { AdminAreaDescriptor } from '../section-descriptors';
import { SectionGatesService } from '../services/section-gates.service';
import { AdminSectionPage } from '../shell/admin-section-page';

/**
 * Area landing page: one navigable card per section (title + the section's
 * one-line description), mirroring the rail's non-overview entries 1:1.
 *
 * Visibility-gated sections are filtered through the shell-provided
 * `SectionGatesService` (the overview renders in the shell's router-outlet, so it
 * shares that instance), keeping the cards in lock-step with the rail.
 */
@Component({
  selector: 'app-admin-overview-page',
  imports: [RouterLink, TranslocoPipe, AdminSectionPage, Icon],
  templateUrl: './admin-overview-page.html',
  host: { class: 'flex min-w-0 flex-1 flex-col' },
})
export class AdminOverviewPage {
  // The area descriptor lives on the parent (shell) route; static, so snapshot is safe.
  protected readonly area = inject(ActivatedRoute).snapshot.parent?.data[
    'area'
  ] as AdminAreaDescriptor;

  protected readonly gates = inject(SectionGatesService);
}
