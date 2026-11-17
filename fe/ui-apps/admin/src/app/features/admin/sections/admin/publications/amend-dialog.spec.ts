import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it } from 'vitest';

import { LanguageService } from '../../../../../core/main-shell/services/language.service';
import { provideNiordIcons } from '../../../../../shared/icons/niord-icons';
import { provideTestTransloco } from '../../../../../shared/testing/transloco-testing';
import type {
  AmendRequest,
  PublishCheck,
} from '../../../services/api/publication-series-api.service';
import { PublicationAmendDialog } from './amend-dialog';

@Component({
  imports: [PublicationAmendDialog],
  template: `
    <app-publication-amend-dialog
      [(visible)]="open"
      [warnings]="warnings()"
      [unacknowledged]="unacknowledged()"
      [working]="working()"
      (submitted)="last.set($event)"
    />
  `,
})
class Host {
  readonly open = signal(false);
  readonly warnings = signal<readonly PublishCheck[]>([]);
  readonly unacknowledged = signal<readonly string[]>([]);
  /** Whether the amend the screen started is still running. */
  readonly working = signal(false);
  readonly last = signal<AmendRequest | undefined>(undefined);
}

async function setup() {
  TestBed.configureTestingModule({
    providers: [
      provideTestTransloco(),
      provideNiordIcons(),
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

const q = <T extends Element>(sel: string) => document.querySelector(sel) as T | null;

type Fixture = Awaited<ReturnType<typeof setup>>;

async function open(fixture: Fixture) {
  fixture.componentInstance.open.set(true);
  await fixture.whenStable();
}

async function reason(fixture: Fixture, value: string) {
  const field = q<HTMLTextAreaElement>('#publication-amend-reason')!;
  field.value = value;
  field.dispatchEvent(new Event('input', { bubbles: true }));
  await fixture.whenStable();
}

async function submit(fixture: Fixture) {
  q<HTMLButtonElement>('.dialog-panel .btn-primary')!.click();
  await fixture.whenStable();
}

describe('PublicationAmendDialog', () => {
  afterEach(() => {
    document.querySelectorAll('.cdk-overlay-container').forEach((el) => el.replaceChildren());
  });

  /** An amend rewrites a document people have read; it must say why. */
  it('will not submit without a reason', async () => {
    const fixture = await setup();
    await open(fixture);

    expect(q<HTMLButtonElement>('.dialog-panel .btn-primary')!.disabled).toBe(true);
    await submit(fixture);
    expect(fixture.componentInstance.last()).toBeUndefined();
  });

  /** The bounds are measured on the trimmed text, as the server measures them. */
  it('refuses a reason the server would refuse', async () => {
    const fixture = await setup();
    await open(fixture);
    await reason(fixture, '  x  ');

    expect(q<HTMLButtonElement>('.dialog-panel .btn-primary')!.disabled).toBe(true);
  });

  /** An amend always re-renders, so the body carries the reason and nothing else. */
  it('submits the trimmed reason and no rendering choice', async () => {
    const fixture = await setup();
    await open(fixture);
    await reason(fixture, '  Wrong chart number  ');
    await submit(fixture);

    expect(fixture.componentInstance.last()).toEqual({
      reason: 'Wrong chart number',
      acknowledgedWarnings: [],
    });
  });

  /**
   * A disabled button says the click landed; it does not say the work is running.
   *
   * An amend re-renders the document per language and that is tens of seconds on
   * a large issue, over which the dialog stands there looking refused.
   */
  it('spins the submit button while the amend is running', async () => {
    const fixture = await setup();
    await open(fixture);
    await reason(fixture, 'Wrong chart number');

    expect(q('.dialog-panel .btn-primary app-icon')).toBeNull();

    fixture.componentInstance.working.set(true);
    await fixture.whenStable();

    const button = q<HTMLButtonElement>('.dialog-panel .btn-primary')!;
    expect(button.querySelector('app-icon')).not.toBeNull();
    expect(button.getAttribute('aria-busy')).toBe('true');
    expect(button.disabled).toBe(true);
    // The label stays, so the control never changes what it says mid-press.
    expect(button.textContent).toContain('admin.publications.amend.confirm');
  });

  /** There is no choice to make about the document: the amend renders it again. */
  it('offers no control over whether the document is rendered again', async () => {
    const fixture = await setup();
    await open(fixture);

    expect(q('#publication-amend-regenerate')).toBeNull();
  });

  it('submits the warning codes that were ticked, and only those', async () => {
    const fixture = await setup();
    fixture.componentInstance.warnings.set([
      { code: 'FEW_MEMBERS', severity: 'WARN', passed: false, acknowledgeable: true },
      {
        code: 'CANCELLED_MEMBERS_ALIVE_AT_CUTOFF',
        severity: 'WARN',
        passed: false,
        acknowledgeable: true,
      },
    ]);
    await open(fixture);
    await reason(fixture, 'Wrong chart number');

    q<HTMLInputElement>('#amend-ack-FEW_MEMBERS')!.click();
    await fixture.whenStable();
    await submit(fixture);

    expect(fixture.componentInstance.last()?.acknowledgedWarnings).toEqual(['FEW_MEMBERS']);
  });

  /**
   * The refusal is re-listed rather than replacing the form. An amend re-resolves,
   * so a warning can appear between the rail being read and the attempt.
   */
  it('re-lists the warnings a refused amend is still waiting on, keeping the reason', async () => {
    const fixture = await setup();
    await open(fixture);
    await reason(fixture, 'Wrong chart number');
    await submit(fixture);

    fixture.componentInstance.unacknowledged.set(['CANCELLED_BUT_DATE_ALIVE']);
    await fixture.whenStable();

    expect(q('.publish-unacknowledged')).not.toBeNull();
    expect(q<HTMLTextAreaElement>('#publication-amend-reason')!.value).toBe('Wrong chart number');

    q<HTMLInputElement>('#amend-unack-CANCELLED_BUT_DATE_ALIVE')!.click();
    await fixture.whenStable();
    await submit(fixture);

    expect(fixture.componentInstance.last()?.acknowledgedWarnings).toEqual([
      'CANCELLED_BUT_DATE_ALIVE',
    ]);
  });

  /**
   * The amend gate compares the SAME vocabulary the publish gate does.
   *
   * An amend re-resolves and re-renders behind the same warning gate, so a rail
   * row ticked here has to travel as the resolver's own code — otherwise the
   * refusal comes straight back naming the row that was just accepted. The row
   * states the correspondence itself; nothing here translates one into the other.
   */
  it('submits the rail row as the code the amend gate compares', async () => {
    const fixture = await setup();
    fixture.componentInstance.warnings.set([
      {
        code: 'CANCELLED_MEMBERS_ALIVE_AT_CUTOFF',
        acknowledgeCode: 'CANCELLED_BUT_DATE_ALIVE',
        severity: 'WARN',
        passed: false,
        acknowledgeable: true,
      },
    ]);
    await open(fixture);
    await reason(fixture, 'Wrong chart number');

    q<HTMLInputElement>('#amend-ack-CANCELLED_BUT_DATE_ALIVE')!.click();
    await fixture.whenStable();
    await submit(fixture);

    expect(fixture.componentInstance.last()?.acknowledgedWarnings).toEqual([
      'CANCELLED_BUT_DATE_ALIVE',
    ]);
    expect(document.querySelector('.dialog-panel')!.textContent).not.toContain(
      'admin.publications.check.',
    );
  });
});
