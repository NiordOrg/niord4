import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';

import { TicketedDownloadService } from '../../../../../core/auth/services/ticketed-download.service';
import { DomainFacade } from '../../../../../core/domain/facades/domain.facade';
import { NotificationService } from '../../../../../core/notifications/notification.service';
import { ConfirmService } from '../../../../../shared/services/confirm.service';
import { provideTestTransloco } from '../../../../../shared/testing/transloco-testing';
import type { AreaRef } from '../../../../messages/models/messages-filter-params.model';
import { MessagesAreaSortApiService } from '../../../../messages/services/api/messages-area-sort-api.service';
import { AreasAdminApiService } from '../../../services/api/areas-admin-api.service';
import { ModelLanguageSelection } from '../../../services/model-language-selection';
import { AreasPage } from './areas-page';
import { AreasFacade } from './areas.facade';

interface PageInternals {
  cogItems: () => { label?: string; separator?: boolean; command?: () => void }[];
}

function setup() {
  const facadeStub = {
    areas: () => [] as readonly AreaRef[],
    loading: () => false,
    error: () => false,
    refresh: () => undefined,
  };
  const recomputeTreeSortOrder = vi.fn(() => of(null));
  const recomputeAreaSortOrder = vi.fn(() => of(null));
  const notifications = { success: vi.fn(), error: vi.fn() };
  TestBed.configureTestingModule({
    providers: [
      provideTestTransloco(),
      {
        provide: AreasAdminApiService,
        useValue: {
          exportUrl: () => '/rest/areas/export',
          uploadUrl: () => '/x',
          recomputeTreeSortOrder,
        },
      },
      { provide: MessagesAreaSortApiService, useValue: { recomputeAreaSortOrder } },
      { provide: DomainFacade, useValue: { capabilities: () => ({ firingSchedule: false }) } },
      { provide: TicketedDownloadService, useValue: { openPublic: () => undefined } },
      { provide: NotificationService, useValue: notifications },
    ],
  });
  TestBed.overrideComponent(AreasPage, {
    set: {
      template: '',
      providers: [
        { provide: AreasFacade, useValue: facadeStub },
        ConfirmService,
        ModelLanguageSelection,
      ],
    },
  });
  const fixture = TestBed.createComponent(AreasPage);
  fixture.detectChanges();
  const page = fixture.componentInstance as unknown as PageInternals;
  const confirm = fixture.debugElement.injector.get(ConfirmService);

  /** Runs the cog item with the given label key (the test bundle renders a key as itself). */
  function runCog(labelKey: string): void {
    const item = page.cogItems().find((i) => i.label === labelKey);
    expect(item, labelKey).toBeDefined();
    item!.command!();
  }

  return { runCog, confirm, recomputeTreeSortOrder, recomputeAreaSortOrder, notifications };
}

describe('AreasPage cog menu', () => {
  it('"Re-index published messages" asks first, with a destructive confirm, and runs nothing yet', () => {
    const { runCog, confirm, recomputeAreaSortOrder } = setup();

    runCog('admin.areas.menu.reindexMessages');

    expect(recomputeAreaSortOrder).not.toHaveBeenCalled();
    const active = confirm.active();
    expect(active?.intent).toBe('destructive');
    expect(active?.header).toBe('admin.areas.confirmReindexMessages.header');
    expect(active?.message).toBe('admin.areas.confirmReindexMessages.message');
    expect(active?.acceptLabel).toBe('admin.areas.confirmReindexMessages.accept');
  });

  it('accepting the confirm re-indexes the published messages once', () => {
    const { runCog, confirm, recomputeAreaSortOrder, notifications } = setup();

    runCog('admin.areas.menu.reindexMessages');
    confirm.active()!.accept();

    expect(recomputeAreaSortOrder).toHaveBeenCalledTimes(1);
    expect(notifications.success).toHaveBeenCalledWith('admin.areas.toast.reindexed');
  });

  it('turning the confirm down never re-indexes', () => {
    const { runCog, confirm, recomputeAreaSortOrder } = setup();

    runCog('admin.areas.menu.reindexMessages');
    confirm.active()!.reject?.();
    confirm.dismiss();

    expect(recomputeAreaSortOrder).not.toHaveBeenCalled();
  });

  it('"Re-index areas" runs at once, with no confirm', () => {
    const { runCog, confirm, recomputeTreeSortOrder } = setup();

    runCog('admin.areas.menu.reindexAreas');

    expect(recomputeTreeSortOrder).toHaveBeenCalledTimes(1);
    expect(confirm.active()).toBeUndefined();
  });
});
