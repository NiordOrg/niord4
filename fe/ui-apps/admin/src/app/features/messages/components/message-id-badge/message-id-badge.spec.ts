import { TestBed } from '@angular/core/testing';

import { MessageIdBadge } from './message-id-badge';
import { LanguageService } from '../../../../core/main-shell/services/language.service';
import { Message } from '../../models/message.model';
import { provideTestTransloco } from '../../../../shared/testing/transloco-testing';

describe('MessageIdBadge', () => {
  function createComponent(message: Message) {
    const fixture = TestBed.createComponent(MessageIdBadge);
    fixture.componentRef.setInput('message', message);
    fixture.detectChanges();
    return fixture.componentInstance;
  }

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [MessageIdBadge],
      providers: [
        provideTestTransloco(),
        { provide: LanguageService, useValue: { selectedLanguage: () => 'en' } },
      ],
    });
  });

  describe('displayValue', () => {
    it('should use shortId when present', () => {
      const comp = createComponent({ id: 'uuid-1', shortId: 'NW-001-25', mainType: 'NW' });
      expect(comp.displayValue()).toBe('NW-001-25');
    });

    // Regression: draft messages without a shortId previously fell back to the
    // message title, which duplicated the title text into the ID column and
    // visually collapsed the layout. The badge now renders a
    // "<typeLabel> <mainType>" placeholder instead.
    it('falls back to "<typeLabel> <mainType>" when shortId is absent', () => {
      const comp = createComponent({
        id: 'uuid-1',
        mainType: 'NM',
        type: 'PERMANENT_NOTICE',
        descs: [{ title: 'Should NOT appear in the badge' }],
      });
      // provideTestTransloco returns the raw key, so the translation slot reads
      // as the i18n key; production renders the localized label.
      expect(comp.displayValue()).toBe('msg.type.PERMANENT_NOTICE NM');
    });

    it('falls back to just mainType when type is missing', () => {
      const comp = createComponent({ id: 'uuid-1', mainType: 'NW' });
      expect(comp.displayValue()).toBe('NW');
    });

    it('returns empty when neither shortId nor type nor mainType is set', () => {
      const comp = createComponent({ id: 'uuid-only' });
      expect(comp.displayValue()).toBe('');
    });

    it('never includes the suffix in the styled value', () => {
      const comp = createComponent({
        id: 'uuid-1',
        shortId: 'NM-042-25',
        mainType: 'NM',
        type: 'TEMPORARY_NOTICE',
      });
      expect(comp.displayValue()).toBe('NM-042-25');
      expect(comp.displayValue()).not.toContain('(T)');
    });
  });

  describe('suffix', () => {
    it('returns "(T)" for NM TEMPORARY_NOTICE (leading space trimmed; gap handled by layout)', () => {
      const comp = createComponent({
        id: 'uuid-1',
        shortId: 'NM-042-25',
        mainType: 'NM',
        type: 'TEMPORARY_NOTICE',
      });
      expect(comp.suffix()).toBe('(T)');
    });

    it('returns "(P)" for NM PRELIMINARY_NOTICE', () => {
      const comp = createComponent({
        id: 'uuid-1',
        shortId: 'NM-043-25',
        mainType: 'NM',
        type: 'PRELIMINARY_NOTICE',
      });
      expect(comp.suffix()).toBe('(P)');
    });

    it('is empty for NM PERMANENT_NOTICE', () => {
      const comp = createComponent({
        id: 'uuid-1',
        shortId: 'NM-044-25',
        mainType: 'NM',
        type: 'PERMANENT_NOTICE',
      });
      expect(comp.suffix()).toBe('');
    });

    it('is empty for NW messages even with TEMPORARY_NOTICE-shaped type', () => {
      const comp = createComponent({
        id: 'uuid-1',
        shortId: 'NW-001-25',
        mainType: 'NW',
        type: 'TEMPORARY_NOTICE',
      });
      expect(comp.suffix()).toBe('');
    });

    it('is empty when shortId is absent (fallback path)', () => {
      const comp = createComponent({
        id: 'uuid-1',
        mainType: 'NM',
        type: 'TEMPORARY_NOTICE',
        descs: [{ title: 'Untitled' }],
      });
      expect(comp.suffix()).toBe('');
    });
  });

  describe('template', () => {
    it('renders the suffix outside the status badge as plain text', () => {
      const fixture = TestBed.createComponent(MessageIdBadge);
      fixture.componentRef.setInput('message', {
        id: 'uuid-1',
        shortId: 'NM-042-25',
        mainType: 'NM',
        type: 'TEMPORARY_NOTICE',
      });
      fixture.detectChanges();
      const badge = fixture.nativeElement.querySelector('span.status-badge');
      expect(badge?.textContent ?? '').toContain('NM-042-25');
      expect(badge?.textContent ?? '').not.toContain('(T)');
      const wrapper = fixture.nativeElement.querySelector('span.whitespace-nowrap');
      expect(wrapper?.textContent ?? '').toContain('(T)');
    });

    it('applies a Layer 3 state class derived from the message status', () => {
      const fixture = TestBed.createComponent(MessageIdBadge);
      fixture.componentRef.setInput('message', {
        id: 'uuid-1',
        shortId: 'NW-001-25',
        mainType: 'NW',
        status: 'PUBLISHED',
      });
      fixture.detectChanges();
      const badge = fixture.nativeElement.querySelector('span.status-badge');
      // StatusClassPipe maps 'PUBLISHED' → 'is-published'; the widget reads
      // --status-* from that locally-scoped state class.
      expect(badge?.classList.contains('is-published')).toBe(true);
    });

    it('omits the state class when the message has no status (falls back to muted neutral)', () => {
      const fixture = TestBed.createComponent(MessageIdBadge);
      fixture.componentRef.setInput('message', {
        id: 'uuid-1',
        shortId: 'NW-001-25',
        mainType: 'NW',
      });
      fixture.detectChanges();
      const badge = fixture.nativeElement.querySelector('span.status-badge');
      // None of the .is-* classes should be present when status is undefined.
      const stateClasses = [
        'is-published',
        'is-draft',
        'is-verified',
        'is-cancelled',
        'is-expired',
        'is-deleted',
      ];
      for (const cls of stateClasses) {
        expect(badge?.classList.contains(cls)).toBe(false);
      }
    });
  });
});
