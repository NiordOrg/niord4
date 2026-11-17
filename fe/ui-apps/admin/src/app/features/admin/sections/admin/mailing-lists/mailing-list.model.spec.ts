import { describe, expect, it } from 'vitest';

import {
  dateToTimeString,
  formToTrigger,
  newTriggerForm,
  timeStringToDate,
  triggerToForm,
  type ScheduledTrigger,
  type StatusChangeTrigger,
  type TriggerFormModel,
} from './mailing-list.model';

const LANGS = ['en', 'da'];

describe('mailing-list trigger conversions', () => {
  it('timeStringToDate / dateToTimeString round-trip; invalid input → null/undefined', () => {
    const date = timeStringToDate('14:05');
    expect(date?.getHours()).toBe(14);
    expect(date?.getMinutes()).toBe(5);
    expect(dateToTimeString(date)).toBe('14:05');
    expect(timeStringToDate('25:00')).toBeNull();
    expect(timeStringToDate(undefined)).toBeNull();
    expect(dateToTimeString(null)).toBeUndefined();
  });

  it('triggerToForm seeds a STATUS_CHANGE trigger: enabled per desc presence, statuses from array', () => {
    const trigger: StatusChangeTrigger = {
      type: 'STATUS_CHANGE',
      descs: [{ lang: 'en', subject: 'Hi' }], // da absent → disabled
      scriptResourcePaths: ['a.ftl'],
      statusChanges: ['PUBLISHED', 'EXPIRED'],
      messageFilter: 'x > 1',
    };
    const form = triggerToForm(trigger, LANGS);
    expect(form.subjects.find((s) => s.lang === 'en')).toMatchObject({
      subject: 'Hi',
      enabled: true,
    });
    expect(form.subjects.find((s) => s.lang === 'da')).toMatchObject({
      subject: '',
      enabled: false,
    });
    expect(form.statusPublished).toBe(true);
    expect(form.statusCancelled).toBe(false);
    expect(form.statusExpired).toBe(true);
    expect(form.messageFilter).toBe('x > 1');
  });

  it('triggerToForm preserves an unconfigured-language subject at the end', () => {
    const trigger: StatusChangeTrigger = {
      type: 'STATUS_CHANGE',
      descs: [{ lang: 'fo', subject: 'Føroyskt' }],
      scriptResourcePaths: [],
      statusChanges: [],
    };
    const form = triggerToForm(trigger, LANGS);
    expect(form.subjects.map((s) => s.lang)).toEqual(['en', 'da', 'fo']);
    expect(form.subjects[2]).toMatchObject({ lang: 'fo', enabled: true });
  });

  it('formToTrigger (STATUS_CHANGE) drops disabled subjects + builds statusChanges; no scheduled fields', () => {
    const form: TriggerFormModel = {
      ...newTriggerForm(LANGS),
      type: 'STATUS_CHANGE',
      subjects: [
        { lang: 'en', subject: 'Hi', enabled: true },
        { lang: 'da', subject: 'Hej', enabled: false },
      ],
      statusPublished: true,
      statusCancelled: true,
      statusExpired: false,
      messageFilter: 'f',
      scriptResourcePaths: ['a.ftl'],
    };
    const wire = formToTrigger(form) as StatusChangeTrigger;
    expect(wire.type).toBe('STATUS_CHANGE');
    expect(wire.descs).toEqual([{ lang: 'en', subject: 'Hi' }]); // disabled da dropped
    expect(wire.statusChanges).toEqual(['PUBLISHED', 'CANCELLED']);
    expect(wire.messageFilter).toBe('f');
    expect('scheduleType' in wire).toBe(false); // inactive variant's fields absent
  });

  it('formToTrigger (SCHEDULED) serializes time as HH:mm; no status fields', () => {
    const form: TriggerFormModel = {
      ...newTriggerForm(LANGS),
      type: 'SCHEDULED',
      subjects: [{ lang: 'en', subject: 'S', enabled: true }],
      scheduleType: 'MONDAY',
      scheduledExecutionTime: timeStringToDate('09:30'),
      scheduledExecutionTimeZone: 'Europe/Copenhagen',
      messageQuery: 'q',
      publicReport: true,
    };
    const wire = formToTrigger(form) as ScheduledTrigger;
    expect(wire.type).toBe('SCHEDULED');
    expect(wire.scheduleType).toBe('MONDAY');
    expect(wire.scheduledExecutionTime).toBe('09:30');
    expect(wire.scheduledExecutionTimeZone).toBe('Europe/Copenhagen');
    expect(wire.publicReport).toBe(true);
    expect('statusChanges' in wire).toBe(false);
  });

  it('newTriggerForm: STATUS_CHANGE, PUBLISHED pre-checked, expanded, every language enabled', () => {
    const form = newTriggerForm(LANGS);
    expect(form.type).toBe('STATUS_CHANGE');
    expect(form.statusPublished).toBe(true);
    expect(form.expanded).toBe(true);
    expect(form.subjects.map((s) => s.lang)).toEqual([...LANGS]); // configured-list order
    expect(form.subjects.every((s) => s.enabled)).toBe(true);
  });
});
