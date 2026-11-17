import { Component, DebugElement, signal, viewChild } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { disabled, form, FormField } from '@angular/forms/signals';
import { By } from '@angular/platform-browser';
import { of } from 'rxjs';
import { vi } from 'vitest';

import { MessageSeriesField } from './message-series-field';
import { MessageSeriesApiService } from '../../services/api/message-series-api.service';
import { LanguageService } from '../../../../core/main-shell/services/language.service';
import { APP_CONFIG } from '../../../../core/config/app-config.token';
import { MessageSeries } from '../../models/message.model';
import { provideTestTransloco } from '../../../../shared/testing/transloco-testing';

const mockSeries: MessageSeries = { seriesId: 'dma-nw' };
const mockSeries2: MessageSeries = { seriesId: 'dma-nm' };

describe('MessageSeriesField', () => {
  let component: MessageSeriesField;
  let getByIdsSpy: ReturnType<typeof vi.fn>;
  let searchSpy: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    getByIdsSpy = vi.fn().mockReturnValue(of([]));
    searchSpy = vi.fn().mockReturnValue(of([]));

    TestBed.configureTestingModule({
      imports: [MessageSeriesField],
      providers: [
        { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest' } },
        { provide: LanguageService, useValue: { selectedLanguage: () => 'en' } },
        {
          provide: MessageSeriesApiService,
          useValue: { searchMessageSeries: searchSpy, getMessageSeriesByIds: getByIdsSpy },
        },
        provideTestTransloco(),
      ],
    });

    const fixture = TestBed.createComponent(MessageSeriesField);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  describe('value model', () => {
    it('holds a single series value', () => {
      component.value.set(mockSeries);
      expect(component.value()).toEqual(mockSeries);
    });

    it('clears to null', () => {
      component.value.set(mockSeries);
      component.value.set(null);
      expect(component.value()).toBeNull();
    });

    it('holds an array of series for multiple mode', () => {
      component.value.set([mockSeries, mockSeries2]);
      expect(component.value()).toEqual([mockSeries, mockSeries2]);
    });
  });

  describe('onSelect', () => {
    it('should update value and emit valueChange', () => {
      const changes: (MessageSeries | readonly MessageSeries[] | null)[] = [];
      component.value.subscribe((v) => changes.push(v));

      component.onSelect(mockSeries);

      expect(component.value()).toEqual(mockSeries);
      expect(changes).toEqual([mockSeries]);
    });

    it('should emit touch', () => {
      let touched = false;
      component.touch.subscribe(() => {
        touched = true;
      });

      component.onSelect(mockSeries);

      expect(touched).toBe(true);
    });
  });

  describe('onClear', () => {
    it('should reset to null in single mode', () => {
      component.value.set(mockSeries);
      const changes: (MessageSeries | readonly MessageSeries[] | null)[] = [];
      component.value.subscribe((v) => changes.push(v));

      component.onClear();

      expect(component.value()).toBeNull();
      expect(changes).toEqual([null]);
    });
  });

  describe('initialIds resolution', () => {
    it('should not call API when initialIds is null', () => {
      expect(getByIdsSpy).not.toHaveBeenCalled();
    });

    it('should call API and emit when initialIds are provided', async () => {
      getByIdsSpy.mockReturnValue(of([mockSeries]));
      const fixture = TestBed.createComponent(MessageSeriesField);
      const comp = fixture.componentInstance;
      const resolved: MessageSeries[][] = [];
      comp.initialIdsResolved.subscribe((s) => resolved.push(s));

      fixture.componentRef.setInput('initialIds', ['dma-nw']);
      fixture.detectChanges();
      await fixture.whenStable();

      expect(getByIdsSpy).toHaveBeenCalledWith(['dma-nw']);
      expect(resolved).toEqual([[mockSeries]]);
    });

    it('should not duplicate API calls when the same IDs are re-emitted', async () => {
      getByIdsSpy.mockReturnValue(of([mockSeries]));
      const fixture = TestBed.createComponent(MessageSeriesField);

      fixture.componentRef.setInput('initialIds', ['dma-nw']);
      fixture.detectChanges();
      await fixture.whenStable();

      // Re-emit same array instance (same IDs) — distinctUntilChanged should suppress
      fixture.componentRef.setInput('initialIds', ['dma-nw']);
      fixture.detectChanges();
      await fixture.whenStable();

      expect(getByIdsSpy).toHaveBeenCalledTimes(1);
    });
  });
});

@Component({
  selector: 'app-host',
  imports: [MessageSeriesField, FormField],
  template: `<app-message-series-field [formField]="form.series" />`,
})
class HostComponent {
  private readonly data = signal<{ series: MessageSeries | MessageSeries[] | null }>({
    series: null,
  });
  readonly form = form(this.data);
  readonly field = viewChild.required(MessageSeriesField);
}

@Component({
  selector: 'app-host-disabled',
  imports: [MessageSeriesField, FormField],
  template: `<app-message-series-field [formField]="form.series" />`,
})
class DisabledHostComponent {
  private readonly data = signal<{ series: MessageSeries | MessageSeries[] | null }>({
    series: null,
  });
  readonly form = form(this.data, (path) => disabled(path.series, { when: () => true }));
  readonly field = viewChild.required(MessageSeriesField);
}

describe('MessageSeriesField — Signal Forms round trip', () => {
  let host: HostComponent;
  let fixture: ReturnType<typeof TestBed.createComponent<HostComponent>>;
  let fieldDebug: DebugElement;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HostComponent],
      providers: [
        { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest' } },
        { provide: LanguageService, useValue: { selectedLanguage: () => 'en' } },
        {
          provide: MessageSeriesApiService,
          useValue: {
            searchMessageSeries: vi.fn().mockReturnValue(of([])),
            getMessageSeriesByIds: vi.fn().mockReturnValue(of([])),
          },
        },
        provideTestTransloco(),
      ],
    });

    fixture = TestBed.createComponent(HostComponent);
    host = fixture.componentInstance;
    fixture.detectChanges();
    fieldDebug = fixture.debugElement.query(By.directive(MessageSeriesField));
  });

  it('should push the form field value down to the field', () => {
    host.form.series().value.set(mockSeries);
    fixture.detectChanges();

    expect(host.field().value()).toEqual(mockSeries);
  });

  it('should bubble field selections up to the form field', () => {
    host.field().onSelect(mockSeries);
    fixture.detectChanges();

    expect(host.form.series().value()).toEqual(mockSeries);
  });

  it('should mark the form field touched after a selection', () => {
    expect(host.form.series().touched()).toBe(false);

    host.field().onSelect(mockSeries);
    fixture.detectChanges();

    expect(host.form.series().touched()).toBe(true);
  });

  it('should reflect the form-driven disabled state', () => {
    const disabledFixture = TestBed.createComponent(DisabledHostComponent);
    disabledFixture.detectChanges();
    expect(disabledFixture.componentInstance.field().disabled()).toBe(true);
  });

  it('should mount the MessageSeriesField via [formField] without errors', () => {
    expect(fieldDebug).toBeTruthy();
  });
});
