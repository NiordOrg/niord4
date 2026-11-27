import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { form } from '@angular/forms/signals';
import { TranslocoService } from '@jsverse/transloco';
import { describe, expect, it } from 'vitest';

import { APP_CONFIG } from '../../../../../core/config/app-config.token';
import { DomainFacade } from '../../../../../core/domain/facades/domain.facade';
import { LanguageService } from '../../../../../core/main-shell/services/language.service';
import { LocaleFormatService } from '../../../../../core/main-shell/services/locale-format.service';
import { provideNiordIcons } from '../../../../../shared/icons/niord-icons';
import { TEST_LANGUAGES } from '../../../../../shared/testing/language-testing';
import { provideTestTransloco } from '../../../../../shared/testing/transloco-testing';
import { wallClockIn } from '../../../../../shared/testing/zone-testing';
import { dateToTimeString, newTriggerForm, type TriggerFormModel } from './mailing-list.model';
import { TriggerCard } from './trigger-card';

/** The `HH:mm` the given zone reads at `instant` — taken from Intl, never from an offset. */
function hoursMinutesIn(timeZone: string, instant: Date): string {
  return wallClockIn(timeZone, instant).slice(11, 16);
}

@Component({
  imports: [TriggerCard],
  template: `<app-trigger-card [triggerField]="form" [index]="0" />`,
})
class Host {
  readonly model = signal<TriggerFormModel>(newTriggerForm(['en', 'da']));
  readonly form = form(this.model);
}

interface CardInternals {
  setType: (type: 'STATUS_CHANGE' | 'SCHEDULED') => void;
}

function setup(initialSessionZone?: string) {
  const sessionZone = signal<string | undefined>(initialSessionZone);
  TestBed.configureTestingModule({
    providers: [
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
      provideHttpClient(),
      provideHttpClientTesting(),
      provideTestTransloco(),
      provideNiordIcons(),
      {
        provide: LanguageService,
        useValue: {
          selectedLanguage: () => 'en',
          modelLanguages: TEST_LANGUAGES.availableLanguages,
        },
      },
      {
        provide: LocaleFormatService,
        useValue: { formatTime: () => '', uses12HourClock: () => false },
      },
      {
        provide: DomainFacade,
        useValue: {
          sessionDomain: () => {
            const timeZone = sessionZone();
            return timeZone ? { domainId: 'test-domain', timeZone } : undefined;
          },
        } as unknown as DomainFacade,
      },
    ],
  });
  const fixture = TestBed.createComponent(Host);
  fixture.detectChanges();
  return {
    fixture,
    host: fixture.componentInstance,
    card: fixture.debugElement.children[0].componentInstance as unknown as CardInternals,
    sessionZone,
  };
}

/**
 * Read the clock across the call, so the assertion holds even if the minute turns over
 * between the two readings.
 */
function seedAndReadClock(card: CardInternals, timeZone: string): string[] {
  const before = new Date();
  card.setType('SCHEDULED');
  const after = new Date();
  return [hoursMinutesIn(timeZone, before), hoursMinutesIn(timeZone, after)];
}

describe('TriggerCard', () => {
  describe('switching to SCHEDULED', () => {
    it('seeds the time as the SESSION DOMAIN reads the clock, not as the browser does', () => {
      const { host, card } = setup('Asia/Tokyo');

      const candidates = seedAndReadClock(card, 'Asia/Tokyo');

      expect(host.model().scheduledExecutionTimeZone).toBe('Asia/Tokyo');
      expect(candidates).toContain(dateToTimeString(host.model().scheduledExecutionTime));
    });

    it('seeds a different hour for two domains an ocean apart at the same moment', () => {
      const { host, card, sessionZone } = setup('Asia/Tokyo');
      const tokyoCandidates = seedAndReadClock(card, 'Asia/Tokyo');
      const tokyoSeed = dateToTimeString(host.model().scheduledExecutionTime);

      host.model.set(newTriggerForm(['en', 'da']));
      sessionZone.set('America/Halifax');
      const halifaxCandidates = seedAndReadClock(card, 'America/Halifax');
      const halifaxSeed = dateToTimeString(host.model().scheduledExecutionTime);

      expect(tokyoCandidates).toContain(tokyoSeed);
      expect(halifaxCandidates).toContain(halifaxSeed);
      // Tokyo and Halifax never share a wall clock, so one seed of the browser's own
      // reading could not satisfy both.
      expect(tokyoSeed).not.toBe(halifaxSeed);
    });

    it('prefers the zone the trigger already carries over the session domain', () => {
      const { host, card } = setup('America/Halifax');
      host.form.scheduledExecutionTimeZone().value.set('Asia/Tokyo');

      const candidates = seedAndReadClock(card, 'Asia/Tokyo');

      expect(host.model().scheduledExecutionTimeZone).toBe('Asia/Tokyo');
      expect(candidates).toContain(dateToTimeString(host.model().scheduledExecutionTime));
    });

    it('seeds nothing when there is no zone to read the clock in', () => {
      const { host, card } = setup();

      card.setType('SCHEDULED');

      expect(host.model().type).toBe('SCHEDULED');
      expect(host.model().scheduledExecutionTime).toBeNull();
      expect(host.model().scheduledExecutionTimeZone).toBe('');
    });

    it('leaves an already-chosen time alone', () => {
      const { host, card } = setup('Asia/Tokyo');
      host.form.scheduledExecutionTime().value.set(new Date(2000, 0, 1, 6, 45));

      card.setType('SCHEDULED');

      expect(dateToTimeString(host.model().scheduledExecutionTime)).toBe('06:45');
      expect(host.model().scheduledExecutionTimeZone).toBe('Asia/Tokyo');
    });
  });

  it('names each subject language by its configured label and flag, and an unconfigured one as stored', () => {
    const { fixture, host } = setup();
    // The rows are drawn afresh after the text arrives, as they are when the drawer opens.
    host.model.set(newTriggerForm([]));
    fixture.detectChanges();
    TestBed.inject(TranslocoService).setTranslation(
      { 'admin.mailingLists.trigger.subjectEnabled': 'Enable {{lang}} subject' },
      'en',
      { merge: true },
    );
    host.model.set(newTriggerForm(['en', 'da', 'kl']));
    fixture.detectChanges();

    // The first fieldset is the Mail subject one; each of its rows is one language.
    const subjects = fixture.nativeElement.querySelector('fieldset') as HTMLFieldSetElement;
    const rows = [...subjects.querySelectorAll(':scope > div')] as HTMLElement[];
    expect(rows.map((row) => row.querySelector('span.truncate')?.textContent?.trim())).toEqual([
      'English',
      'Dansk',
      'kl',
    ]);
    expect(
      rows.map((row) => row.querySelector('input[type="checkbox"]')?.getAttribute('aria-label')),
    ).toEqual(['Enable English subject', 'Enable Dansk subject', 'Enable kl subject']);
    expect(
      rows.map((row) => row.querySelector('app-language-flag img')?.getAttribute('src') ?? null),
    ).toEqual(['assets/img/flags/en.png', 'assets/img/flags/da.png', null]);
  });

  it('shows an unset zone as a disabled placeholder rather than a blank row', () => {
    const { fixture, host } = setup();
    host.form.type().value.set('SCHEDULED');
    host.form.expanded().value.set(true);
    fixture.detectChanges();

    const select = fixture.nativeElement.querySelector(
      'select[aria-label="admin.mailingLists.trigger.timeZone"]',
    ) as HTMLSelectElement;
    expect(select.selectedIndex).toBe(0);
    expect(select.options[0].value).toBe('');
    expect(select.options[0].disabled).toBe(true);
    expect(select.options[0].textContent).toContain(
      'admin.mailingLists.trigger.timeZonePlaceholder',
    );
  });
});
