import { Component, inject, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import {
  type ActivatedRouteSnapshot,
  provideRouter,
  Router,
  type RouterStateSnapshot,
} from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { describe, expect, it, vi } from 'vitest';

import { provideNiordIcons } from '../../icons/niord-icons';
import { DiscardConfirmService } from '../../services/discard-confirm.service';
import { provideTestTransloco } from '../../testing/transloco-testing';
import { Drawer } from './drawer';
import { DrawerGateService } from './drawer-gate.service';
import { drawerUnsavedChangesGuard } from './drawer-unsaved-changes.guard';

function runGuard(): boolean | Promise<boolean> {
  return TestBed.runInInjectionContext(() =>
    drawerUnsavedChangesGuard(
      undefined,
      {} as ActivatedRouteSnapshot,
      {} as RouterStateSnapshot,
      {} as RouterStateSnapshot,
    ),
  ) as boolean | Promise<boolean>;
}

function setup(confirmResult: boolean) {
  const confirmDiscard = vi.fn(() => Promise.resolve(confirmResult));
  TestBed.configureTestingModule({
    providers: [{ provide: DiscardConfirmService, useValue: { confirmDiscard } }],
  });
  return { confirmDiscard };
}

/** A page with a changed drawer that a Save closes and then leaves, in one turn. */
@Component({
  imports: [Drawer],
  template: `<app-drawer [visible]="open()" [dirty]="true" header="x"><p>x</p></app-drawer>`,
})
class DrawerPage {
  readonly open = signal(true);
  private readonly router = inject(Router);

  leave(closingTheDrawer: boolean): void {
    if (closingTheDrawer) {
      this.open.set(false);
    }
    void this.router.navigate(['/next']);
  }
}

@Component({ template: '' })
class NextPage {}

async function setupPage(confirmResult: boolean) {
  const confirmDiscard = vi.fn(() => Promise.resolve(confirmResult));
  TestBed.configureTestingModule({
    providers: [
      provideTestTransloco(),
      provideNiordIcons(),
      { provide: DiscardConfirmService, useValue: { confirmDiscard } },
      provideRouter([
        { path: 'page', component: DrawerPage, canDeactivate: [drawerUnsavedChangesGuard] },
        { path: 'next', component: NextPage },
      ]),
    ],
  });
  const harness = await RouterTestingHarness.create('/page');
  await harness.fixture.whenStable();
  const page = harness.routeDebugElement?.componentInstance as DrawerPage;
  expect(TestBed.inject(DrawerGateService).anyDirty()).toBe(true);
  return { harness, page, confirmDiscard, router: TestBed.inject(Router) };
}

describe('drawerUnsavedChangesGuard', () => {
  it('allows navigation when no drawer is dirty', () => {
    const { confirmDiscard } = setup(true);

    expect(runGuard()).toBe(true);
    expect(confirmDiscard).not.toHaveBeenCalled();
  });

  it('prompts when a drawer is dirty and follows the user decision', async () => {
    const { confirmDiscard } = setup(false);
    TestBed.inject(DrawerGateService).setDirty({}, true);

    await expect(runGuard()).resolves.toBe(false);
    expect(confirmDiscard).toHaveBeenCalled();

    confirmDiscard.mockReturnValue(Promise.resolve(true));
    await expect(runGuard()).resolves.toBe(true);
  });

  it('lets a drawer that leaves the registry before the next render go without asking', async () => {
    const { confirmDiscard } = setup(false);
    const gate = TestBed.inject(DrawerGateService);
    const drawer = {};
    gate.setDirty(drawer, true);

    const result = runGuard();
    gate.setDirty(drawer, false);

    await expect(result).resolves.toBe(true);
    expect(confirmDiscard).not.toHaveBeenCalled();
  });

  /**
   * A Save that closes its drawer and navigates in the same turn (the new
   * series landing on its own page) has nothing left to discard: the guard must
   * not ask whether to throw away what was just saved.
   */
  it('does not ask when the page closes its changed drawer and navigates in one turn', async () => {
    const { page, confirmDiscard, router } = await setupPage(false);

    page.leave(true);
    await vi.waitFor(() => expect(router.url).toBe('/next'));

    expect(confirmDiscard).not.toHaveBeenCalled();
  });

  it('asks when the page navigates while its changed drawer stays open, and No stays', async () => {
    const { harness, page, confirmDiscard, router } = await setupPage(false);

    page.leave(false);
    await vi.waitFor(() => expect(confirmDiscard).toHaveBeenCalledTimes(1));
    await harness.fixture.whenStable();

    expect(router.url).toBe('/page');
  });
});
