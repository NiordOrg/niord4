import { Component, DebugElement, signal, viewChild } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { disabled, form, FormField } from '@angular/forms/signals';
import { By } from '@angular/platform-browser';
import { of } from 'rxjs';
import { vi } from 'vitest';

import { MessageIdField } from './message-id-field';
import { MessagesApiService } from '../../services/api/messages-api.service';
import { LanguageService } from '../../../../core/main-shell/services/language.service';
import { APP_CONFIG } from '../../../../core/config/app-config.token';
import { MessageIdSearchResult } from '../../models/message-id-search-result.model';
import { provideTestTransloco } from '../../../../shared/testing/transloco-testing';

const mockResult: MessageIdSearchResult = {
  messageId: 'NW-001-25',
  type: 'SHORT_ID',
  status: 'PUBLISHED',
  title: 'Test notice',
};

describe('MessageIdField', () => {
  let component: MessageIdField;
  let fixture: ComponentFixture<MessageIdField>;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [MessageIdField],
      providers: [
        { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest' } },
        { provide: LanguageService, useValue: { selectedLanguage: () => 'en' } },
        provideTestTransloco(),
        {
          provide: MessagesApiService,
          useValue: { searchMessageIds: vi.fn() },
        },
      ],
    });

    fixture = TestBed.createComponent(MessageIdField);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  describe('value model', () => {
    it('holds the message id', () => {
      component.value.set('NW-001-25');
      expect(component.value()).toBe('NW-001-25');
    });

    it('clears to null', () => {
      component.value.set('NW-001-25');
      component.value.set(null);
      expect(component.value()).toBeNull();
    });

    it('reflects the form-driven disabled input', () => {
      fixture.componentRef.setInput('disabled', true);
      expect(component.disabled()).toBe(true);
      fixture.componentRef.setInput('disabled', false);
      expect(component.disabled()).toBe(false);
    });
  });

  describe('onSelect', () => {
    it('should store messageId and emit the full result', () => {
      const changes: (string | null)[] = [];
      const emitted: (MessageIdSearchResult | null)[] = [];
      component.value.subscribe((v) => changes.push(v));
      component.selectedResultChange.subscribe((r) => emitted.push(r));

      component.onSelect(mockResult);

      expect(component.value()).toBe('NW-001-25');
      expect(changes).toEqual(['NW-001-25']);
      expect(emitted).toEqual([mockResult]);
    });

    it('should emit touch on select', () => {
      let touched = false;
      component.touch.subscribe(() => {
        touched = true;
      });

      component.onSelect(mockResult);

      expect(touched).toBe(true);
    });

    it('should capture the selected message status for chip coloring', () => {
      component.onSelect(mockResult);

      expect(component.selectedStatus()).toBe('PUBLISHED');
      expect(component.statusStateClass()).toBe('is-published');
    });

    it('should leave selectedStatus null when the result has no status', () => {
      const noStatus: MessageIdSearchResult = { messageId: 'NW-002-25', type: 'ID' };

      component.onSelect(noStatus);

      expect(component.selectedStatus()).toBeNull();
      expect(component.statusStateClass()).toBe('');
    });

    it('should leave selectedStatus null when the result has an unknown status string', () => {
      const odd: MessageIdSearchResult = { messageId: 'NW-003-25', status: 'CUSTOM' };

      component.onSelect(odd);

      expect(component.selectedStatus()).toBeNull();
    });
  });

  describe('chip rendering toggle', () => {
    it('should show chip when value is set and allowFreeText is false', () => {
      component.value.set('NW-001-25');

      expect(component.showAsChip()).toBe(true);
    });

    it('should NOT show chip when allowFreeText is true', () => {
      fixture.componentRef.setInput('allowFreeText', true);
      component.value.set('NW-001-25');

      expect(component.showAsChip()).toBe(false);
    });

    it('should NOT show chip when value is null', () => {
      expect(component.showAsChip()).toBe(false);
    });
  });

  describe('onClear', () => {
    it('should reset value and emit null result', () => {
      component.value.set('NW-001-25');
      const changes: (string | null)[] = [];
      const emitted: (MessageIdSearchResult | null)[] = [];
      component.value.subscribe((v) => changes.push(v));
      component.selectedResultChange.subscribe((r) => emitted.push(r));

      component.onClear();

      expect(component.value()).toBeNull();
      expect(changes).toEqual([null]);
      expect(emitted).toEqual([null]);
    });

    it('should also reset selectedStatus', () => {
      component.onSelect(mockResult);
      expect(component.selectedStatus()).toBe('PUBLISHED');

      component.onClear();

      expect(component.selectedStatus()).toBeNull();
    });
  });

  describe('onInput — allowFreeText disabled (default)', () => {
    it('should not update value when allowFreeText is false', () => {
      const changes: (string | null)[] = [];
      component.value.subscribe((v) => changes.push(v));

      component.onInput('typed');

      expect(component.value()).toBeNull();
      expect(changes).toHaveLength(0);
    });
  });

  describe('onInput — allowFreeText enabled', () => {
    beforeEach(() => {
      fixture.componentRef.setInput('allowFreeText', true);
    });

    it('should set value to typed text', () => {
      const changes: (string | null)[] = [];
      component.value.subscribe((v) => changes.push(v));

      component.onInput('manual-id');

      expect(component.value()).toBe('manual-id');
      expect(changes).toEqual(['manual-id']);
    });

    it('should set value to null when input is cleared', () => {
      component.value.set('previous');
      const changes: (string | null)[] = [];
      component.value.subscribe((v) => changes.push(v));

      component.onInput('');

      expect(component.value()).toBeNull();
      expect(changes).toEqual([null]);
    });

    it('should emit null selectedResult when typing free text', () => {
      const emitted: (MessageIdSearchResult | null)[] = [];
      component.selectedResultChange.subscribe((r) => emitted.push(r));

      component.onInput('free');

      expect(emitted).toEqual([null]);
    });
  });

  describe('statusLabel', () => {
    it('should return translation key for known statuses', () => {
      expect(component.statusLabel('PUBLISHED')).toBe('msg.status.PUBLISHED');
      expect(component.statusLabel('DRAFT')).toBe('msg.status.DRAFT');
      expect(component.statusLabel('CANCELLED')).toBe('msg.status.CANCELLED');
    });

    it('should return raw value for unknown statuses', () => {
      expect(component.statusLabel('CUSTOM_STATUS')).toBe('CUSTOM_STATUS');
    });

    it('should return empty string for undefined', () => {
      expect(component.statusLabel(undefined)).toBe('');
    });
  });

  describe('matchTypeLabel', () => {
    it('should return translation key for ID', () => {
      expect(component.matchTypeLabel('ID')).toBe('messages.matchType.ID');
    });

    it('should return translation key for SHORT_ID', () => {
      expect(component.matchTypeLabel('SHORT_ID')).toBe('messages.matchType.SHORT_ID');
    });

    it('should return translation key for TEXT', () => {
      expect(component.matchTypeLabel('TEXT')).toBe('messages.matchType.TEXT');
    });

    it('should return raw value for unknown match types', () => {
      expect(component.matchTypeLabel('CUSTOM_TYPE')).toBe('CUSTOM_TYPE');
    });

    it('should return empty string for undefined', () => {
      expect(component.matchTypeLabel(undefined)).toBe('');
    });
  });

  describe('onSearch', () => {
    it('should clear suggestions when query is shorter than minLength', () => {
      component.suggestions.set([mockResult]);

      component.onSearch('NW');

      expect(component.suggestions()).toHaveLength(0);
    });

    it('should not clear suggestions when query meets minLength (debounced)', () => {
      component.suggestions.set([mockResult]);

      component.onSearch('NW-');

      // search$ is debounced — suggestions remain until the async response arrives
      expect(component.suggestions()).toHaveLength(1);
    });
  });
});

@Component({
  selector: 'app-host',
  imports: [MessageIdField, FormField],
  template: `<app-message-id-field [formField]="form.messageId" />`,
})
class HostComponent {
  private readonly data = signal<{ messageId: string | null }>({ messageId: null });
  readonly form = form(this.data);
  readonly field = viewChild.required(MessageIdField);
}

@Component({
  selector: 'app-host-disabled',
  imports: [MessageIdField, FormField],
  template: `<app-message-id-field [formField]="form.messageId" />`,
})
class DisabledHostComponent {
  private readonly data = signal<{ messageId: string | null }>({ messageId: null });
  readonly form = form(this.data, (path) => disabled(path.messageId, { when: () => true }));
  readonly field = viewChild.required(MessageIdField);
}

describe('MessageIdField — Signal Forms round trip', () => {
  let host: HostComponent;
  let fixture: ComponentFixture<HostComponent>;
  let fieldDebug: DebugElement;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HostComponent],
      providers: [
        { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest' } },
        { provide: LanguageService, useValue: { selectedLanguage: () => 'en' } },
        provideTestTransloco(),
        {
          provide: MessagesApiService,
          useValue: { searchMessageIds: vi.fn() },
        },
      ],
    });

    fixture = TestBed.createComponent(HostComponent);
    host = fixture.componentInstance;
    fixture.detectChanges();
    fieldDebug = fixture.debugElement.query(By.directive(MessageIdField));
  });

  it('should push the form field value down to the field', () => {
    host.form.messageId().value.set('NW-001-25');
    fixture.detectChanges();

    expect(host.field().value()).toBe('NW-001-25');
  });

  it('should reflect a form-driven clear on the field', () => {
    host.form.messageId().value.set('NW-001-25');
    fixture.detectChanges();
    host.form.messageId().value.set(null);
    fixture.detectChanges();

    expect(host.field().value()).toBeNull();
  });

  it('should reflect the form-driven disabled state', () => {
    const disabledFixture = TestBed.createComponent(DisabledHostComponent);
    disabledFixture.detectChanges();
    expect(disabledFixture.componentInstance.field().disabled()).toBe(true);
  });

  it('should bubble field selections up to the form field', () => {
    const result: MessageIdSearchResult = {
      messageId: 'NW-002-25',
      type: 'ID',
      status: 'PUBLISHED',
    };

    host.field().onSelect(result);
    fixture.detectChanges();

    expect(host.form.messageId().value()).toBe('NW-002-25');
  });

  it('should mark the form field touched after a selection', () => {
    expect(host.form.messageId().touched()).toBe(false);

    host.field().onSelect({ messageId: 'NW-002-25' } as MessageIdSearchResult);
    fixture.detectChanges();

    expect(host.form.messageId().touched()).toBe(true);
  });

  it('should mount the MessageIdField via [formField] without errors', () => {
    expect(fieldDebug).toBeTruthy();
  });
});

/** The editor's References row: a free-text field whose value is bound in and out. */
@Component({
  selector: 'app-free-text-host',
  imports: [MessageIdField],
  template: `<app-message-id-field [allowFreeText]="true" [(value)]="messageId" />`,
})
class FreeTextHostComponent {
  readonly messageId = signal<string | null>(null);
}

describe('MessageIdField — free text, picking a suggestion', () => {
  const found: MessageIdSearchResult = {
    messageId: 'NM-819-26',
    type: 'SHORT_ID',
    status: 'PUBLISHED',
    title: 'A notice',
  };

  let fixture: ComponentFixture<FreeTextHostComponent>;

  beforeEach(async () => {
    TestBed.configureTestingModule({
      imports: [FreeTextHostComponent],
      providers: [
        { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest' } },
        { provide: LanguageService, useValue: { selectedLanguage: () => 'en' } },
        provideTestTransloco(),
        {
          provide: MessagesApiService,
          useValue: { searchMessageIds: vi.fn(() => of([found])) },
        },
      ],
    });
    fixture = TestBed.createComponent(FreeTextHostComponent);
    fixture.autoDetectChanges();
    await fixture.whenStable();
  });

  afterEach(() => {
    document.querySelectorAll('.cdk-overlay-container').forEach((el) => el.replaceChildren());
  });

  const box = () => fixture.nativeElement.querySelector('.ac-input') as HTMLInputElement;

  async function typeAndPick(typed: string): Promise<void> {
    box().value = typed;
    box().dispatchEvent(new Event('input'));
    await fixture.whenStable();
    // The search is debounced; wait for its row.
    await vi.waitFor(() => expect(document.querySelector('.ac-panel .ac-option')).not.toBeNull());
    document
      .querySelector('.ac-panel .ac-option')!
      .dispatchEvent(new MouseEvent('mousedown', { bubbles: true, cancelable: true }));
    await fixture.whenStable();
  }

  it('keeps the id in the box when the whole id was typed and then picked', async () => {
    await typeAndPick('NM-819-26');

    expect(fixture.componentInstance.messageId()).toBe('NM-819-26');
    expect(box().value).toBe('NM-819-26');
    // The box holds text, so its clear button is offered.
    expect(fixture.nativeElement.querySelector('.ac-clear')).not.toBeNull();
  });

  it('shows the picked id in the box when only part of it was typed', async () => {
    await typeAndPick('NM-819');

    expect(fixture.componentInstance.messageId()).toBe('NM-819-26');
    expect(box().value).toBe('NM-819-26');
  });
});
