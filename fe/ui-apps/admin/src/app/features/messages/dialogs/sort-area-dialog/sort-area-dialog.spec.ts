import { Component, input, output, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { DomainFacade } from '../../../../core/domain/facades/domain.facade';
import { HelpUrlService } from '../../../../core/main-shell/help/help-url.service';
import { NotificationService } from '../../../../core/notifications/notification.service';
import { provideTestTransloco } from '../../../../shared/testing/transloco-testing';
import { AreasField } from '../../components/areas-field/areas-field';
import type { AreaRef } from '../../models/messages-filter-params.model';
import { MessagesApiService } from '../../services/api/messages-api.service';
import { MessagesAreaSortApiService } from '../../services/api/messages-area-sort-api.service';
import { SortAreaDialog } from './sort-area-dialog';
import { SortAreaDialogService } from './sort-area-dialog.service';

/** Stands in for the area picker, which loads its tree from the backend. */
@Component({ selector: 'app-areas-field', template: '' })
class StubAreasField {
  readonly value = input<readonly AreaRef[]>([]);
  readonly domainId = input<string | null>(null);
  readonly placeholder = input<string>('');
  readonly valueChange = output<readonly AreaRef[]>();
}

/** Reaches the dialog's protected pick handler, as the area picker's valueChange calls it. */
interface PickHandle {
  onAreaPicked(areas: readonly AreaRef[]): void;
}

function setup() {
  const searchMessages = vi.fn(() => of({ data: [], total: 0 }));
  TestBed.configureTestingModule({
    providers: [
      provideTestTransloco(),
      { provide: MessagesApiService, useValue: { searchMessages } },
      { provide: MessagesAreaSortApiService, useValue: { changeAreaSortOrder: vi.fn() } },
      { provide: NotificationService, useValue: { error: vi.fn() } },
      { provide: DomainFacade, useValue: { sessionDomain: signal({ domainId: 'd' }) } },
      {
        provide: HelpUrlService,
        useValue: { urlForSection: () => undefined, routeUrl: signal(undefined) },
      },
    ],
  });
  TestBed.overrideComponent(SortAreaDialog, {
    remove: { imports: [AreasField] },
    add: { imports: [StubAreasField] },
  });
  const service = TestBed.inject(SortAreaDialogService);
  const fixture = TestBed.createComponent(SortAreaDialog);
  fixture.autoDetectChanges();
  return { fixture, service, searchMessages };
}

/** The dialog's body is portaled to the CDK overlay container, so the list is read there. */
function listText(): string {
  return document.querySelector('.cdk-overlay-container')?.textContent ?? '';
}

describe('SortAreaDialog — the empty list', () => {
  afterEach(() => {
    document.querySelectorAll('.cdk-overlay-container').forEach((el) => el.replaceChildren());
  });

  it('asks for an area while none is picked', async () => {
    const { fixture, service, searchMessages } = setup();
    service.openInteractive('PUBLISHED');
    await fixture.whenStable();

    expect(searchMessages).not.toHaveBeenCalled();
    expect(listText()).toContain('messages.sortArea.pickArea');
    expect(listText()).not.toContain('messages.sortArea.empty');
  });

  it('says there is nothing to sort once a picked area has no messages of the chosen status', async () => {
    const { fixture, service, searchMessages } = setup();
    service.openInteractive('PUBLISHED');
    await fixture.whenStable();

    (fixture.componentInstance as unknown as PickHandle).onAreaPicked([{ id: 7 } as AreaRef]);
    await fixture.whenStable();

    expect(searchMessages).toHaveBeenCalledWith(
      'area=7&status=PUBLISHED&sortBy=AREA&sortOrder=ASC',
      0,
      1000,
    );
    expect(listText()).toContain('messages.sortArea.empty');
    expect(listText()).not.toContain('messages.sortArea.pickArea');
  });

  it('says there is nothing to sort when a tag scopes no messages', async () => {
    const { fixture, service } = setup();
    service.openWithTag('tmp-1');
    await fixture.whenStable();

    expect(listText()).toContain('messages.sortArea.empty');
    expect(listText()).not.toContain('messages.sortArea.pickArea');
  });
});
