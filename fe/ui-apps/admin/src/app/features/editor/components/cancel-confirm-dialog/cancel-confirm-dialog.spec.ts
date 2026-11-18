import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { TranslocoService } from '@jsverse/transloco';
import { afterEach, describe, expect, it } from 'vitest';

import { provideNiordIcons } from '../../../../shared/icons/niord-icons';
import { provideTestTransloco } from '../../../../shared/testing/transloco-testing';
import { CancelConfirmDialog } from './cancel-confirm-dialog';

@Component({
  imports: [CancelConfirmDialog],
  template: `
    <app-cancel-confirm-dialog
      [(visible)]="visible"
      [mainType]="mainType()"
      [autoCancellationWarning]="autoCancellationWarning()"
      (confirmed)="confirmed.set($event)"
    />
  `,
})
class Host {
  readonly visible = signal(false);
  readonly mainType = signal('');
  readonly autoCancellationWarning = signal(false);
  readonly confirmed = signal<boolean | undefined>(undefined);
}

async function setup(mainType: string, autoCancellationWarning = false) {
  TestBed.configureTestingModule({
    providers: [provideTestTransloco(), provideNiordIcons()],
  });
  TestBed.inject(TranslocoService).load('en').subscribe();

  const fixture = TestBed.createComponent(Host);
  fixture.componentInstance.mainType.set(mainType);
  fixture.componentInstance.autoCancellationWarning.set(autoCancellationWarning);
  fixture.autoDetectChanges();
  await fixture.whenStable();
  return fixture;
}

const q = (sel: string) => document.querySelector(sel);
const checkbox = () => q('#create-cancellation') as HTMLInputElement | null;
const autoNote = () => q('[data-testid="auto-cancellation-warning"]');

afterEach(() => {
  document.querySelectorAll('.cdk-overlay-container').forEach((el) => el.replaceChildren());
});

describe('CancelConfirmDialog', () => {
  it('offers the create-cancellation checkbox for an NM message', async () => {
    const fixture = await setup('NM');
    fixture.componentInstance.visible.set(true);
    await fixture.whenStable();

    expect(q('.dialog-panel')).not.toBeNull();
    expect(checkbox()).not.toBeNull();
    expect(q('label[for="create-cancellation"]')).not.toBeNull();
  });

  it('hides the create-cancellation checkbox for an NW message', async () => {
    const fixture = await setup('NW');
    fixture.componentInstance.visible.set(true);
    await fixture.whenStable();

    expect(q('.dialog-panel')).not.toBeNull();
    expect(checkbox()).toBeNull();
  });

  it('passes the checked create-cancellation flag through on confirm and closes (NM)', async () => {
    const fixture = await setup('NM');
    fixture.componentInstance.visible.set(true);
    await fixture.whenStable();

    checkbox()!.click();
    await fixture.whenStable();
    (q('.dialog-foot .btn-danger') as HTMLButtonElement).click();
    await fixture.whenStable();

    expect(fixture.componentInstance.confirmed()).toBe(true);
    expect(fixture.componentInstance.visible()).toBe(false);
  });

  it('passes false on confirm when the box is left unchecked (NM)', async () => {
    const fixture = await setup('NM');
    fixture.componentInstance.visible.set(true);
    await fixture.whenStable();

    (q('.dialog-foot .btn-danger') as HTMLButtonElement).click();
    await fixture.whenStable();

    expect(fixture.componentInstance.confirmed()).toBe(false);
  });

  it('confirms false for an NW message where no checkbox is shown', async () => {
    const fixture = await setup('NW');
    fixture.componentInstance.visible.set(true);
    await fixture.whenStable();

    (q('.dialog-foot .btn-danger') as HTMLButtonElement).click();
    await fixture.whenStable();

    expect(fixture.componentInstance.confirmed()).toBe(false);
  });

  it('says that a cancellation warning will be published when cancelling publishes one', async () => {
    const fixture = await setup('NW', true);
    fixture.componentInstance.visible.set(true);
    await fixture.whenStable();

    expect(autoNote()).not.toBeNull();
    expect(autoNote()!.getAttribute('role')).toBe('note');
    expect(autoNote()!.textContent).toContain('cancelDialog.autoCancellationWarning');
    expect(checkbox()).toBeNull();
  });

  it('says nothing of a cancellation warning when cancelling publishes none', async () => {
    const fixture = await setup('NW', false);
    fixture.componentInstance.visible.set(true);
    await fixture.whenStable();

    expect(q('.dialog-panel')).not.toBeNull();
    expect(autoNote()).toBeNull();
  });

  it('keeps the NM create-cancellation checkbox beside no note', async () => {
    const fixture = await setup('NM', false);
    fixture.componentInstance.visible.set(true);
    await fixture.whenStable();

    expect(checkbox()).not.toBeNull();
    expect(autoNote()).toBeNull();
  });

  it('resets the create-cancellation checkbox each time the dialog reopens (NM)', async () => {
    const fixture = await setup('NM');
    fixture.componentInstance.visible.set(true);
    await fixture.whenStable();

    checkbox()!.click();
    await fixture.whenStable();
    expect(checkbox()!.checked).toBe(true);

    fixture.componentInstance.visible.set(false);
    await fixture.whenStable();
    fixture.componentInstance.visible.set(true);
    await fixture.whenStable();

    expect(checkbox()!.checked).toBe(false);
  });
});
