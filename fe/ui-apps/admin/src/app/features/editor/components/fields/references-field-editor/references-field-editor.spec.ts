import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';

import { AuthFacade } from '../../../../../core/auth/facades/auth.facade';
import { APP_CONFIG } from '../../../../../core/config/app-config.token';
import { DomainFacade } from '../../../../../core/domain/facades/domain.facade';
import { LanguageService } from '../../../../../core/main-shell/services/language.service';
import { NotificationService } from '../../../../../core/notifications/notification.service';
import { provideTestTransloco } from '../../../../../shared/testing/transloco-testing';
import type { Message } from '../../../../messages/models/message.model';
import { MessageDetailsDialogService } from '../../../../messages/services/message-details-dialog.service';
import { MessagesApiService } from '../../../../messages/services/api/messages-api.service';
import { EditorFacade } from '../../../services/editor-facade';
import { EditorFieldsService } from '../../../services/editor-fields.service';
import { TemplateMessageStore } from '../../../services/template-message-store';
import { ReferencesFieldEditor } from './references-field-editor';

// Reaches the component's protected event handlers (the facade-delegation wiring under test).
interface RefsHandle {
  add(): void;
  remove(index: number): void;
  onDrop(event: { previousIndex: number; currentIndex: number }): void;
  onReferenceId(index: number, messageId: string | null): void;
  onReferenceType(index: number, type: unknown): void;
  onReferenceDesc(index: number, event: { lang: string; value: string }): void;
}

function setup() {
  const auth = { hasRole: (role: string) => signal(role === 'editor') };
  const domain = {
    sessionDomain: signal({ domainId: 'd', messageSeries: [] }),
    supportsMainType: () => signal(true),
  };
  const api = {
    editableDetails: vi.fn((id: string) => of({ id, status: 'DRAFT' } as Message)),
    adjustEditableMessage: vi.fn((message: Message) => of(message)),
    searchMessageIds: vi.fn(() => of([])),
  };
  const notify = { error: vi.fn(), success: vi.fn(), info: vi.fn(), warn: vi.fn() };
  const dialog = { open: vi.fn(), close: vi.fn() };
  const language = {
    modelLanguages: [
      { value: 'en', label: 'English', flagPath: 'assets/img/flags/en.png' },
      { value: 'da', label: 'Dansk', flagPath: 'assets/img/flags/da.png' },
    ],
    selectedLanguage: signal('en'),
  };

  TestBed.configureTestingModule({
    providers: [
      provideTestTransloco(),
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest' } },
      EditorFacade,
      EditorFieldsService,
      { provide: AuthFacade, useValue: auth },
      { provide: DomainFacade, useValue: domain },
      { provide: MessagesApiService, useValue: api },
      { provide: NotificationService, useValue: notify },
      {
        provide: TemplateMessageStore,
        useValue: { consume: vi.fn(), set: vi.fn(), clear: vi.fn() },
      },
      { provide: MessageDetailsDialogService, useValue: dialog },
      { provide: LanguageService, useValue: language },
    ],
  });

  const facade = TestBed.inject(EditorFacade);
  const fields = TestBed.inject(EditorFieldsService);
  const fixture = TestBed.createComponent(ReferencesFieldEditor);
  const handle = fixture.componentInstance as unknown as RefsHandle;
  return { fixture, facade, fields, api, dialog, handle };
}

const draft: Message = { id: 'm', status: 'DRAFT', mainType: 'NW' };

describe('ReferencesFieldEditor', () => {
  it('renders per-reference viewer links; a click opens the details dialog', () => {
    const { fixture, facade, dialog } = setup();
    facade.applyLoaded({ ...draft, references: [{ messageId: 'NW-100', type: 'REFERENCE' }] });
    fixture.detectChanges();

    const link = (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>(
      '.reference-link',
    );
    expect(link?.textContent?.trim()).toBe('NW-100');
    link?.click();
    expect(dialog.open).toHaveBeenCalledWith('NW-100', ['NW-100']);
  });

  it('add seeds a reference (one desc per model language) through the facade', () => {
    const { facade, handle } = setup();
    facade.applyLoaded({ ...draft, references: [] });
    handle.add();
    const references = facade.message()?.references ?? [];
    expect(references.length).toBe(1);
    expect(references[0].type).toBe('REFERENCE');
    expect(references[0].descs?.map((d) => d.lang)).toEqual(['en', 'da']);
    expect(facade.userTouched()).toBe(true);
  });

  it('remove drops the reference row', () => {
    const { facade, handle } = setup();
    facade.applyLoaded({ ...draft, references: [{ messageId: 'NW-100', type: 'REFERENCE' }] });
    handle.remove(0);
    expect(facade.message()?.references ?? []).toEqual([]);
  });

  it('onDrop reorders the references via moveReference', () => {
    const { facade, handle } = setup();
    facade.applyLoaded({
      ...draft,
      references: [
        { messageId: 'A', type: 'REFERENCE' },
        { messageId: 'B', type: 'REFERENCE' },
      ],
    });
    handle.onDrop({ previousIndex: 0, currentIndex: 1 });
    expect(facade.message()?.references?.map((r) => r.messageId)).toEqual(['B', 'A']);
  });

  it('the autocomplete / type / description edits patch the reference', () => {
    const { facade, handle } = setup();
    facade.applyLoaded({ ...draft, references: [{ messageId: '', type: 'REFERENCE', descs: [] }] });

    handle.onReferenceId(0, 'NW-042');
    handle.onReferenceType(0, 'CANCELLATION');
    handle.onReferenceDesc(0, { lang: 'en', value: 'see also' });

    const reference = facade.message()?.references?.[0];
    expect(reference?.messageId).toBe('NW-042');
    expect(reference?.type).toBe('CANCELLATION');
    expect(reference?.descs?.find((d) => d.lang === 'en')?.description).toBe('see also');
  });

  it('expanding renders the editor row with the autocomplete and type select', () => {
    const { fixture, facade, fields } = setup();
    facade.applyLoaded({ ...draft, references: [{ messageId: 'NW-100', type: 'REFERENCE' }] });
    fields.expand('references');
    fixture.detectChanges();

    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelector('[data-ref-row]')).not.toBeNull();
    expect(host.querySelector('app-message-id-field')).not.toBeNull();
    expect(host.querySelector('app-select')).not.toBeNull();
  });
});
