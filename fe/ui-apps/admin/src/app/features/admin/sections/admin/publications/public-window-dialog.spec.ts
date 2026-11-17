import { Component, signal, type WritableSignal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { afterEach, describe, expect, it } from 'vitest';

import { APP_CONFIG } from '../../../../../core/config/app-config.token';
import { LanguageService } from '../../../../../core/main-shell/services/language.service';
import { provideNiordIcons } from '../../../../../shared/icons/niord-icons';
import { provideTestTransloco } from '../../../../../shared/testing/transloco-testing';
import { provideTestSessionZone } from '../../../../../shared/testing/zone-testing';
import { PublicWindowDialog, type PublicWindowDecision } from './public-window-dialog';

const FROM = Date.UTC(2017, 1, 22, 9, 0);
const TO = Date.UTC(2017, 11, 31, 22, 59);
const DAY = 24 * 60 * 60 * 1000;

/** The desk's zone — west of the runner's, so the two never agree on a clock. */
const SESSION_ZONE = 'America/Halifax';

@Component({
  imports: [PublicWindowDialog],
  template: `
    <app-publication-public-window-dialog
      [(visible)]="open"
      [publicFrom]="from()"
      [publicTo]="to()"
      [startRequired]="startRequired()"
      [errorKey]="errorKey()"
      (submitted)="last.set($event)"
    />
  `,
})
class Host {
  readonly open = signal(false);
  readonly from = signal<number | null | undefined>(FROM);
  readonly to = signal<number | null | undefined>(TO);
  readonly startRequired = signal(true);
  readonly errorKey = signal<string | undefined>(undefined);
  readonly last = signal<PublicWindowDecision | undefined>(undefined);
}

/** The two bound signals the shared pickers write into. */
interface Internals {
  readonly periodForm: {
    from: () => { value: WritableSignal<Date | null> };
    to: () => { value: WritableSignal<Date | null> };
  };
}

async function setup() {
  TestBed.configureTestingModule({
    providers: [
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
      provideTestTransloco(),
      provideNiordIcons(),
      // The desk the period belongs to, west of the runner: the bounds are
      // instants, and the days they fall on are the domain's.
      provideTestSessionZone(SESSION_ZONE),
      {
        provide: LanguageService,
        useValue: { activeLocale: signal('en'), selectedLanguage: signal('en') },
      },
    ],
  });
  const fixture = TestBed.createComponent(Host);
  fixture.autoDetectChanges();
  await fixture.whenStable();
  return fixture;
}

type Fixture = Awaited<ReturnType<typeof setup>>;

const panel = () => document.querySelector('.dialog-panel');
const text = () => panel()?.textContent ?? '';
const saveButton = () =>
  document.querySelector<HTMLButtonElement>('[data-testid="public-window-save"]');

function internals(fixture: Fixture): Internals {
  return fixture.debugElement.query(By.directive(PublicWindowDialog))
    .componentInstance as unknown as Internals;
}

async function open(fixture: Fixture) {
  fixture.componentInstance.open.set(true);
  await fixture.whenStable();
}

async function setBounds(fixture: Fixture, from: Date | null, to: Date | null) {
  const form = internals(fixture).periodForm;
  form.from().value.set(from);
  form.to().value.set(to);
  await fixture.whenStable();
}

/**
 * The public period, edited as ONE decision.
 *
 * Both ends in one dialog, because "from when, until when" is one question: the
 * split action could only clear an end, so a publication going out again next
 * month had no way to say so. A start still to come is an ordinary answer, and
 * the dialog says as much while it is being typed.
 */
describe('PublicWindowDialog', () => {
  afterEach(() => {
    document.querySelectorAll('.cdk-overlay-container').forEach((el) => el.replaceChildren());
  });

  it('seeds both fields from the period the publication carries', async () => {
    const fixture = await setup();
    await open(fixture);

    const form = internals(fixture).periodForm;
    expect(form.from().value()?.getTime()).toBe(FROM);
    expect(form.to().value()?.getTime()).toBe(TO);
  });

  /** A period nobody has set opens empty rather than on today. */
  it('opens empty when the publication has no period', async () => {
    const fixture = await setup();
    fixture.componentInstance.from.set(undefined);
    fixture.componentInstance.to.set(null);
    await open(fixture);

    const form = internals(fixture).periodForm;
    expect(form.from().value()).toBeNull();
    expect(form.to().value()).toBeNull();
  });

  /**
   * A period left over from the last publication would be submitted against this
   * one by whoever pressed the button without reading the fields.
   */
  it('re-seeds every time it opens', async () => {
    const fixture = await setup();
    await open(fixture);
    await setBounds(fixture, new Date(FROM + 5 * DAY), null);

    fixture.componentInstance.open.set(false);
    await fixture.whenStable();
    await open(fixture);

    expect(internals(fixture).periodForm.from().value()?.getTime()).toBe(FROM);
  });

  it('emits both ends, as epoch milliseconds', async () => {
    const fixture = await setup();
    await open(fixture);
    await setBounds(fixture, new Date(FROM), new Date(TO));

    saveButton()!.click();
    await fixture.whenStable();

    expect(fixture.componentInstance.last()).toEqual({ publicFrom: FROM, publicTo: TO });
  });

  /** An end nobody set travels as an explicit null: the period runs on. */
  it('emits a cleared end as null, and says what that means', async () => {
    const fixture = await setup();
    await open(fixture);
    await setBounds(fixture, new Date(FROM), null);

    expect(text()).toContain('admin.publications.oneOff.publicWindow.noEnd');

    saveButton()!.click();
    await fixture.whenStable();

    expect(fixture.componentInstance.last()).toEqual({ publicFrom: FROM, publicTo: null });
  });

  // ------------------------------------------------------------- refusals

  /**
   * A period that runs backwards is refused HERE, because both dates are on the
   * screen together: an admin who has just typed them should not have to send
   * them to find out that they are the wrong way round.
   */
  it('refuses an end before the start, and says so in place', async () => {
    const fixture = await setup();
    await open(fixture);
    await setBounds(fixture, new Date(TO), new Date(FROM));

    expect(document.querySelector('[data-testid="public-window-inverted"]')).not.toBeNull();
    expect(saveButton()!.disabled).toBe(true);
  });

  /** The server refuses a published publication with no start, so the form does first. */
  it('demands a start where the publication is out', async () => {
    const fixture = await setup();
    await open(fixture);
    await setBounds(fixture, null, new Date(TO));

    expect(document.querySelector('[data-testid="public-window-start-required"]')).not.toBeNull();
    expect(saveButton()!.disabled).toBe(true);
  });

  /** A publication still being prepared may have no start at all. */
  it('allows no start at all while the publication is not out', async () => {
    const fixture = await setup();
    fixture.componentInstance.startRequired.set(false);
    await open(fixture);
    await setBounds(fixture, null, null);

    expect(document.querySelector('[data-testid="public-window-start-required"]')).toBeNull();
    expect(saveButton()!.disabled).toBe(false);

    saveButton()!.click();
    await fixture.whenStable();
    expect(fixture.componentInstance.last()).toEqual({ publicFrom: null, publicTo: null });
  });

  // ------------------------------------------------------------ scheduled

  /**
   * A start that has not arrived yet is the POINT of this dialog as much as the
   * end is — and it looks exactly like a date somebody mistyped, so it is named
   * while it is on the screen rather than discovered afterwards.
   */
  it('calls out a start still to come, and still allows it', async () => {
    const fixture = await setup();
    await open(fixture);
    await setBounds(fixture, new Date(Date.now() + 90 * DAY), null);

    expect(document.querySelector('[data-testid="public-window-scheduled"]')).not.toBeNull();
    expect(saveButton()!.disabled).toBe(false);
  });

  it('says nothing about scheduling for a start already past', async () => {
    const fixture = await setup();
    await open(fixture);
    await setBounds(fixture, new Date(FROM), null);

    expect(document.querySelector('[data-testid="public-window-scheduled"]')).toBeNull();
  });

  /** A refusal the caller mapped reaches the reader inside the dialog. */
  it('shows the refusal the caller hands it', async () => {
    const fixture = await setup();
    fixture.componentInstance.errorKey.set('admin.publications.error.publicWindowInvalid');
    await open(fixture);

    expect(text()).toContain('admin.publications.error.publicWindowInvalid');
  });
});
