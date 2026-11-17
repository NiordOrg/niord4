import { TestBed } from '@angular/core/testing';
import { TranslocoService } from '@jsverse/transloco';
import { afterEach, describe, expect, it } from 'vitest';

import { provideNiordIcons } from '../../../../shared/icons/niord-icons';
import { provideTestTransloco } from '../../../../shared/testing/transloco-testing';
import type { MainType } from '../../models/message.model';
import { MessageCopyDialog } from './message-copy-dialog';
import { MessageCopyDialogService } from './message-copy-dialog.service';

async function setup() {
  TestBed.configureTestingModule({
    providers: [provideTestTransloco(), provideNiordIcons()],
  });
  TestBed.inject(TranslocoService).load('en').subscribe();

  const fixture = TestBed.createComponent(MessageCopyDialog);
  const service = TestBed.inject(MessageCopyDialogService);
  fixture.autoDetectChanges();
  await fixture.whenStable();
  return { fixture, service };
}

const q = (sel: string) => document.querySelector(sel);
const checkbox = () => q('#copy-include-reference') as HTMLInputElement | null;
const confirmBtn = () => q('.dialog-foot .btn-primary') as HTMLButtonElement;
const cancelBtn = () => q('.dialog-foot .btn-tertiary') as HTMLButtonElement;

afterEach(() => {
  document.querySelectorAll('.cdk-overlay-container').forEach((el) => el.replaceChildren());
});

async function open(service: MessageCopyDialogService, mainType: MainType | undefined) {
  const results: (string[] | undefined)[] = [];
  service.open('m-1', mainType).subscribe((r) => results.push(r));
  return results;
}

describe('MessageCopyDialog', () => {
  it('offers the include-reference checkbox for an NM message', async () => {
    const { fixture, service } = await setup();
    await open(service, 'NM');
    await fixture.whenStable();

    expect(q('.dialog-panel')).not.toBeNull();
    expect(checkbox()).not.toBeNull();
  });

  it('hides the include-reference checkbox for an NW message', async () => {
    const { fixture, service } = await setup();
    await open(service, 'NW');
    await fixture.whenStable();

    expect(q('.dialog-panel')).not.toBeNull();
    expect(checkbox()).toBeNull();
  });

  it('confirm with the box checked resolves the REFERENCE copy route (NM)', async () => {
    const { fixture, service } = await setup();
    const results = await open(service, 'NM');
    await fixture.whenStable();

    checkbox()!.click();
    await fixture.whenStable();
    confirmBtn().click();
    await fixture.whenStable();

    expect(results).toEqual([['/editor/copy', 'm-1', 'REFERENCE']]);
  });

  it('confirm with the box unchecked resolves the plain copy route (NM)', async () => {
    const { fixture, service } = await setup();
    const results = await open(service, 'NM');
    await fixture.whenStable();

    confirmBtn().click();
    await fixture.whenStable();

    expect(results).toEqual([['/editor/copy', 'm-1']]);
  });

  it('confirm resolves the plain copy route for an NW message (no checkbox)', async () => {
    const { fixture, service } = await setup();
    const results = await open(service, 'NW');
    await fixture.whenStable();

    confirmBtn().click();
    await fixture.whenStable();

    expect(results).toEqual([['/editor/copy', 'm-1']]);
  });

  it('cancel resolves undefined (no navigation)', async () => {
    const { fixture, service } = await setup();
    const results = await open(service, 'NM');
    await fixture.whenStable();

    cancelBtn().click();
    await fixture.whenStable();

    expect(results).toEqual([undefined]);
  });
});
