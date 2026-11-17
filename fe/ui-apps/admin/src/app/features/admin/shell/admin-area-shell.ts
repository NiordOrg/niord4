import { afterRenderEffect, Component, ElementRef, inject, viewChild } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import {
  ActivatedRoute,
  NavigationEnd,
  Router,
  RouterLink,
  RouterLinkActive,
  RouterOutlet,
} from '@angular/router';
import { filter } from 'rxjs';
import { TranslocoPipe } from '@jsverse/transloco';

import { Icon } from '../../../shared/icons/icon';
import { AdminAreaDescriptor } from '../section-descriptors';
import { BatchStatusService } from '../services/batch-status.service';
import { SectionGatesService } from '../services/section-gates.service';

/**
 * Shared shell for the Admin and Sysadmin areas: a left section rail + the routed
 * section pane. The rail entries come from the area descriptor on the route's
 * `data.area`, so rail, overview cards, and child routes always agree.
 *
 * The rail stacks above the content pane at narrow viewports; from `md` up it
 * sits to the left.
 *
 * Areas with a badge-carrying section (sysadmin's Batch Jobs) poll the
 * running-jobs count for the shell's lifetime; other areas never start the poll.
 *
 * Visibility-gated sections (those with a `settingGate`) are filtered out of the
 * rail and the overview cards until their backend setting resolves truthy; the
 * shell provides `SectionGatesService` so the overview page shares the instance.
 */
@Component({
  selector: 'app-admin-area-shell',
  imports: [RouterLink, RouterLinkActive, RouterOutlet, TranslocoPipe, Icon],
  providers: [BatchStatusService, SectionGatesService],
  templateUrl: './admin-area-shell.html',
  host: { class: 'flex min-w-0 flex-1 flex-col' },
})
export class AdminAreaShell {
  // Route data is static per area route, so the snapshot read is safe.
  protected readonly area = inject(ActivatedRoute).snapshot.data['area'] as AdminAreaDescriptor;

  protected readonly batchStatus = inject(BatchStatusService);
  protected readonly gates = inject(SectionGatesService);

  private readonly nav = viewChild.required<ElementRef<HTMLElement>>('nav');
  private readonly navigated = toSignal(
    inject(Router).events.pipe(filter((e) => e instanceof NavigationEnd)),
  );

  constructor() {
    if (this.area.sections.some((section) => section.hasBatchBadge)) {
      this.batchStatus.start();
    }
    this.gates.start(this.area.sections);

    // Below `lg` the section nav is a single row that pans sideways, so the
    // active section can start off-screen. After each navigation bring it into
    // view; at lg+ the nav is a column that does not scroll, so nothing moves.
    afterRenderEffect(() => {
      this.navigated();
      const nav = this.nav().nativeElement;
      if (nav.scrollWidth <= nav.clientWidth) {
        return;
      }
      nav
        .querySelector<HTMLElement>('[aria-current="page"]')
        ?.scrollIntoView?.({ inline: 'center', block: 'nearest' });
    });
  }
}
