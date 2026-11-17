import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { TranslocoService } from '@jsverse/transloco';
import { of } from 'rxjs';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { AuthFacade } from '../../../../../core/auth/facades/auth.facade';
import { DomainFacade } from '../../../../../core/domain/facades/domain.facade';
import type { Domain } from '../../../../../core/domain/models/domain.model';
import { LanguageService } from '../../../../../core/main-shell/services/language.service';
import { NotificationService } from '../../../../../core/notifications/notification.service';
import { RichTextEditor } from '../../../../../shared/forms/rich-text-editor/rich-text-editor';
import { provideNiordIcons } from '../../../../../shared/icons/niord-icons';
import { provideTestTransloco } from '../../../../../shared/testing/transloco-testing';
import type { Message } from '../../../../messages/models/message.model';
import { MessagesApiService } from '../../../../messages/services/api/messages-api.service';
import { PublicationsApiService } from '../../../../messages/services/api/publications-api.service';
import { EditorFacade } from '../../../services/editor-facade';
import { EditorFieldsService } from '../../../services/editor-fields.service';
import { InsertPublicationsDialog } from '../../insert-publications-dialog/insert-publications-dialog';
import { LinkDialog } from '../../link-dialog/link-dialog';
import { PublicationField } from './publication-field';

class StubFacade {
  readonly editableNow = signal(true);
  readonly message = signal<Message>({ id: 'm', descs: [{ lang: 'en' }, { lang: 'da' }] });
  setMessageDesc = vi.fn();
  applyPublicationsUpdate = vi.fn();
}

class StubFields {
  readonly publicationExpanded = signal(true);
  isExpanded = (fieldId: string) =>
    fieldId === 'publication' ? this.publicationExpanded() : false;
  edited = vi.fn(() => false);
  registerLabel = vi.fn();
  unregisterLabel = vi.fn();
  expand = vi.fn();
  collapse = vi.fn();
  toggle = vi.fn();
  focusNext = vi.fn();
  focusPrevious = vi.fn();
}

const languageStub = {
  selectedLanguage: signal('en'),
  modelLanguages: [
    { value: 'en', label: 'English', flagPath: 'en.png' },
    { value: 'da', label: 'Dansk', flagPath: 'da.png' },
  ],
};

@Component({
  imports: [PublicationField],
  template: `<app-publication-field />`,
})
class HostComponent {}

/** Wait for all N lazily-mounted TipTap surfaces (the floating imports `whenStable` misses). */
async function waitForEditors(fixture: ComponentFixture<unknown>, count: number): Promise<void> {
  for (let i = 0; i < 300; i++) {
    if (fixture.nativeElement.querySelectorAll('.ProseMirror').length >= count) {
      await fixture.whenStable();
      return;
    }
    await new Promise((resolve) => setTimeout(resolve, 0));
  }
  throw new Error('editors never mounted');
}

function setup() {
  const facade = new StubFacade();
  const fields = new StubFields();
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({
    providers: [
      provideTestTransloco(),
      provideNiordIcons(),
      { provide: EditorFacade, useValue: facade },
      { provide: EditorFieldsService, useValue: fields },
      { provide: LanguageService, useValue: languageStub },
      { provide: AuthFacade, useValue: { hasRole: () => signal(true) } },
      // The insert dialog scopes its picker to the desk the citation is written
      // from. Stubbed, so the live facade's domain catalogue fetch stays out of a
      // spec about the field.
      {
        provide: DomainFacade,
        useValue: {
          sessionDomain: signal({ domainId: 'niord-nm' } as Domain),
        } as unknown as DomainFacade,
      },
      {
        provide: MessagesApiService,
        useValue: {
          extractMessagePublication: vi.fn(() => of(null)),
          updateMessagePublications: vi.fn(() => of(facade.message())),
        },
      },
      {
        provide: PublicationsApiService,
        useValue: { getByIds: vi.fn(() => of([])), search: vi.fn(() => of([])) },
      },
      { provide: NotificationService, useValue: { error: vi.fn() } },
    ],
  });
  TestBed.inject(TranslocoService).load('en').subscribe();

  const fixture = TestBed.createComponent(HostComponent);
  fixture.autoDetectChanges();
  const el = fixture.nativeElement as HTMLElement;
  const editors = () => Array.from(el.querySelectorAll('app-rich-text-editor'));
  const rtes = () =>
    fixture.debugElement
      .queryAll(By.directive(RichTextEditor))
      .map((d) => d.componentInstance as RichTextEditor);
  return { fixture, facade, fields, el, editors, rtes };
}

describe('PublicationField', () => {
  afterEach(() => {
    document.querySelectorAll('.cdk-overlay-container').forEach((elm) => elm.replaceChildren());
  });

  it('renders an External + Internal inline editor per configured language (N=2 → 4 editors)', async () => {
    const { fixture, editors } = setup();
    await fixture.whenStable();
    expect(editors().length).toBe(4);
  });

  it('mounts no editor while the row is collapsed', async () => {
    const { fixture, fields, editors } = setup();
    fields.publicationExpanded.set(false);
    await fixture.whenStable();
    expect(editors().length).toBe(0);
  });

  it('routes an External edit to setMessageDesc({ publication }) and an Internal edit to { internalPublication }', async () => {
    const { fixture, facade, rtes } = setup();
    await waitForEditors(fixture, 4);

    // Template order per language is External then Internal; language order is [en, da].
    rtes()[0].insertContentAtCursor('EXT'); // en External
    rtes()[1].insertContentAtCursor('INT'); // en Internal
    await fixture.whenStable();

    const external = facade.setMessageDesc.mock.calls.find(
      (c) => 'publication' in (c[1] as object),
    );
    const internal = facade.setMessageDesc.mock.calls.find(
      (c) => 'internalPublication' in (c[1] as object),
    );
    expect(external?.[0]).toBe('en');
    expect(external?.[1].publication).toContain('EXT');
    expect(internal?.[0]).toBe('en');
    expect(internal?.[1].internalPublication).toContain('INT');
  });

  it('preserves a server publication=<id> span through an edit (inline round-trip)', async () => {
    const { fixture, facade } = setup();
    facade.message.set({
      id: 'm',
      descs: [{ lang: 'en', publication: '<span publication="7">Notices to Mariners</span>' }],
    });
    await waitForEditors(fixture, 2);

    const external = fixture.debugElement.queryAll(By.directive(RichTextEditor))[0]
      .componentInstance as RichTextEditor;
    external.insertContentAtCursor('See ');
    await fixture.whenStable();

    const call = facade.setMessageDesc.mock.calls.find((c) => 'publication' in (c[1] as object));
    expect(call?.[1].publication).toContain('publication="7"');
    expect(call?.[1].publication).toContain('See ');
  });

  it('projects an Insert-Publications button into each of the four editor toolbars', async () => {
    const { fixture, el } = setup();
    await waitForEditors(fixture, 4);

    const buttons = el.querySelectorAll('button[aria-label="editor.dialog.publications.insert"]');
    expect(buttons.length).toBe(4);
  });

  it('opens the dialog with the invoking editor kind/language and its cursor publication id', async () => {
    const { fixture, el, rtes } = setup();
    await waitForEditors(fixture, 4);

    // Template order: [en External, en Internal, da External, da Internal].
    vi.spyOn(rtes()[0], 'publicationIdAtSelection').mockReturnValue('pub-9');
    const buttons = el.querySelectorAll<HTMLButtonElement>(
      'button[aria-label="editor.dialog.publications.insert"]',
    );
    buttons[0].click();
    await fixture.whenStable();

    const dialog = fixture.debugElement.query(By.directive(InsertPublicationsDialog))
      .componentInstance as InsertPublicationsDialog;
    expect(dialog.visible()).toBe(true);
    expect(dialog.kind()).toBe('EXTERNAL');
    expect(dialog.lang()).toBe('en');
    expect(dialog.publicationId()).toBe('pub-9');
  });

  it('passes INTERNAL + the second language for the da Internal editor button', async () => {
    const { fixture, el, rtes } = setup();
    await waitForEditors(fixture, 4);

    vi.spyOn(rtes()[3], 'publicationIdAtSelection').mockReturnValue(undefined);
    const buttons = el.querySelectorAll<HTMLButtonElement>(
      'button[aria-label="editor.dialog.publications.insert"]',
    );
    buttons[3].click();
    await fixture.whenStable();

    const dialog = fixture.debugElement.query(By.directive(InsertPublicationsDialog))
      .componentInstance as InsertPublicationsDialog;
    expect(dialog.kind()).toBe('INTERNAL');
    expect(dialog.lang()).toBe('da');
    expect(dialog.publicationId()).toBeUndefined();
  });

  it('opens the Insert/Edit-link dialog from an editor toolbar and applies the result to that editor', async () => {
    const { fixture, el, rtes } = setup();
    await waitForEditors(fixture, 4);

    // The shared link toolbar button (one per editor) opens the modal, not a browser prompt.
    const linkButtons = el.querySelectorAll<HTMLButtonElement>(
      'button[aria-label="shared.richText.link"]',
    );
    expect(linkButtons.length).toBe(4);

    const applySpy = vi.spyOn(rtes()[0], 'applyLink');
    linkButtons[0].click();
    await fixture.whenStable();

    const dialog = fixture.debugElement.query(By.directive(LinkDialog))
      .componentInstance as LinkDialog;
    expect(dialog.visible()).toBe(true);

    const result = { href: 'https://z.test', text: 'Chart', title: '', target: '_blank' };
    dialog.confirmed.emit(result);
    await fixture.whenStable();

    expect(applySpy).toHaveBeenCalledWith(result);
  });

  it('renders the collapsed viewer as sanitized publication HTML', async () => {
    const { fixture, facade, fields, el } = setup();
    fields.publicationExpanded.set(false);
    facade.message.set({
      id: 'm',
      descs: [{ lang: 'en', publication: '<span>Danish Chart Catalogue</span>' }],
    });
    await fixture.whenStable();

    const viewer = el.querySelector('[fieldViewer]') as HTMLElement;
    expect(viewer.querySelector('app-message-publication')).not.toBeNull();
    expect(viewer.textContent).toContain('Danish Chart Catalogue');
  });
});
