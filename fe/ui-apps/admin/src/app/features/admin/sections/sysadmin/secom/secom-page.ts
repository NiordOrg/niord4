import { Component, inject, signal } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';

import { Confirm } from '../../../../../shared/components/confirm/confirm';
import {
  AppTab,
  AppTabList,
  AppTabPanel,
  AppTabPanels,
  AppTabs,
} from '../../../../../shared/components/tabs/tabs';
import { Icon } from '../../../../../shared/icons/icon';
import { ConfirmService } from '../../../../../shared/services/confirm.service';
import { AdminSectionPage } from '../../../shell/admin-section-page';
import { SecomCertificatesTab } from './secom-certificates-tab';
import { SecomDatasetsTab } from './secom-datasets-tab';
import { SecomOutboxTab } from './secom-outbox-tab';
import { SecomOverviewTab } from './secom-overview-tab';
import { SecomSubscribersTab } from './secom-subscribers-tab';
import { SecomFacade } from './secom.facade';

/**
 * SECOM section: operates the S-124 exchange — node status and certificates,
 * which message series are exposed, the generated datasets, the subscribers,
 * and the delivery outbox. Five page-local tabs (no URL state — a reload lands
 * on Overview), all reading one page-scoped {@link SecomFacade}.
 *
 * The section is registered unconditionally, so a deep link always resolves; on
 * a node built without the SECOM subsystem the facade's 404 probe replaces the
 * tabs with an explanatory note rather than five broken panels. The rail entry
 * and overview card are separately gated on `secomEnabled`, the value the web
 * settings compute from the running `niord.secom.enabled`.
 */
@Component({
  selector: 'app-secom-page',
  imports: [
    AdminSectionPage,
    AppTabs,
    AppTabList,
    AppTab,
    AppTabPanels,
    AppTabPanel,
    Confirm,
    Icon,
    SecomOverviewTab,
    SecomCertificatesTab,
    SecomDatasetsTab,
    SecomSubscribersTab,
    SecomOutboxTab,
    TranslocoPipe,
  ],
  providers: [SecomFacade, ConfirmService],
  templateUrl: './secom-page.html',
  host: { class: 'flex min-w-0 flex-1 flex-col' },
})
export class SecomPage {
  protected readonly facade = inject(SecomFacade);

  protected readonly activeTab = signal('overview');
}
