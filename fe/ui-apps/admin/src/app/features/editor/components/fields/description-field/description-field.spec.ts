import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { TranslocoService } from '@jsverse/transloco';
import { of } from 'rxjs';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { AuthFacade } from '../../../../../core/auth/facades/auth.facade';
import { APP_CONFIG } from '../../../../../core/config/app-config.token';
import { LanguageService } from '../../../../../core/main-shell/services/language.service';
import { RichTextEditor } from '../../../../../shared/forms/rich-text-editor/rich-text-editor';
import { provideNiordIcons } from '../../../../../shared/icons/niord-icons';
import { provideTestTransloco } from '../../../../../shared/testing/transloco-testing';
import { MessagesApiService } from '../../../../messages/services/api/messages-api.service';
import type { Message, MessageDescription } from '../../../../messages/models/message.model';
import { MessageDescService } from '../../../services/message-desc.service';
import { EditorFacade } from '../../../services/editor-facade';
import { EditorFieldsService } from '../../../services/editor-fields.service';
import { DescriptionField } from './description-field';

class StubFacade {
  readonly editableNow = signal(true);
  readonly message = signal<Message>({
    id: 'm',
    parts: [{ type: 'DETAILS', descs: [{ lang: 'en', details: '' }] }],
  });
  setPartDetails = vi.fn();
  adjustDebounced = vi.fn();
}

class StubFields {
  readonly descriptionExpanded = signal(true);
  isExpanded = (fieldId: string) =>
    fieldId === 'description' ? this.descriptionExpanded() : false;
  edited = vi.fn(() => false);
  registerLabel = vi.fn();
  unregisterLabel = vi.fn();
  expand = vi.fn();
  collapse = vi.fn();
  toggle = vi.fn();
  focusNext = vi.fn();
  focusPrevious = vi.fn();
}

const twoLanguages = {
  selectedLanguage: signal('en'),
  modelLanguages: [
    { value: 'en', label: 'English', flagPath: 'en.png' },
    { value: 'da', label: 'Dansk', flagPath: 'da.png' },
  ],
};

const threeLanguages = {
  selectedLanguage: signal('en'),
  modelLanguages: [
    { value: 'en', label: 'English', flagPath: 'en.png' },
    { value: 'da', label: 'Dansk', flagPath: 'da.png' },
    { value: 'de', label: 'Deutsch', flagPath: 'de.png' },
  ],
};

const descStub = {
  descForLangOrDefault: (descs: MessageDescription[] | undefined, lang: string) =>
    descs?.find((d) => d.lang === lang) ?? descs?.[0],
  descForLanguage: (descs: MessageDescription[] | undefined, lang: string) =>
    descs?.find((d) => d.lang === lang),
};

@Component({
  imports: [DescriptionField],
  template: `<app-description-field [partIndex]="0" />`,
})
class HostComponent {}

/** Wait for N lazily-mounted TipTap surfaces (the floating imports `whenStable` misses). */
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

function setup(language: unknown = twoLanguages) {
  const facade = new StubFacade();
  const fields = new StubFields();
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({
    providers: [
      provideTestTransloco(),
      provideNiordIcons(),
      { provide: EditorFacade, useValue: facade },
      { provide: EditorFieldsService, useValue: fields },
      { provide: LanguageService, useValue: language },
      { provide: MessageDescService, useValue: descStub },
      { provide: AuthFacade, useValue: { hasRole: () => signal(true) } },
      { provide: MessagesApiService, useValue: { formatMessageGeometry: () => of('') } },
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord' } },
    ],
  });
  TestBed.inject(TranslocoService).load('en').subscribe();

  const fixture = TestBed.createComponent(HostComponent);
  fixture.autoDetectChanges();
  const el = fixture.nativeElement as HTMLElement;
  const editors = () => Array.from(el.querySelectorAll('app-rich-text-editor'));
  return { fixture, facade, fields, el, editors };
}

describe('DescriptionField', () => {
  afterEach(() => {
    document.querySelectorAll('.cdk-overlay-container').forEach((elm) => elm.replaceChildren());
  });

  it('renders one full-preset editor per configured model language (N=2)', async () => {
    const { fixture, editors } = setup();
    await fixture.whenStable();
    expect(editors().length).toBe(2);
  });

  it('scales to N languages — one editor each for a three-language config', async () => {
    const { fixture, editors } = setup(threeLanguages);
    await fixture.whenStable();
    expect(editors().length).toBe(3);
  });

  it('mounts no editor while the row is collapsed (never boots TipTap for a viewer)', async () => {
    const { fixture, fields, editors } = setup();
    fields.descriptionExpanded.set(false);
    await fixture.whenStable();
    expect(editors().length).toBe(0);
    expect(fixture.nativeElement.querySelector('.ProseMirror')).toBeNull();
  });

  it('routes a body edit to setPartDetails exactly once and queues NO adjust (single write path)', async () => {
    const { fixture, facade } = setup();
    await waitForEditors(fixture, 2);

    const rte = fixture.debugElement.queryAll(By.directive(RichTextEditor))[0]
      .componentInstance as RichTextEditor;
    rte.insertContentAtCursor('Wreck reported');
    await fixture.whenStable();

    expect(facade.setPartDetails).toHaveBeenCalledTimes(1);
    expect(facade.setPartDetails.mock.calls[0][0]).toBe(0);
    expect(facade.setPartDetails.mock.calls[0][1]).toBe('en');
    expect(facade.setPartDetails.mock.calls[0][2]).toContain('Wreck reported');
    // Body edits never drive server re-derivation — unlike the subject field.
    expect(facade.adjustDebounced).not.toHaveBeenCalled();
  });

  it('seeds the editors with a stored indent WITHOUT writing it back, and shows it on the surface', async () => {
    const { fixture, facade } = setup();
    facade.message.set({
      id: 'm',
      parts: [
        {
          type: 'DETAILS',
          descs: [{ lang: 'en', details: '<p style="padding-left: 30px">Indented</p>' }],
        },
      ],
    });
    await waitForEditors(fixture, 2);

    const indented = fixture.nativeElement.querySelector('.ProseMirror p') as HTMLElement;
    expect(indented.style.paddingLeft).toBe('30px');
    // Parsing the stored indent is not an edit: nothing is written back.
    expect(facade.setPartDetails).not.toHaveBeenCalled();

    const rte = fixture.debugElement.queryAll(By.directive(RichTextEditor))[0]
      .componentInstance as RichTextEditor;
    rte.insertContentAtCursor('!');
    await fixture.whenStable();

    expect(facade.setPartDetails).toHaveBeenCalledTimes(1);
    expect(facade.setPartDetails.mock.calls[0][2]).toContain('style="padding-left: 30px;"');
  });

  it('seeds the editors on load WITHOUT writing back (programmatic populate is not a user edit)', async () => {
    const { fixture, facade } = setup();
    facade.message.set({
      id: 'm',
      parts: [{ type: 'DETAILS', descs: [{ lang: 'en', details: '<p>Seed EN</p>' }] }],
    });
    await waitForEditors(fixture, 2);

    expect(facade.setPartDetails).not.toHaveBeenCalled();
    expect(facade.adjustDebounced).not.toHaveBeenCalled();
  });

  it('renders the collapsed viewer as sanitized HTML (drops script, keeps text)', async () => {
    const { fixture, facade, fields, el } = setup();
    fields.descriptionExpanded.set(false);
    facade.message.set({
      id: 'm',
      parts: [
        {
          type: 'DETAILS',
          descs: [{ lang: 'en', details: '<p>Visible body</p><script>alert(1)</script>' }],
        },
      ],
    });
    await fixture.whenStable();

    const viewer = el.querySelector('[fieldViewer]') as HTMLElement;
    expect(viewer.textContent).toContain('Visible body');
    expect(viewer.querySelector('script')).toBeNull();
  });

  it('renders the collapsed viewer as the other views do, keeping an indent and an alignment', async () => {
    const { fixture, facade, fields, el } = setup();
    fields.descriptionExpanded.set(false);
    facade.message.set({
      id: 'm',
      parts: [
        {
          type: 'DETAILS',
          descs: [
            {
              lang: 'en',
              details: '<p style="padding-left: 30px; text-align: center; color: red">Dates</p>',
            },
          ],
        },
      ],
    });
    await fixture.whenStable();

    const paragraph = el.querySelector('[fieldViewer] p') as HTMLElement;
    expect(paragraph.style.paddingLeft).toBe('30px');
    expect(paragraph.style.textAlign).toBe('center');
    expect(paragraph.style.color).toBe('');
  });
});
