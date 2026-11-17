import { Component, computed, effect, inject, model, signal, untracked } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';

import { DomainFacade } from '../../../../../core/domain/facades/domain.facade';
import { NotificationService } from '../../../../../core/notifications/notification.service';
import { Dialog } from '../../../../../shared/components/dialog/dialog';
import { MESSAGE_TAGS_DATA_SOURCE } from '../../../../../shared/forms/message-tags-field/message-tags-data.token';
import { SingleTagField } from '../../../../../shared/forms/single-tag-field/single-tag-field';
import { TagsApiService } from '../../../../messages/services/api/tags-api.service';
import { AreasAdminApiService } from '../../../services/api/areas-admin-api.service';

/**
 * Generate firing-area template messages: pick a message series (from the session domain) and an
 * optional message tag, then POST to the firing-schedules endpoint. Only reachable when the domain
 * has a firing schedule (the Areas cog gates it).
 *
 * The tag is picked with the shared single-tag field, which searches the tags by name as the user
 * types, so every tag of the domain can be reached (the tags endpoint returns at most 100 rows per
 * request). The field is bound to the tags API itself, not to `provideMessageTagsDataSource()`,
 * so locked tags stay on offer: the generator adds the messages to a locked tag as to any other.
 */
@Component({
  selector: 'app-firing-generate-dialog',
  imports: [Dialog, SingleTagField, TranslocoPipe],
  providers: [{ provide: MESSAGE_TAGS_DATA_SOURCE, useExisting: TagsApiService }],
  templateUrl: './firing-generate-dialog.html',
})
export class FiringGenerateDialog {
  private readonly api = inject(AreasAdminApiService);
  private readonly domain = inject(DomainFacade);
  private readonly notifications = inject(NotificationService);

  readonly visible = model(false);

  protected readonly seriesIds = this.domain.domainMessageSeriesIds;
  protected readonly seriesId = signal('');
  protected readonly tagId = signal<string | null>(null);
  protected readonly generating = signal(false);

  protected readonly generateDisabled = computed(() => this.seriesId() === '' || this.generating());

  constructor() {
    // Seed the form each time the dialog opens.
    effect(() => {
      if (!this.visible()) return;
      untracked(() => {
        this.seriesId.set(this.seriesIds()[0] ?? '');
        this.tagId.set(null);
        this.generating.set(false);
      });
    });
  }

  protected generate(): void {
    if (this.generateDisabled()) return;
    this.generating.set(true);
    this.api.generateFiringAreaMessages(this.seriesId(), this.tagId() ?? undefined).subscribe({
      next: () => {
        this.notifications.success('admin.areas.firing.toast.generated');
        this.visible.set(false);
      },
      error: () => {
        this.generating.set(false);
        this.notifications.error('admin.areas.firing.toast.error');
      },
    });
  }
}
