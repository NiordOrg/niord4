import { TestBed } from '@angular/core/testing';
import { describe, expect, it, beforeEach } from 'vitest';

import { MessageDetailsRenderer } from './message-details-renderer';
import { MessageDescriptionService } from '../../services/message-description.service';
import { MessageRenderingService } from '../../services/message-rendering.service';
import { MessageAttachmentTypeService } from '../../services/message-attachment-type.service';
import { MessageDateFormatterService } from '../../services/message-date-formatter.service';
import { MessageUrlBuilderService } from '../../services/message-url-builder.service';
import { LanguageService } from '../../../../core/main-shell/services/language.service';
import { APP_CONFIG } from '../../../../core/config/app-config.token';
import {
  AttachmentDisplay,
  Message,
  MessageAttachment,
  MessagePart,
} from '../../models/message.model';
import { provideTestTransloco } from '../../../../shared/testing/transloco-testing';

const att = (display: AttachmentDisplay | undefined, fileName: string): MessageAttachment => ({
  path: `messages/x/${fileName}`,
  fileName,
  type: 'image/png',
  display,
});

const part = (type: string, details: string): MessagePart => ({
  type,
  descs: [{ lang: 'en', details }],
});

const baseMessage: Message = {
  id: 'uuid-1',
  shortId: 'NW-001-25',
  mainType: 'NW',
  status: 'PUBLISHED',
  descs: [{ lang: 'en', title: 'Test message' }],
};

describe('MessageDetailsRenderer', () => {
  function createComponent(message: Message) {
    const fixture = TestBed.createComponent(MessageDetailsRenderer);
    fixture.componentRef.setInput('message', message);
    fixture.detectChanges();
    return { fixture, comp: fixture.componentInstance };
  }

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [MessageDetailsRenderer],
      providers: [
        provideTestTransloco(),
        { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest' } },
        {
          provide: LanguageService,
          useValue: { selectedLanguage: () => 'en', activeLocale: () => 'en-GB' },
        },
        MessageDescriptionService,
        MessageRenderingService,
        MessageAttachmentTypeService,
        MessageDateFormatterService,
        MessageUrlBuilderService,
      ],
    });
  });

  describe('title', () => {
    it('should resolve from descs via MessageDescriptionService', () => {
      const { comp } = createComponent(baseMessage);
      expect(comp.title()).toBe('Test message');
    });

    it('should be empty when no descs are present', () => {
      const { comp } = createComponent({ ...baseMessage, descs: undefined });
      expect(comp.title()).toBe('');
    });
  });

  describe('language-parametric rendering', () => {
    const bilingualTitle: Message = {
      ...baseMessage,
      descs: [
        { lang: 'en', title: 'English title' },
        { lang: 'da', title: 'Dansk titel' },
      ],
    };

    const bilingualPart: MessagePart = {
      type: 'DETAILS',
      descs: [
        { lang: 'en', subject: 'English subject', details: 'English details' },
        { lang: 'da', subject: 'Dansk emne', details: 'Danske detaljer' },
      ],
    };

    it('defaults desc selection to the app language when no language input is set', () => {
      // LanguageService mock reports 'en' → the default (unset) path stays behavior-identical.
      const { comp } = createComponent(bilingualTitle);
      expect(comp.language()).toBeUndefined();
      expect(comp.title()).toBe('English title');
    });

    it('resolves the title at an explicitly passed language', () => {
      const fixture = TestBed.createComponent(MessageDetailsRenderer);
      fixture.componentRef.setInput('message', bilingualTitle);
      fixture.componentRef.setInput('language', 'da');
      fixture.detectChanges();
      expect(fixture.componentInstance.title()).toBe('Dansk titel');
    });

    it('threads the language into a child (part subject/details) and swaps content when it flips', () => {
      const fixture = TestBed.createComponent(MessageDetailsRenderer);
      fixture.componentRef.setInput('message', { ...baseMessage, parts: [bilingualPart] });
      fixture.detectChanges();

      // Default (no language) → app language 'en'.
      let text = fixture.nativeElement.textContent as string;
      expect(text).toContain('English subject');
      expect(text).toContain('English details');
      expect(text).not.toContain('Dansk emne');

      // Flip to Danish → the child re-resolves its descs at the passed language.
      fixture.componentRef.setInput('language', 'da');
      fixture.detectChanges();
      text = fixture.nativeElement.textContent as string;
      expect(text).toContain('Dansk emne');
      expect(text).toContain('Danske detaljer');
      expect(text).not.toContain('English subject');
    });
  });

  describe('field-table presence', () => {
    it('hides every field row when the message has no data beyond title/badge', () => {
      const { fixture } = createComponent(baseMessage);
      const html = fixture.nativeElement.innerHTML;
      expect(html).not.toContain('messages.field.references');
      expect(html).not.toContain('messages.field.attachments');
      expect(html).not.toContain('messages.field.charts');
      expect(html).not.toContain('messages.field.publication');
      expect(fixture.nativeElement.querySelector('.text-right')).toBeNull();
    });

    it('renders the references row when references are present', () => {
      const { fixture } = createComponent({
        ...baseMessage,
        references: [{ messageId: 'NW-005-25', type: 'REFERENCE' }],
      });
      expect(fixture.nativeElement.innerHTML).toContain('messages.field.references');
    });

    it('renders the attachments row (toggle button only) when attachments are present', () => {
      const { fixture, comp } = createComponent({
        ...baseMessage,
        attachments: [att(undefined, 'a.png')],
      });
      const html = fixture.nativeElement.innerHTML;
      expect(html).toContain('messages.field.attachments');
      expect(html).toContain('name="lucidePaperclip"');
      expect(comp.showAttachments()).toBe(false);
      expect(html).not.toContain('app-message-attachment-list');
    });

    it('renders the charts row when charts are present', () => {
      const { fixture } = createComponent({
        ...baseMessage,
        charts: [{ chartNumber: '101' }],
      });
      expect(fixture.nativeElement.innerHTML).toContain('messages.field.charts');
    });

    it('renders the publication row when description.publication is present', () => {
      const { fixture } = createComponent({
        ...baseMessage,
        descs: [{ lang: 'en', title: 'X', publication: '<p>pub</p>' }],
      });
      expect(fixture.nativeElement.innerHTML).toContain('messages.field.publication');
    });

    it('renders the source line text when descs[0].source is present', () => {
      const { fixture } = createComponent({
        ...baseMessage,
        descs: [{ lang: 'en', title: 'X', source: 'Some authority' }],
      });
      expect(fixture.nativeElement.textContent).toContain('Some authority');
    });

    it('uses a <dl> for the labelled field rows', () => {
      const { fixture } = createComponent({
        ...baseMessage,
        references: [{ messageId: 'NW-005-25', type: 'REFERENCE' }],
      });
      const html = fixture.nativeElement.innerHTML;
      expect(html).toContain('<dl');
      expect(html).toContain('<dt');
      expect(html).toContain('<dd');
    });

    it('renders the source line when only publishDateFrom is present (no source)', () => {
      const { fixture } = createComponent({
        ...baseMessage,
        publishDateFrom: '2026-05-13T10:00:00Z',
      });
      expect(fixture.nativeElement.textContent).toContain('msg.field.published');
    });

    it('does NOT render the source line for unpublished messages even with a publishDateFrom', () => {
      const { fixture } = createComponent({
        ...baseMessage,
        status: 'DRAFT',
        publishDateFrom: '2026-05-13T10:00:00Z',
      });
      expect(fixture.nativeElement.textContent).not.toContain('msg.field.published');
    });
  });

  describe('showPromulgation', () => {
    const withProm: Message = {
      ...baseMessage,
      promulgations: [
        {
          '@class': 'org.niord.core.promulgation.vo.NavtexMessagePromulgationVo',
          type: { typeId: 'navtex', serviceId: 'navtex', name: 'NAVTEX' },
          promulgate: true,
        },
      ],
    };

    it('does not render the promulgation block by default', () => {
      const { fixture } = createComponent(withProm);
      expect(fixture.nativeElement.innerHTML).not.toContain('app-promulgation-list');
      expect(fixture.nativeElement.innerHTML).not.toContain('messages.promulgation.heading');
    });

    it('renders the promulgation block (heading + list) when [showPromulgation] is set', () => {
      const fixture = TestBed.createComponent(MessageDetailsRenderer);
      fixture.componentRef.setInput('message', withProm);
      fixture.componentRef.setInput('showPromulgation', true);
      fixture.detectChanges();
      expect(fixture.nativeElement.innerHTML).toContain('app-promulgation-list');
      expect(fixture.nativeElement.innerHTML).toContain('messages.promulgation.heading');
    });

    it('renders nothing when [showPromulgation] is set but the message has no promulgations', () => {
      const fixture = TestBed.createComponent(MessageDetailsRenderer);
      fixture.componentRef.setInput('message', baseMessage);
      fixture.componentRef.setInput('showPromulgation', true);
      fixture.detectChanges();
      expect(fixture.nativeElement.innerHTML).not.toContain('app-promulgation-list');
    });
  });

  describe('part type labels', () => {
    it('shows a label on the first part and on every part whose type differs from the previous', () => {
      const { comp } = createComponent({
        ...baseMessage,
        parts: [part('DETAILS', 'a'), part('DETAILS', 'b'), part('TIME', 'c'), part('TIME', 'd')],
      });
      const parts = comp.parts();
      expect(comp.isFirstOfType(parts, 0)).toBe(true);
      expect(comp.isFirstOfType(parts, 1)).toBe(false);
      expect(comp.isFirstOfType(parts, 2)).toBe(true);
      expect(comp.isFirstOfType(parts, 3)).toBe(false);
    });

    it('partTypeLabel resolves the lowercased type key under messages.field.part', () => {
      const { comp } = createComponent(baseMessage);
      expect(comp.partTypeLabel(part('DETAILS', ''))).toContain('msg.field.details');
      expect(comp.partTypeLabel(part('TIME', ''))).toContain('msg.field.time');
    });

    it('returns empty string when part type is undefined', () => {
      const { comp } = createComponent(baseMessage);
      expect(comp.partTypeLabel({ descs: [] })).toBe('');
    });
  });

  describe('attachments toggle', () => {
    it('hides the attachment list by default', () => {
      const { fixture, comp } = createComponent({
        ...baseMessage,
        attachments: [att(undefined, 'a.png')],
      });
      expect(comp.showAttachments()).toBe(false);
      expect(fixture.nativeElement.innerHTML).not.toContain('app-message-attachment-list');
    });

    it('renders the attachment list after toggleAttachments() flips the signal', () => {
      const { fixture, comp } = createComponent({
        ...baseMessage,
        attachments: [att(undefined, 'a.png')],
      });
      comp.toggleAttachments();
      fixture.detectChanges();
      expect(comp.showAttachments()).toBe(true);
      expect(fixture.nativeElement.innerHTML).toContain('app-message-attachment-list');
    });

    it('attachmentsToggleLabel resolves to the show key while hidden and the hide key while shown', () => {
      const { fixture, comp } = createComponent({
        ...baseMessage,
        attachments: [att(undefined, 'a.png')],
      });
      expect(comp.attachmentsToggleLabel()).toContain('msg.show_attachments');
      comp.toggleAttachments();
      fixture.detectChanges();
      expect(comp.attachmentsToggleLabel()).toContain('msg.hide_attachments');
    });
  });

  describe('inline ABOVE/BELOW attachment placement', () => {
    it('renders an ABOVE attachment inline as the first article child, BELOW after the field table', () => {
      const { fixture } = createComponent({
        ...baseMessage,
        parts: [part('DETAILS', 'body text')],
        attachments: [
          att('BELOW', 'below.png'),
          att('ABOVE', 'above.png'),
          att(undefined, 'plain.png'),
        ],
      });
      const article = fixture.nativeElement.querySelector('article') as HTMLElement;
      expect(article.firstElementChild?.tagName).toBe('APP-MESSAGE-ATTACHMENT');
      // The inline instances render the source figure (full-size image), not a thumbnail.
      const figures = article.querySelectorAll('app-message-attachment figure');
      expect(figures.length).toBe(2);
      // BELOW sits after the field table (dl), before the source line.
      const children = [...article.children].map((el) => el.tagName);
      expect(children.indexOf('DL')).toBeLessThan(children.lastIndexOf('APP-MESSAGE-ATTACHMENT'));
    });

    it('renders no inline attachment when none carry a display placement', () => {
      const { fixture } = createComponent({
        ...baseMessage,
        attachments: [att(undefined, 'plain.png')],
      });
      expect(fixture.nativeElement.querySelectorAll('figure').length).toBe(0);
    });
  });
});
