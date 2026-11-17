import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it } from 'vitest';

import { NotificationService } from '../../../core/notifications/notification.service';
import { provideNiordIcons } from '../../icons/niord-icons';
import { provideTestTransloco } from '../../testing/transloco-testing';
import { Toast } from './toast';

describe('Toast (app-toast / CDK overlay)', () => {
  const q = (sel: string) => document.querySelector(sel);
  const qa = (sel: string) => Array.from(document.querySelectorAll(sel));

  afterEach(() => {
    // Dispose any leaked overlay panels across the shared single-fork TestBed.
    document.querySelectorAll('.cdk-overlay-container').forEach((el) => el.replaceChildren());
  });

  async function setup() {
    TestBed.configureTestingModule({
      providers: [provideTestTransloco(), provideNiordIcons()],
    });
    const fixture = TestBed.createComponent(Toast);
    fixture.autoDetectChanges();
    await fixture.whenStable();
    return { fixture, service: TestBed.inject(NotificationService) };
  }

  it('portals a pushed message with its severity class + summary', async () => {
    const { fixture, service } = await setup();
    service.notify({ severity: 'error', summaryKey: 'Boom', life: 0 });
    await fixture.whenStable();

    const toast = q('.toast');
    expect(toast).not.toBeNull();
    expect(toast?.classList.contains('toast-error')).toBe(true);
    expect(q('.toast-summary')?.textContent).toContain('Boom');
  });

  it('renders the optional detail line and stacks multiple toasts', async () => {
    const { fixture, service } = await setup();
    service.notify({ severity: 'info', summaryKey: 'Sum', detailKey: 'Det', life: 0 });
    service.notify({ severity: 'success', summaryKey: 'Two', life: 0 });
    await fixture.whenStable();

    expect(qa('.toast')).toHaveLength(2);
    expect(q('.toast-detail')?.textContent).toContain('Det');
  });

  it('removes a toast when its close button is clicked', async () => {
    const { fixture, service } = await setup();
    service.error('Nope');
    await fixture.whenStable();
    expect(q('.toast')).not.toBeNull();

    (q('.toast-close') as HTMLButtonElement).click();
    await fixture.whenStable();
    expect(q('.toast')).toBeNull();
  });
});
