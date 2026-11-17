import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { TranslocoService } from '@jsverse/transloco';
import { afterEach, describe, expect, it } from 'vitest';

import { LanguageService } from '../../../../core/main-shell/services/language.service';
import { provideNiordIcons } from '../../../../shared/icons/niord-icons';
import { provideTestTransloco } from '../../../../shared/testing/transloco-testing';
import type { Message } from '../../../messages/models/message.model';
import { PublishConfirmDialog } from './publish-confirm-dialog';

@Component({
  imports: [PublishConfirmDialog],
  template: `
    <app-publish-confirm-dialog
      [(visible)]="visible"
      [referencedMessages]="refs()"
      (confirmed)="confirmed.set($event)"
    />
  `,
})
class Host {
  readonly visible = signal(false);
  readonly refs = signal<readonly Message[]>([]);
  readonly confirmed = signal<boolean | undefined>(undefined);
}

function mkRef(id: string): Message {
  return { id, shortId: id.toUpperCase(), status: 'PUBLISHED' } as Message;
}

async function setup(refs: readonly Message[] = []) {
  TestBed.configureTestingModule({
    providers: [
      provideTestTransloco(),
      provideNiordIcons(),
      // The reference badges inject LanguageService; stub the one member they read.
      { provide: LanguageService, useValue: { selectedLanguage: signal('en') } },
    ],
  });
  TestBed.inject(TranslocoService).load('en').subscribe();

  const fixture = TestBed.createComponent(Host);
  fixture.componentInstance.refs.set(refs);
  fixture.autoDetectChanges();
  await fixture.whenStable();
  return fixture;
}

const q = (sel: string) => document.querySelector(sel);
const checkbox = () => q('#cascade-cancel') as HTMLInputElement | null;

afterEach(() => {
  document.querySelectorAll('.cdk-overlay-container').forEach((el) => el.replaceChildren());
});

describe('PublishConfirmDialog', () => {
  it('opens without a cascade-cancel checkbox when there are no referenced messages', async () => {
    const fixture = await setup([]);
    fixture.componentInstance.visible.set(true);
    await fixture.whenStable();

    expect(q('.dialog-panel')).not.toBeNull();
    expect(checkbox()).toBeNull();
  });

  it('renders the cascade-cancel checkbox and one badge per referenced message', async () => {
    const fixture = await setup([mkRef('r1'), mkRef('r2')]);
    fixture.componentInstance.visible.set(true);
    await fixture.whenStable();

    expect(checkbox()).not.toBeNull();
    expect(q('label[for="cascade-cancel"]')).not.toBeNull();
    expect(document.querySelectorAll('app-message-id-badge')).toHaveLength(2);
  });

  it('passes the checked cascade flag through on confirm and closes', async () => {
    const fixture = await setup([mkRef('r1')]);
    fixture.componentInstance.visible.set(true);
    await fixture.whenStable();

    checkbox()!.click();
    await fixture.whenStable();
    (q('.dialog-foot .btn-primary') as HTMLButtonElement).click();
    await fixture.whenStable();

    expect(fixture.componentInstance.confirmed()).toBe(true);
    expect(fixture.componentInstance.visible()).toBe(false);
  });

  it('passes an unchecked cascade flag through on confirm', async () => {
    const fixture = await setup([mkRef('r1')]);
    fixture.componentInstance.visible.set(true);
    await fixture.whenStable();

    (q('.dialog-foot .btn-primary') as HTMLButtonElement).click();
    await fixture.whenStable();

    expect(fixture.componentInstance.confirmed()).toBe(false);
  });

  it('resets the cascade checkbox each time the dialog reopens', async () => {
    const fixture = await setup([mkRef('r1')]);
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
