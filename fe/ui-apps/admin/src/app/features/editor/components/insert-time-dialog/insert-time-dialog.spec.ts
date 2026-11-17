import { Component, Injectable, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import {
  provideTransloco,
  TranslocoLoader,
  TranslocoService,
  Translation,
} from '@jsverse/transloco';
import { Observable, of } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { DomainService } from '../../../../core/domain/services/domain.service';
import { LanguageService } from '../../../../core/main-shell/services/language.service';
import { provideNiordIcons } from '../../../../shared/icons/niord-icons';
import type { Message } from '../../../messages/models/message.model';
import { InsertTimeDialog } from './insert-time-dialog';

/** The date-interval phrases the dialog resolves (`shared.dateInterval.*` + the re-keyed `msg.time.*`). */
const BUNDLE: Translation = {
  shared: {
    dateInterval: {
      single: '{{value}}',
      sameDateRange: '{{date}}, {{fromTime}} - {{toTime}}',
      fromTo: '{{from}} - {{to}}',
    },
  },
  msg: {
    time: {
      until_further_notice: 'Until further notice',
      from_date: 'From {{fromDate}}',
      to_date: 'Until {{toDate}}',
    },
  },
};

@Injectable()
class BundleLoader implements TranslocoLoader {
  getTranslation(): Observable<Translation> {
    return of(BUNDLE);
  }
}

const languageStub = {
  modelLanguages: [
    { value: 'en', locale: 'en-GB', label: 'English', flagPath: '' },
    { value: 'da', locale: 'da', label: 'Dansk', flagPath: '' },
  ],
  selectedLanguage: signal('en'),
};

@Component({
  imports: [InsertTimeDialog],
  template: `
    <app-insert-time-dialog
      [(visible)]="visible"
      [message]="message()"
      [partIndex]="partIndex()"
      [lang]="lang()"
      (insertContent)="inserted.set($event)"
    />
  `,
})
class Host {
  readonly visible = signal(false);
  readonly message = signal<Message | undefined>(undefined);
  readonly partIndex = signal(0);
  readonly lang = signal('');
  readonly inserted = signal<string | undefined>(undefined);
}

async function setup(message: Message) {
  TestBed.configureTestingModule({
    providers: [
      provideTransloco({
        config: { availableLangs: ['en', 'da'], defaultLang: 'en', reRenderOnLangChange: true },
        loader: BundleLoader,
      }),
      provideNiordIcons(),
      { provide: LanguageService, useValue: languageStub },
      { provide: DomainService, useValue: { sessionDomain: () => undefined } },
    ],
  });
  TestBed.inject(TranslocoService).load('en').subscribe();

  const fixture = TestBed.createComponent(Host);
  fixture.componentInstance.message.set(message);
  fixture.autoDetectChanges();
  await fixture.whenStable();
  return fixture;
}

const q = (sel: string) => document.querySelector(sel);
const insertButton = () => q('.dialog-foot .btn-primary') as HTMLButtonElement;
const tzBox = () => q('#insert-time-tz') as HTMLInputElement | null;
const intervalBoxes = () =>
  Array.from(document.querySelectorAll<HTMLInputElement>('.dialog-body .checkbox')).filter(
    (box) => box.id !== 'insert-time-tz',
  );

beforeEach(() => localStorage.removeItem('formatTimeSettings'));
afterEach(() => {
  document.querySelectorAll('.cdk-overlay-container').forEach((el) => el.replaceChildren());
  localStorage.removeItem('formatTimeSettings');
});

describe('InsertTimeDialog', () => {
  it('renders safely while CLOSED for a part with event dates and no lang yet (no Intl throw)', async () => {
    // The projected dialog content is instantiated even while closed; before the first open the
    // `lang` input is still '' — the interval phrases must not compute (an empty Intl language
    // tag is a RangeError that aborts the WHOLE page render, the F5-walk regression).
    const fixture = await setup({
      id: 'm',
      parts: [
        { eventDates: [{ fromDate: '2026-07-04T10:00:00Z', toDate: '2026-07-05T10:00:00Z' }] },
      ],
    });
    // visible stays false, lang stays '' — creation + render must not throw.
    await fixture.whenStable();
    expect(document.querySelectorAll('.cdk-overlay-container input[type="checkbox"]')).toHaveLength(
      0,
    );

    // Opening with a real lang renders the interval list normally.
    fixture.componentInstance.lang.set('en');
    fixture.componentInstance.visible.set(true);
    await fixture.whenStable();
    expect(intervalBoxes().length).toBeGreaterThan(0);
  });

  it('previews "until further notice" when the part has no intervals', async () => {
    const fixture = await setup({ id: 'm', parts: [{ eventDates: [] }] });
    fixture.componentInstance.visible.set(true);
    await fixture.whenStable();

    insertButton().click();
    await fixture.whenStable();

    expect(fixture.componentInstance.inserted()).toBe('Until further notice');
  });

  it('auto-selects a sole interval and emits its phrase (not until-further-notice)', async () => {
    const fixture = await setup({
      id: 'm',
      parts: [{ eventDates: [{ allDay: true, fromDate: '2026-07-04', toDate: '2026-07-04' }] }],
    });
    fixture.componentInstance.visible.set(true);
    await fixture.whenStable();

    expect(intervalBoxes()).toHaveLength(1);
    expect(intervalBoxes()[0].checked).toBe(true);

    insertButton().click();
    await fixture.whenStable();

    expect(fixture.componentInstance.inserted()).not.toBe('Until further notice');
    expect(fixture.componentInstance.inserted()).toContain('2026');
  });

  it('changes the emitted output when the time-zone toggle flips', async () => {
    const fixture = await setup({
      id: 'm',
      parts: [
        {
          eventDates: [
            { allDay: false, fromDate: '2026-07-04T10:00:00Z', toDate: '2026-07-04T12:00:00Z' },
          ],
        },
      ],
    });

    fixture.componentInstance.visible.set(true);
    await fixture.whenStable();
    insertButton().click();
    await fixture.whenStable();
    const withZone = fixture.componentInstance.inserted();

    // Reopen and turn the time zone off.
    fixture.componentInstance.visible.set(false);
    await fixture.whenStable();
    fixture.componentInstance.visible.set(true);
    await fixture.whenStable();
    tzBox()!.click();
    await fixture.whenStable();
    insertButton().click();
    await fixture.whenStable();
    const withoutZone = fixture.componentInstance.inserted();

    expect(withZone).not.toBe(withoutZone);
  });

  it('joins multiple checked intervals with <br>', async () => {
    const fixture = await setup({
      id: 'm',
      parts: [
        {
          eventDates: [
            { allDay: true, fromDate: '2026-07-04', toDate: '2026-07-04' },
            { allDay: true, fromDate: '2026-08-01', toDate: '2026-08-05' },
          ],
        },
      ],
    });
    fixture.componentInstance.visible.set(true);
    await fixture.whenStable();

    // Two intervals → none auto-selected; check both.
    const boxes = intervalBoxes();
    expect(boxes).toHaveLength(2);
    boxes[0].click();
    boxes[1].click();
    await fixture.whenStable();

    insertButton().click();
    await fixture.whenStable();

    const html = fixture.componentInstance.inserted() ?? '';
    expect(html).toContain('<br>');
    expect(html.split('<br>').filter((part) => part.trim() !== '')).toHaveLength(2);
  });

  it('persists the time-zone preference and restores it on reopen', async () => {
    const fixture = await setup({
      id: 'm',
      parts: [{ eventDates: [{ allDay: true, fromDate: '2026-07-04', toDate: '2026-07-04' }] }],
    });

    fixture.componentInstance.visible.set(true);
    await fixture.whenStable();
    expect(tzBox()!.checked).toBe(true);

    tzBox()!.click();
    await fixture.whenStable();
    insertButton().click();
    await fixture.whenStable();

    expect(localStorage.getItem('formatTimeSettings')).toBe('{"tz":false}');

    fixture.componentInstance.visible.set(true);
    await fixture.whenStable();
    expect(tzBox()!.checked).toBe(false);
  });
});
