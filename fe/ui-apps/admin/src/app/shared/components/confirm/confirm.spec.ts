import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';

import { provideNiordIcons } from '../../icons/niord-icons';
import { ConfirmService } from '../../services/confirm.service';
import { provideTestTransloco } from '../../testing/transloco-testing';
import { Confirm } from './confirm';

@Component({
  imports: [Confirm],
  template: `<app-confirm />`,
  providers: [ConfirmService],
})
class ConfirmHost {}

describe('Confirm', () => {
  async function setup() {
    TestBed.configureTestingModule({ providers: [provideTestTransloco(), provideNiordIcons()] });
    const fixture = TestBed.createComponent(ConfirmHost);
    fixture.autoDetectChanges();
    await fixture.whenStable();
    const svc = fixture.debugElement.injector.get(ConfirmService);
    return { fixture, svc };
  }

  const q = (sel: string) => document.querySelector(sel);

  afterEach(() => {
    document.querySelectorAll('.cdk-overlay-container').forEach((el) => el.replaceChildren());
  });

  it('renders nothing while idle', async () => {
    await setup();
    expect(q('.confirm-panel')).toBeNull();
  });

  it('renders the active confirm; destructive accept runs the callback then dismisses', async () => {
    const { fixture, svc } = await setup();
    const accept = vi.fn();
    svc.confirmDestructive({ header: 'Delete?', message: 'Sure?', accept });
    await fixture.whenStable();

    expect(q('.confirm-panel')).not.toBeNull();
    expect(q('.confirm-title')?.textContent).toContain('Delete?');
    expect(q('.confirm-foot .btn-danger')).not.toBeNull(); // destructive -> danger accept

    (q('.confirm-foot .btn-danger') as HTMLButtonElement).click();
    await fixture.whenStable();
    expect(accept).toHaveBeenCalledTimes(1);
    expect(svc.active()).toBeUndefined();
    expect(q('.confirm-panel')).toBeNull();
  });

  it('renders details as list items, described to the dialog with the message', async () => {
    const { fixture, svc } = await setup();
    svc.confirmDestructive({ message: 'Sure?', details: ['First', 'Second'], accept: vi.fn() });
    await fixture.whenStable();

    const items = Array.from(document.querySelectorAll('.confirm-details li')).map((li) =>
      li.textContent?.trim(),
    );
    expect(items).toEqual(['First', 'Second']);
    const bodyId = q('.confirm-body')!.id;
    const detailsId = q('.confirm-details')!.id;
    expect(bodyId).not.toBe('');
    expect(detailsId).not.toBe('');
    expect(q('.confirm-panel')!.getAttribute('aria-describedby')).toBe(`${bodyId} ${detailsId}`);
  });

  it('renders no list without details, and the message alone describes the dialog', async () => {
    const { fixture, svc } = await setup();
    svc.confirmDestructive({ message: 'Sure?', accept: vi.fn() });
    await fixture.whenStable();
    expect(q('.confirm-details')).toBeNull();
    expect(q('.confirm-panel')!.getAttribute('aria-describedby')).toBe(q('.confirm-body')!.id);

    svc.dismiss();
    await fixture.whenStable();
    svc.confirmDestructive({ message: 'Sure?', details: [], accept: vi.fn() });
    await fixture.whenStable();
    expect(q('.confirm-details')).toBeNull();
  });

  it('reject dismisses without running accept; neutral uses a primary accept', async () => {
    const { fixture, svc } = await setup();
    const accept = vi.fn();
    svc.confirmNeutral({ message: 'Reset?', accept });
    await fixture.whenStable();

    expect(q('.confirm-foot .btn-primary')).not.toBeNull(); // neutral -> primary accept
    (q('.confirm-foot .btn-tertiary') as HTMLButtonElement).click(); // reject
    await fixture.whenStable();
    expect(accept).not.toHaveBeenCalled();
    expect(svc.active()).toBeUndefined();
  });
});
