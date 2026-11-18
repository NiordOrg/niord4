import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';

import { TicketedDownloadService } from '../../../../../core/auth/services/ticketed-download.service';
import { NotificationService } from '../../../../../core/notifications/notification.service';
import { ConfirmService } from '../../../../../shared/services/confirm.service';
import { provideTestTransloco } from '../../../../../shared/testing/transloco-testing';
import type { CategoryRef } from '../../../../messages/models/message.model';
import { CategoriesAdminApiService } from '../../../services/api/categories-admin-api.service';
import { CategoriesPage } from './categories-page';
import { CategoriesFacade } from './categories.facade';
import { ModelLanguageSelection } from '../../../services/model-language-selection';

interface PageInternals {
  isTemplateNode: (category: CategoryRef) => boolean;
  addDisabled: () => boolean;
  cogItems: () => { separator?: boolean }[];
  liveType: { set: (value: unknown) => void };
  selectedNode: { set: (value: CategoryRef | undefined) => void };
  deleteSelected: () => void;
  onNodeMoved: (move: { movedId: number; targetParentId: number | null }) => void;
}

/** A failed response as the backend sends it: a problem document, its `detail` optional. */
function failure(status: number, detail?: string): HttpErrorResponse {
  return new HttpErrorResponse({
    status,
    error: { type: 'about:blank', status, ...(detail === undefined ? {} : { detail }) },
  });
}

function setup(apiFailure?: HttpErrorResponse) {
  const facadeStub = {
    categories: () => [] as readonly CategoryRef[],
    loading: () => false,
    error: () => false,
    refresh: () => undefined,
  };
  const notifications = { success: vi.fn(), error: vi.fn() };
  TestBed.configureTestingModule({
    providers: [
      provideTestTransloco(),
      {
        provide: CategoriesAdminApiService,
        useValue: {
          exportUrl: () => '/rest/categories/export',
          uploadUrl: () => '/x',
          delete: () => throwError(() => apiFailure),
          moveCategory: () => throwError(() => apiFailure),
        },
      },
      { provide: TicketedDownloadService, useValue: { openPublic: () => undefined } },
      { provide: NotificationService, useValue: notifications },
    ],
  });
  TestBed.overrideComponent(CategoriesPage, {
    set: {
      template: '',
      providers: [
        { provide: CategoriesFacade, useValue: facadeStub },
        ConfirmService,
        ModelLanguageSelection,
      ],
    },
  });
  const fixture = TestBed.createComponent(CategoriesPage);
  fixture.detectChanges();
  const confirm = fixture.debugElement.injector.get(ConfirmService);
  return {
    page: fixture.componentInstance as unknown as PageInternals,
    notifications,
    accept: () => confirm.active()!.accept(),
  };
}

describe('CategoriesPage', () => {
  it('flags TEMPLATE nodes for the tree badge', () => {
    const { page } = setup();
    expect(page.isTemplateNode({ id: 1, type: 'TEMPLATE' } as CategoryRef)).toBe(true);
    expect(page.isTemplateNode({ id: 2, type: 'CATEGORY' } as CategoryRef)).toBe(false);
    expect(page.isTemplateNode({ id: 3 } as CategoryRef)).toBe(false);
  });

  it('disables Add only while the open editor is live-typed TEMPLATE', () => {
    const { page } = setup();
    expect(page.addDisabled()).toBe(false);
    // The editor mirrors its live radio to the page via `(typeChange)` → `liveType`.
    page.liveType.set('CATEGORY');
    expect(page.addDisabled()).toBe(false);
    page.liveType.set('TEMPLATE');
    expect(page.addDisabled()).toBe(true);
  });

  it('cog menu is export / import / re-index (no firing, no published-sort)', () => {
    const { page } = setup();
    const items = page.cogItems();
    // 3 actions + 1 separator.
    expect(items.filter((i) => !i.separator)).toHaveLength(3);
    expect(items.filter((i) => i.separator)).toHaveLength(1);
  });

  describe('a failed delete or move', () => {
    const detail = 'The category or one of its sub-categories is in use: 3 messages.';

    function deleteWith(apiFailure: HttpErrorResponse) {
      const ctx = setup(apiFailure);
      ctx.page.selectedNode.set({ id: 7, descs: [{ lang: 'en', name: 'Lights' }] } as CategoryRef);
      ctx.page.deleteSelected();
      ctx.accept();
      return ctx.notifications;
    }

    it('a delete the server refuses with a reason shows that reason', () => {
      const notifications = deleteWith(failure(409, detail));
      expect(notifications.error).toHaveBeenCalledExactlyOnceWith(
        'admin.categories.toast.deleteRefused',
        { detail },
      );
    });

    it('a refused delete without a reason shows the plain error', () => {
      const notifications = deleteWith(failure(409));
      expect(notifications.error).toHaveBeenCalledExactlyOnceWith(
        'admin.categories.toast.deleteError',
      );
    });

    it('any other failed delete shows the plain error, even with a detail', () => {
      const notifications = deleteWith(failure(500, 'Something broke'));
      expect(notifications.error).toHaveBeenCalledExactlyOnceWith(
        'admin.categories.toast.deleteError',
      );
    });

    it('a move the server refuses with a reason shows that reason', () => {
      const moveDetail = 'A category cannot be moved under itself or one of its sub-categories';
      const { page, notifications, accept } = setup(failure(400, moveDetail));
      page.onNodeMoved({ movedId: 1, targetParentId: 2 });
      accept();
      expect(notifications.error).toHaveBeenCalledExactlyOnceWith(
        'admin.categories.toast.moveRefused',
        { detail: moveDetail },
      );
    });

    it('any other failed move shows the plain error', () => {
      const { page, notifications, accept } = setup(failure(500, 'Something broke'));
      page.onNodeMoved({ movedId: 1, targetParentId: null });
      accept();
      expect(notifications.error).toHaveBeenCalledExactlyOnceWith(
        'admin.categories.toast.moveError',
      );
    });
  });
});
