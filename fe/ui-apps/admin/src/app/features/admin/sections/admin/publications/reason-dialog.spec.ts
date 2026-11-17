import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it } from 'vitest';

import { provideNiordIcons } from '../../../../../shared/icons/niord-icons';
import { provideTestTransloco } from '../../../../../shared/testing/transloco-testing';
import { PublicationReasonDialog } from './reason-dialog';

@Component({
  imports: [PublicationReasonDialog],
  template: `
    <app-publication-reason-dialog
      [(visible)]="open"
      headerKey="admin.publications.retire.title"
      effectKey="admin.publications.retire.effect"
      confirmKey="admin.publications.issue.retire"
      [errorKey]="errorKey()"
      (submitted)="last.set($event)"
    />
  `,
})
class Host {
  readonly open = signal(false);
  readonly last = signal<string | undefined>(undefined);
  readonly errorKey = signal<string | undefined>(undefined);
}

async function setup() {
  TestBed.configureTestingModule({
    providers: [provideTestTransloco(), provideNiordIcons()],
  });
  const fixture = TestBed.createComponent(Host);
  fixture.autoDetectChanges();
  await fixture.whenStable();
  fixture.componentInstance.open.set(true);
  await fixture.whenStable();
  return fixture;
}

const q = <T extends Element>(sel: string) => document.querySelector(sel) as T | null;

async function type(fixture: Awaited<ReturnType<typeof setup>>, text: string) {
  const box = q<HTMLTextAreaElement>('#publication-reason')!;
  box.value = text;
  box.dispatchEvent(new Event('input', { bubbles: true }));
  await fixture.whenStable();
}

describe('PublicationReasonDialog', () => {
  afterEach(() => {
    document.querySelectorAll('.cdk-overlay-container').forEach((el) => el.replaceChildren());
  });

  /** The reason is the only part of a withdrawal that survives into the history. */
  it('refuses to submit without a reason', async () => {
    await setup();
    expect(q<HTMLButtonElement>('.dialog-panel .btn-danger')!.disabled).toBe(true);
  });

  /** Three characters, the bound the server enforces. */
  it('refuses a reason shorter than the server accepts', async () => {
    const fixture = await setup();
    await type(fixture, 'ab');
    expect(q<HTMLButtonElement>('.dialog-panel .btn-danger')!.disabled).toBe(true);
  });

  /** The bounds are measured on what is submitted: padding is not reason. */
  it('measures the reason trimmed, as the server does', async () => {
    const fixture = await setup();
    await type(fixture, '   ab   ');
    expect(q<HTMLButtonElement>('.dialog-panel .btn-danger')!.disabled).toBe(true);
  });

  it('emits the trimmed reason once it is long enough', async () => {
    const fixture = await setup();
    await type(fixture, '  Wrong chart edition  ');

    const button = q<HTMLButtonElement>('.dialog-panel .btn-danger')!;
    expect(button.disabled).toBe(false);
    button.click();
    await fixture.whenStable();

    expect(fixture.componentInstance.last()).toBe('Wrong chart edition');
  });

  /**
   * Cancelling emits nothing and closes.
   *
   * The caller never learns a reason was typed, which is the point: a dialog that
   * reported its draft on the way out would let a half-decision act.
   */
  it('emits nothing when cancelled', async () => {
    const fixture = await setup();
    await type(fixture, 'Wrong chart edition');

    q<HTMLButtonElement>('.dialog-panel .btn-tertiary')!.click();
    await fixture.whenStable();

    expect(fixture.componentInstance.last()).toBeUndefined();
    expect(fixture.componentInstance.open()).toBe(false);
  });

  /** A reason left over from the last withdrawal must not be submitted against the next. */
  it('clears the box when it is reopened', async () => {
    const fixture = await setup();
    await type(fixture, 'Wrong chart edition');

    fixture.componentInstance.open.set(false);
    await fixture.whenStable();
    fixture.componentInstance.open.set(true);
    await fixture.whenStable();

    expect(q<HTMLTextAreaElement>('#publication-reason')!.value).toBe('');
    expect(q<HTMLButtonElement>('.dialog-panel .btn-danger')!.disabled).toBe(true);
  });

  /** A refusal is stated in the reader's language, inside the dialog that caused it. */
  it('renders a refusal', async () => {
    const fixture = await setup();
    fixture.componentInstance.errorKey.set('admin.publications.error.staleVersion');
    await fixture.whenStable();

    expect(document.querySelector('.dialog-panel')!.textContent).toContain(
      'admin.publications.error.staleVersion',
    );
  });
});
