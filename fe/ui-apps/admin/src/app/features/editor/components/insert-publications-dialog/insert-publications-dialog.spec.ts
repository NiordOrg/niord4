import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { TranslocoService } from '@jsverse/transloco';
import { of, throwError } from 'rxjs';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { DomainFacade } from '../../../../core/domain/facades/domain.facade';
import type { Domain } from '../../../../core/domain/models/domain.model';
import { NotificationService } from '../../../../core/notifications/notification.service';
import { LanguageService } from '../../../../core/main-shell/services/language.service';
import { provideNiordIcons } from '../../../../shared/icons/niord-icons';
import { provideTestTransloco } from '../../../../shared/testing/transloco-testing';
import type { Message } from '../../../messages/models/message.model';
import type { PublicationRef } from '../../../messages/models/messages-filter-params.model';
import { MessagesApiService } from '../../../messages/services/api/messages-api.service';
import { PublicationsApiService } from '../../../messages/services/api/publications-api.service';
import { PublicationsField } from '../../../messages/components/publications-field/publications-field';
import { EditorFacade } from '../../services/editor-facade';
import { InsertPublicationsDialog, type PublicationKind } from './insert-publications-dialog';

const MESSAGE: Message = { id: 'm', descs: [{ lang: 'en' }, { lang: 'da' }] };

/** The desk the citation is being written from. */
const sessionDomain = signal<Domain | undefined>({ domainId: 'niord-nm' } as Domain);

const PUB_WITH_PARAMS: PublicationRef = {
  publicationId: 'p1',
  type: 'MESSAGE_REPORT',
  descs: [
    { lang: 'en', title: 'Notices', messagePublicationFormat: 'Publication no. ${parameters}' },
  ],
};

const PUB_NO_PARAMS: PublicationRef = {
  publicationId: 'p2',
  type: 'MESSAGE_REPORT',
  descs: [
    { lang: 'en', title: 'Chart catalogue', messagePublicationFormat: 'See the chart catalogue' },
  ],
};

/**
 * The one publication a message may give its own link: a LINK publication that is
 * not produced per language and carries no link of its own.
 */
const PUB_LINK_OVERRIDABLE: PublicationRef = {
  publicationId: 'p3',
  type: 'LINK',
  languageSpecific: false,
  descs: [
    { lang: 'en', title: 'A link publication' },
    { lang: 'da', title: 'En link-publikation' },
  ],
};

/** Same publication, but it already points somewhere — there is nothing to supply. */
const PUB_LINK_WITH_OWN_LINK: PublicationRef = {
  ...PUB_LINK_OVERRIDABLE,
  publicationId: 'p4',
  descs: [
    { lang: 'en', title: 'A link publication' },
    { lang: 'da', title: 'En link-publikation', link: 'https://example.test/da' },
  ],
};

/** One document per language: a single free-text link could only be wrong in all but one. */
const PUB_LINK_LANGUAGE_SPECIFIC: PublicationRef = {
  ...PUB_LINK_OVERRIDABLE,
  publicationId: 'p5',
  languageSpecific: true,
};

/** A legacy-half row: the payload does not carry the flag, so the rule is unevaluable. */
const PUB_LINK_LEGACY: PublicationRef = {
  publicationId: 'p6',
  type: 'LINK',
  descs: [{ lang: 'en', title: 'A legacy link publication' }],
};

class StubFacade {
  readonly message = signal<Message | undefined>(MESSAGE);
  applyPublicationsUpdate = vi.fn();
}

const languageStub = {
  selectedLanguage: signal('en'),
  modelLanguages: [
    { value: 'en', label: 'English', flagPath: 'en.png', locale: 'en-GB' },
    { value: 'da', label: 'Dansk', flagPath: 'da.png', locale: 'da' },
  ],
};

@Component({
  imports: [InsertPublicationsDialog],
  template: `
    <app-insert-publications-dialog
      [(visible)]="visible"
      [kind]="kind()"
      [lang]="lang()"
      [publicationId]="publicationId()"
    />
  `,
})
class Host {
  readonly visible = signal(false);
  readonly kind = signal<PublicationKind>('EXTERNAL');
  readonly lang = signal('en');
  readonly publicationId = signal<string | undefined>(undefined);
}

let api: {
  extractMessagePublication: ReturnType<typeof vi.fn>;
  updateMessagePublications: ReturnType<typeof vi.fn>;
};
let publicationsApi: {
  getByIds: ReturnType<typeof vi.fn>;
  search: ReturnType<typeof vi.fn>;
};
let notify: { error: ReturnType<typeof vi.fn> };
let facade: StubFacade;

function setup() {
  api = {
    extractMessagePublication: vi.fn(() => of(null)),
    updateMessagePublications: vi.fn(() => of(MESSAGE)),
  };
  publicationsApi = {
    getByIds: vi.fn(() => of([] as PublicationRef[])),
    search: vi.fn(() => of([] as PublicationRef[])),
  };
  notify = { error: vi.fn() };
  facade = new StubFacade();

  TestBed.resetTestingModule();
  TestBed.configureTestingModule({
    providers: [
      provideTestTransloco(),
      provideNiordIcons(),
      { provide: MessagesApiService, useValue: api },
      { provide: PublicationsApiService, useValue: publicationsApi },
      { provide: NotificationService, useValue: notify },
      { provide: EditorFacade, useValue: facade },
      { provide: LanguageService, useValue: languageStub },
      // The picker is scoped to the desk the citation is written from. Stubbed
      // rather than real: the live facade reaches AuthFacade -> Keycloak.
      { provide: DomainFacade, useValue: { sessionDomain } as unknown as DomainFacade },
    ],
  });
  TestBed.inject(TranslocoService).load('en').subscribe();

  const fixture = TestBed.createComponent(Host);
  fixture.autoDetectChanges();
  return fixture;
}

const panel = () => document.querySelector('.dialog-panel');
const paramsInput = () =>
  document.querySelector<HTMLInputElement>(
    'input[aria-label="editor.dialog.publications.parameters"]',
  );
const linkInput = () =>
  document.querySelector<HTMLInputElement>('input[aria-label="editor.dialog.publications.link"]');
const updateButtons = () =>
  Array.from(document.querySelectorAll<HTMLButtonElement>('.dialog-foot .btn-primary'));

function pickField(fixture: ReturnType<typeof setup>, pub: PublicationRef) {
  const field = fixture.debugElement.query(By.directive(PublicationsField))
    .componentInstance as PublicationsField;
  field.onSelect([pub]);
}

afterEach(() => {
  document.querySelectorAll('.cdk-overlay-container').forEach((el) => el.replaceChildren());
});

describe('InsertPublicationsDialog', () => {
  it('opens in INSERT mode (no publicationId) without resolving a publication', async () => {
    const fixture = setup();
    fixture.componentInstance.visible.set(true);
    await fixture.whenStable();

    expect(panel()).not.toBeNull();
    expect(publicationsApi.getByIds).not.toHaveBeenCalled();
    // No publication picked → no parameter/link fields, Update disabled.
    expect(paramsInput()).toBeNull();
    expect(linkInput()).toBeNull();
    expect(updateButtons().every((b) => b.disabled)).toBe(true);
  });

  /**
   * A citation names something a reader is meant to go and look up, so the picker
   * offers only released publications — narrower than the message filter, which
   * also offers the issue an editor is currently working on.
   */
  it('configures the publications field for the EXTERNAL channel (messagePublication, no type, PUBLISHED only)', async () => {
    const fixture = setup();
    fixture.componentInstance.visible.set(true);
    await fixture.whenStable();

    const field = fixture.debugElement.query(By.directive(PublicationsField))
      .componentInstance as PublicationsField;
    expect(field.messagePublication()).toBe('EXTERNAL');
    expect(field.type()).toBeNull();
    expect(field.statuses()).toEqual(['PUBLISHED']);
    // The desk the citation is written from decides what may be cited: a
    // publication is offered where it is owned and wherever its owner shared it.
    expect(field.domain()).toBe('niord-nm');
    // Released issues only, so the badge says the same word on every row and the
    // series and date are qualifiers nobody is choosing by inside this dialog.
    expect(field.nameOnly()).toBe(true);
  });

  it('passes the INTERNAL channel through to the publications field', async () => {
    const fixture = setup();
    fixture.componentInstance.kind.set('INTERNAL');
    fixture.componentInstance.visible.set(true);
    await fixture.whenStable();

    const field = fixture.debugElement.query(By.directive(PublicationsField))
      .componentInstance as PublicationsField;
    expect(field.messagePublication()).toBe('INTERNAL');
  });

  it('EDIT mode resolves the publication by id and prefills parameters from the extract', async () => {
    const fixture = setup();
    publicationsApi.getByIds.mockReturnValue(of([PUB_WITH_PARAMS]));
    api.extractMessagePublication.mockReturnValue(of({ parameters: 'A,B', link: undefined }));

    fixture.componentInstance.publicationId.set('p1');
    fixture.componentInstance.visible.set(true);
    await fixture.whenStable();

    expect(publicationsApi.getByIds).toHaveBeenCalledWith(['p1']);
    expect(api.extractMessagePublication).toHaveBeenCalledWith(MESSAGE, 'p1', 'en');
    expect(paramsInput()).not.toBeNull();
    expect(paramsInput()!.value).toBe('A,B');
    expect(updateButtons().every((b) => b.disabled)).toBe(false);
  });

  it('a null extract answer leaves the fields empty (nothing to prefill) without a toast', async () => {
    const fixture = setup();
    publicationsApi.getByIds.mockReturnValue(of([PUB_WITH_PARAMS]));
    api.extractMessagePublication.mockReturnValue(of(null));

    fixture.componentInstance.publicationId.set('p1');
    fixture.componentInstance.visible.set(true);
    await fixture.whenStable();

    expect(paramsInput()).not.toBeNull();
    expect(paramsInput()!.value).toBe('');
    expect(notify.error).not.toHaveBeenCalled();
  });

  it('shows the parameters field only for a publication whose template carries ${parameters}', async () => {
    const fixture = setup();
    fixture.componentInstance.visible.set(true);
    await fixture.whenStable();

    pickField(fixture, PUB_WITH_PARAMS);
    await fixture.whenStable();
    expect(paramsInput()).not.toBeNull();

    pickField(fixture, PUB_NO_PARAMS);
    await fixture.whenStable();
    expect(paramsInput()).toBeNull();
  });

  /**
   * The per-message link override, under all three of its conditions.
   *
   * It exists to supply a destination the publication does not have — not to let one
   * message quietly point somewhere else, and not to overwrite one of several
   * per-language documents with a single free-text URL.
   */
  it('offers the link override only for a shared-link LINK publication that has no link of its own', async () => {
    const fixture = setup();
    fixture.componentInstance.visible.set(true);
    await fixture.whenStable();

    pickField(fixture, PUB_LINK_OVERRIDABLE);
    await fixture.whenStable();
    expect(linkInput()).not.toBeNull();

    // Already points somewhere.
    pickField(fixture, PUB_LINK_WITH_OWN_LINK);
    await fixture.whenStable();
    expect(linkInput()).toBeNull();

    // One document per language.
    pickField(fixture, PUB_LINK_LANGUAGE_SPECIFIC);
    await fixture.whenStable();
    expect(linkInput()).toBeNull();

    // A publication whose citation is not an anchor at all.
    pickField(fixture, PUB_WITH_PARAMS);
    await fixture.whenStable();
    expect(linkInput()).toBeNull();
  });

  it('does not offer the link override when the publication cannot say whether it is language-specific', async () => {
    const fixture = setup();
    fixture.componentInstance.visible.set(true);
    await fixture.whenStable();

    pickField(fixture, PUB_LINK_LEGACY);
    await fixture.whenStable();
    expect(linkInput()).toBeNull();
  });

  it('Update all languages omits the lang param and applies the response + closes', async () => {
    const fixture = setup();
    const response: Message = { id: 'm', descs: [{ lang: 'en', publication: 'X' }] };
    api.updateMessagePublications.mockReturnValue(of(response));

    fixture.componentInstance.visible.set(true);
    await fixture.whenStable();
    pickField(fixture, PUB_WITH_PARAMS);
    await fixture.whenStable();

    updateButtons()[0].click();
    await fixture.whenStable();

    expect(api.updateMessagePublications).toHaveBeenCalledTimes(1);
    const [msgArg, optionsArg] = api.updateMessagePublications.mock.calls[0];
    expect(msgArg).toBe(MESSAGE);
    expect(optionsArg.publicationId).toBe('p1');
    expect(optionsArg.lang).toBeUndefined();
    expect(facade.applyPublicationsUpdate).toHaveBeenCalledWith(response);
    expect(fixture.componentInstance.visible()).toBe(false);
  });

  /**
   * The parameterised citation, end to end.
   *
   * Every citation-only publication in the estate renders through `${parameters}`
   * ("VragID ${parameters}", "J.nr. ${parameters}"), and a broken one is publicly
   * visible and removable only by hand-editing rich text, message by message.
   */
  it('writes the typed ${parameters} value into the citation update', async () => {
    const fixture = setup();
    fixture.componentInstance.visible.set(true);
    await fixture.whenStable();
    pickField(fixture, PUB_WITH_PARAMS);
    await fixture.whenStable();

    const input = paramsInput()!;
    input.value = '2026/117';
    input.dispatchEvent(new Event('input'));
    await fixture.whenStable();

    updateButtons()[0].click();
    await fixture.whenStable();

    const [, optionsArg] = api.updateMessagePublications.mock.calls[0];
    expect(optionsArg.parameters).toBe('2026/117');
  });

  /**
   * A citation into something that was later withdrawn must still open.
   *
   * The hydration lookup applies no status narrowing, so the dialog gets the row
   * back; narrowing it here would blank exactly the citations that need explaining.
   */
  it('EDIT mode resolves a retired publication rather than opening empty', async () => {
    const fixture = setup();
    publicationsApi.getByIds.mockReturnValue(
      of([
        {
          publicationId: 'p9',
          type: 'MESSAGE_REPORT',
          status: 'RETIRED',
          descs: [{ lang: 'en', title: 'A withdrawn issue' }],
        } satisfies PublicationRef,
      ]),
    );

    fixture.componentInstance.publicationId.set('p9');
    fixture.componentInstance.visible.set(true);
    await fixture.whenStable();

    expect(publicationsApi.getByIds).toHaveBeenCalledWith(['p9']);
    expect(updateButtons().every((b) => b.disabled)).toBe(false);
  });

  it('Update {lang} only passes the invoking language', async () => {
    const fixture = setup();
    fixture.componentInstance.lang.set('da');
    fixture.componentInstance.visible.set(true);
    await fixture.whenStable();
    pickField(fixture, PUB_WITH_PARAMS);
    await fixture.whenStable();

    updateButtons()[1].click();
    await fixture.whenStable();

    const [, optionsArg] = api.updateMessagePublications.mock.calls[0];
    expect(optionsArg.lang).toBe('da');
  });

  it('a failed update toasts and keeps the dialog open', async () => {
    const fixture = setup();
    api.updateMessagePublications.mockReturnValue(throwError(() => new Error('boom')));

    fixture.componentInstance.visible.set(true);
    await fixture.whenStable();
    pickField(fixture, PUB_WITH_PARAMS);
    await fixture.whenStable();

    updateButtons()[0].click();
    await fixture.whenStable();

    expect(notify.error).toHaveBeenCalledWith('editor.dialog.publications.updateError');
    expect(facade.applyPublicationsUpdate).not.toHaveBeenCalled();
    expect(fixture.componentInstance.visible()).toBe(true);
  });
});
