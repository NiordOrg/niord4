import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';

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
}

function setup() {
  const facadeStub = {
    categories: () => [] as readonly CategoryRef[],
    loading: () => false,
    error: () => false,
    refresh: () => undefined,
  };
  TestBed.configureTestingModule({
    providers: [
      provideTestTransloco(),
      {
        provide: CategoriesAdminApiService,
        useValue: { exportUrl: () => '/rest/categories/export', uploadUrl: () => '/x' },
      },
      { provide: TicketedDownloadService, useValue: { openPublic: () => undefined } },
      {
        provide: NotificationService,
        useValue: { success: () => undefined, error: () => undefined },
      },
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
  return fixture.componentInstance as unknown as PageInternals;
}

describe('CategoriesPage', () => {
  it('flags TEMPLATE nodes for the tree badge', () => {
    const page = setup();
    expect(page.isTemplateNode({ id: 1, type: 'TEMPLATE' } as CategoryRef)).toBe(true);
    expect(page.isTemplateNode({ id: 2, type: 'CATEGORY' } as CategoryRef)).toBe(false);
    expect(page.isTemplateNode({ id: 3 } as CategoryRef)).toBe(false);
  });

  it('disables Add only while the open editor is live-typed TEMPLATE', () => {
    const page = setup();
    expect(page.addDisabled()).toBe(false);
    // The editor mirrors its live radio to the page via `(typeChange)` → `liveType`.
    page.liveType.set('CATEGORY');
    expect(page.addDisabled()).toBe(false);
    page.liveType.set('TEMPLATE');
    expect(page.addDisabled()).toBe(true);
  });

  it('cog menu is export / import / re-index (no firing, no published-sort)', () => {
    const page = setup();
    const items = page.cogItems();
    // 3 actions + 1 separator.
    expect(items.filter((i) => !i.separator)).toHaveLength(3);
    expect(items.filter((i) => i.separator)).toHaveLength(1);
  });
});
