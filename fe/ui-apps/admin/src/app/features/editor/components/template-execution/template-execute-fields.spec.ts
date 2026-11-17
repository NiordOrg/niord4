import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { DomainFacade } from '../../../../core/domain/facades/domain.facade';
import { NotificationService } from '../../../../core/notifications/notification.service';
import { provideNiordIcons } from '../../../../shared/icons/niord-icons';
import { provideTestTransloco } from '../../../../shared/testing/transloco-testing';
import { provideTestSessionZone } from '../../../../shared/testing/zone-testing';
import type { Message } from '../../../messages/models/message.model';
import type {
  ParamType,
  SystemCategory,
  TemplateParamValues,
} from '../../../messages/models/template.model';
import { TemplateExecuteFields } from './template-execute-fields';

const point = () => ({
  type: 'Feature' as const,
  geometry: { type: 'Point' as const, coordinates: [12, 56] },
  properties: {},
});

@Component({
  imports: [TemplateExecuteFields],
  template: `
    <app-template-execute-fields
      [templates]="templates()"
      [paramTypes]="paramTypes()"
      lang="en"
      [(message)]="message"
      [(templateData)]="data"
    />
  `,
})
class Host {
  readonly templates = signal<readonly SystemCategory[]>([]);
  readonly paramTypes = signal<ReadonlyMap<string, ParamType>>(
    new Map([['text', { type: 'STANDARD', name: 'text' }]]),
  );
  readonly message = signal<Message>({ id: '', mainType: 'NW' });
  readonly data = signal<TemplateParamValues[]>([{ posDecimals: '1', positions: [] }]);
}

function tmpl(over: Partial<SystemCategory>): SystemCategory {
  return { id: 1, type: 'TEMPLATE', descs: [{ lang: 'en', name: 'T' }], ...over };
}

interface SetupOptions {
  /** No session domain, so nothing names the zone a whole day is measured in. */
  noSessionDomain?: boolean;
}

function setup(options: SetupOptions = {}) {
  const notifyError = vi.fn();
  TestBed.configureTestingModule({
    providers: [
      provideTestTransloco(),
      provideNiordIcons(),
      // UTC+5:30, no DST: an emitted instant landing on :30 can only have come from
      // resolving the picked day in the domain's zone.
      provideTestSessionZone('Asia/Kolkata'),
      {
        provide: DomainFacade,
        useValue: {
          sessionDomain: signal({
            domainId: 'd',
            // The same zone the pickers resolve their days in: the whole-day snap and the
            // picked day would otherwise be reasoned about in two different calendars.
            timeZone: options.noSessionDomain ? undefined : 'Asia/Kolkata',
            messageSeries: [{ seriesId: 's', mainType: 'NW', types: [] }],
          }),
        },
      },
      { provide: NotificationService, useValue: { error: notifyError, success: vi.fn() } },
    ],
  });
  const fixture = TestBed.createComponent(Host);
  fixture.autoDetectChanges();
  return {
    fixture,
    host: fixture.componentInstance,
    el: fixture.nativeElement as HTMLElement,
    notifyError,
  };
}

const paramFields = (el: HTMLElement) => el.querySelectorAll('app-template-param-field');

afterEach(() => {
  document.querySelectorAll('.cdk-overlay-container').forEach((n) => n.replaceChildren());
});

describe('TemplateExecuteFields', () => {
  it('renders a std field only when the template declares it', async () => {
    const { fixture, host, el } = setup();
    host.templates.set([tmpl({ stdTemplateFields: [], templateParams: [] })]);
    await fixture.whenStable();
    // The Base fields card renders only when the template declares a general std field.
    expect(el.textContent).not.toContain('editor.template.card.baseFields');

    host.templates.set([tmpl({ stdTemplateFields: ['type'], templateParams: [] })]);
    await fixture.whenStable();
    expect(el.textContent).toContain('editor.template.card.baseFields');
    expect(el.textContent).toContain('msg.field.type');
    expect(el.textContent).not.toContain('msg.field.areas');
  });

  it('writes a publish date as the domain zone reads the picked day', async () => {
    const { fixture, host, el } = setup();
    host.templates.set([tmpl({ stdTemplateFields: ['publish_date'], templateParams: [] })]);
    // 11 June 00:00 in Kolkata — so the calendar opens on June, not on the runner's month.
    host.message.set({ id: '', mainType: 'NW', publishDateFrom: '2026-06-10T18:30:00.000Z' });
    await fixture.whenStable();

    const trigger = el.querySelector<HTMLButtonElement>(
      'app-date-time-field app-date-picker button',
    )!;
    trigger.click();
    await fixture.whenStable();

    const cell = Array.from(document.querySelectorAll<HTMLButtonElement>('.calendar-day')).find(
      (day) => day.textContent?.trim() === '5' && !day.classList.contains('is-outside'),
    );
    cell!.click();
    await fixture.whenStable();

    // Midnight on 5 June for the domain, carried straight into the message as an instant.
    expect(host.message().publishDateFrom).toBe('2026-06-04T18:30:00.000Z');
  });

  /** A message part carrying one timed event-date row, ready for the all-day toggle. */
  function withTimedEventDate(): Message {
    return {
      id: '',
      mainType: 'NW',
      parts: [
        {
          type: 'DETAILS',
          geometry: { type: 'FeatureCollection', features: [] },
          eventDates: [
            {
              allDay: false,
              fromDate: '2026-06-10T09:30:00.000Z',
              toDate: '2026-06-10T15:45:00.000Z',
            },
          ],
        },
      ],
    };
  }

  /** The all-day checkbox of the first event-date row. */
  function allDayBox(el: HTMLElement): HTMLInputElement {
    return [...el.querySelectorAll<HTMLInputElement>('input[type="checkbox"]')].find((box) =>
      box.closest('label')?.textContent?.includes('editor.template.allDay'),
    )!;
  }

  /**
   * A day is a day in the domain's zone here too.
   *
   * Kolkata is UTC+5:30, so its whole-day bounds land on :30 — an hour no browser-local
   * midnight could produce, and 09:30Z on the 10th is already the 10th's afternoon there.
   */
  it('snaps an all-day event date to the whole day in the domain zone', async () => {
    const { fixture, host, el } = setup();
    host.message.set(withTimedEventDate());
    host.templates.set([tmpl({ stdTemplateFields: ['event_dates'], templateParams: [] })]);
    await fixture.whenStable();

    allDayBox(el).click();
    await fixture.whenStable();

    const interval = host.message().parts![0].eventDates![0];
    expect(interval.allDay).toBe(true);
    expect(interval.fromDate).toBe('2026-06-09T18:30:00.000Z');
    // The last MILLISECOND of the day: an inclusive bound at .000 would drop the final second.
    expect(interval.toDate).toBe('2026-06-10T18:29:59.999Z');
  });

  it('leaves the instants alone and warns when no domain gives a zone', async () => {
    const { fixture, host, el, notifyError } = setup({ noSessionDomain: true });
    host.message.set(withTimedEventDate());
    host.templates.set([tmpl({ stdTemplateFields: ['event_dates'], templateParams: [] })]);
    await fixture.whenStable();

    allDayBox(el).click();
    await fixture.whenStable();

    const interval = host.message().parts![0].eventDates![0];
    expect(interval.allDay).toBe(true);
    expect(interval.fromDate).toBe('2026-06-10T09:30:00.000Z');
    expect(interval.toDate).toBe('2026-06-10T15:45:00.000Z');
    expect(notifyError).toHaveBeenCalledWith('common.dateTime.noTimeZone');
  });

  it('always renders a normal (non-positionList) parameter', async () => {
    const { fixture, host, el } = setup();
    host.templates.set([tmpl({ templateParams: [{ paramId: 'x', type: 'text' }] })]);
    await fixture.whenStable();
    expect(paramFields(el)).toHaveLength(1);
  });

  it('hides a positionList parameter when there are no positions, and shows one per position', async () => {
    const { fixture, host, el } = setup();
    host.templates.set([
      tmpl({ templateParams: [{ paramId: 'fyr', type: 'text', positionList: true }] }),
    ]);
    await fixture.whenStable();
    // No geometry → no positions → the positionList param is hidden.
    expect(paramFields(el)).toHaveLength(0);

    // Two features on the part geometry → two derived positions → the param renders twice.
    host.message.set({
      id: '',
      mainType: 'NW',
      parts: [
        { type: 'DETAILS', geometry: { type: 'FeatureCollection', features: [point(), point()] } },
      ],
    });
    host.templates.set([
      tmpl({ id: 2, templateParams: [{ paramId: 'fyr', type: 'text', positionList: true }] }),
    ]);
    await fixture.whenStable();
    expect(paramFields(el)).toHaveLength(2);
  });

  it('serializes each position geometry into the nested {coordinates} feature list the templates iterate', async () => {
    const { fixture, host } = setup();
    host.message.set({
      id: '',
      mainType: 'NW',
      parts: [{ type: 'DETAILS', geometry: { type: 'FeatureCollection', features: [point()] } }],
    });
    host.templates.set([
      tmpl({ id: 2, templateParams: [{ paramId: 'fyr', type: 'text', positionList: true }] }),
    ]);
    await fixture.whenStable();

    const positions = host.data()[0].positions as { geometry: unknown }[];
    expect(positions).toHaveLength(1);
    // The shape the backend FreeMarker position-list templates require — NOT raw GeoJSON.
    expect(positions[0].geometry).toEqual([{ coordinates: [{ coordinates: [12, 56] }] }]);
  });
});
