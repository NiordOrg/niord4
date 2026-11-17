import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Overlay } from '@angular/cdk/overlay';
import { ComponentPortal } from '@angular/cdk/portal';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { Drawer } from './drawer';
import { DrawerGateService } from './drawer-gate.service';
import { provideNiordIcons } from '../../icons/niord-icons';
import { provideTestTransloco } from '../../testing/transloco-testing';
import { DiscardConfirmService } from '../../services/discard-confirm.service';

@Component({
  imports: [Drawer],
  template: `
    <app-drawer
      [visible]="visible()"
      [dirty]="dirty()"
      header="Test drawer"
      size="wide"
      (closed)="closedCount = closedCount + 1"
      (hidden)="hiddenCount = hiddenCount + 1"
    >
      <p class="body-probe">Body content</p>
      <div drawerFooter class="footer-probe">Footer content</div>
    </app-drawer>
  `,
})
class Host {
  readonly visible = signal(false);
  readonly dirty = signal(false);
  closedCount = 0;
  hiddenCount = 0;
}

/** A throwaway overlay body for the topmost-dispatch test. */
@Component({ template: '' })
class Dummy {}

/** An editor-shaped drawer: a button ahead of the fields, then the fields. */
@Component({
  imports: [Drawer],
  template: `
    <app-drawer [visible]="visible()" header="Editor drawer" initialFocus="first">
      <button type="button" class="ahead-probe">A tab</button>
      <input class="first-field" />
      <input class="second-field" />
    </app-drawer>
  `,
})
class FieldHost {
  readonly visible = signal(false);
}

async function setup() {
  // Controllable discard-confirm result; `confirmDiscard` resolves it per call.
  const discard = { result: true, confirmDiscard: vi.fn(() => Promise.resolve(discard.result)) };
  TestBed.configureTestingModule({
    providers: [
      provideTestTransloco(),
      provideNiordIcons(),
      { provide: DiscardConfirmService, useValue: discard },
    ],
  });
  const fixture = TestBed.createComponent(Host);
  fixture.autoDetectChanges();
  await fixture.whenStable();
  return { fixture, host: fixture.componentInstance, discard };
}

/** The drawer chrome is portaled to the CDK overlay container — query there. */
function drawerRoot(): HTMLElement | null {
  return document.querySelector('[role="dialog"]');
}

function closeButton(): HTMLButtonElement | null {
  return drawerRoot()?.querySelector('header button') ?? null;
}

function backdrop(): HTMLElement | null {
  return document.querySelector('.niord-drawer-backdrop');
}

/** The overlay pane the slide-out runs on. */
function pane(): HTMLElement | null {
  return document.querySelector('.niord-drawer-panel');
}

/**
 * The end of the slide-out, as the browser reports it. Built as a plain Event
 * with the property bolted on: `TransitionEvent` is not implemented in the
 * test DOM, and the listener reads nothing else.
 */
function endSlideOut(element: HTMLElement): void {
  const event = new Event('transitionend');
  Object.defineProperty(event, 'propertyName', { value: 'transform' });
  element.dispatchEvent(event);
}

/**
 * Longer than the drawer's own fallback timer, so anything that was going to
 * happen without a `transitionend` has happened by the time this resolves.
 */
function pastTheFallbackTimer(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 600));
}

describe('Drawer', () => {
  afterEach(() => {
    vi.restoreAllMocks();
    // Dispose any leaked overlay panels across the shared single-fork TestBed.
    document.querySelectorAll('.cdk-overlay-container').forEach((el) => el.replaceChildren());
  });

  it('renders nothing while hidden', async () => {
    await setup();
    expect(drawerRoot()).toBeNull();
  });

  it('renders header, body and footer when visible', async () => {
    const { fixture, host } = await setup();
    host.visible.set(true);
    await fixture.whenStable();

    const root = drawerRoot();
    expect(root).not.toBeNull();
    expect(root!.querySelector('h2')?.textContent).toContain('Test drawer');
    expect(root!.querySelector('.body-probe')?.textContent).toContain('Body content');
    expect(root!.querySelector('footer .footer-probe')?.textContent).toContain('Footer content');
  });

  /**
   * The default: the ✕, so the trap and Esc work the moment the drawer opens.
   */
  it('lands focus on the close button by default', async () => {
    const { fixture, host } = await setup();
    host.visible.set(true);
    await fixture.whenStable();

    expect(document.activeElement).toBe(closeButton());
  });

  /**
   * An editor starts in its first FIELD, not on the ✕ and not on the button
   * ahead of it: a drawer whose purpose is the typing costs a Tab otherwise, and
   * makes the first key a reader presses the one that closes it.
   */
  it('lands focus in the first field when asked to', async () => {
    TestBed.configureTestingModule({
      providers: [
        provideTestTransloco(),
        provideNiordIcons(),
        {
          provide: DiscardConfirmService,
          useValue: { confirmDiscard: () => Promise.resolve(true) },
        },
      ],
    });
    const fixture = TestBed.createComponent(FieldHost);
    fixture.autoDetectChanges();
    await fixture.whenStable();

    fixture.componentInstance.visible.set(true);
    await fixture.whenStable();

    expect(document.activeElement).toBe(drawerRoot()!.querySelector('.first-field'));
  });

  it('emits closed on ✕ click when clean — without prompting', async () => {
    const { fixture, host, discard } = await setup();
    host.visible.set(true);
    await fixture.whenStable();

    closeButton()!.click();

    expect(host.closedCount).toBe(1);
    expect(discard.confirmDiscard).not.toHaveBeenCalled();
  });

  it('gates ✕ behind the discard confirm when dirty', async () => {
    const { fixture, host, discard } = await setup();
    host.visible.set(true);
    host.dirty.set(true);
    await fixture.whenStable();

    discard.result = false;
    closeButton()!.click();
    await fixture.whenStable();
    expect(host.closedCount).toBe(0);
    expect(discard.confirmDiscard).toHaveBeenCalled();

    discard.result = true;
    closeButton()!.click();
    await fixture.whenStable();
    expect(host.closedCount).toBe(1);
  });

  it('closes on Escape (routed through the CDK overlay) when clean', async () => {
    const { fixture, host } = await setup();
    host.visible.set(true);
    await fixture.whenStable();

    drawerRoot()!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));

    expect(host.closedCount).toBe(1);
  });

  it('closes on Escape dispatched at document level (focus outside the panel)', async () => {
    const { fixture, host } = await setup();
    host.visible.set(true);
    await fixture.whenStable();

    document.body.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));

    expect(host.closedCount).toBe(1);
  });

  it('Escape consumed by an inner widget (defaultPrevented) does not close the drawer', async () => {
    const { fixture, host } = await setup();
    host.visible.set(true);
    await fixture.whenStable();

    const event = new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true });
    event.preventDefault();
    drawerRoot()!.dispatchEvent(event);
    expect(host.closedCount).toBe(0);
  });

  it('stands down on Escape while a CDK overlay is stacked above it (topmost dispatch)', async () => {
    const { fixture, host } = await setup();
    host.visible.set(true);
    await fixture.whenStable();

    // A second, later-attached CDK overlay that also listens for Escape — the
    // dispatcher routes the keydown to it (topmost), never to the drawer.
    const overlay = TestBed.inject(Overlay);
    const ref = overlay.create();
    ref.attach(new ComponentPortal(Dummy));
    const sub = ref.keydownEvents().subscribe();
    try {
      document.body.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
      expect(host.closedCount).toBe(0);
    } finally {
      sub.unsubscribe();
      ref.dispose();
    }

    // With the overlay gone the drawer is topmost again and closes.
    document.body.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    expect(host.closedCount).toBe(1);
  });

  it('backdrop click closes when clean and routes through the discard confirm when dirty', async () => {
    const { fixture, host, discard } = await setup();
    host.visible.set(true);
    await fixture.whenStable();

    expect(backdrop()).not.toBeNull();

    discard.result = false;
    backdrop()!.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    expect(host.closedCount).toBe(1);
    expect(discard.confirmDiscard).not.toHaveBeenCalled();

    host.dirty.set(true);
    await fixture.whenStable();
    backdrop()!.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    await fixture.whenStable();
    expect(host.closedCount).toBe(1);
    expect(discard.confirmDiscard).toHaveBeenCalledTimes(1);

    discard.result = true;
    backdrop()!.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    await fixture.whenStable();
    expect(host.closedCount).toBe(2);
  });

  it('document Escape while hidden does nothing', async () => {
    const { host } = await setup();
    document.body.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    expect(host.closedCount).toBe(0);
  });

  it('registers open+dirty state in the gate registry and clears it again', async () => {
    const { fixture, host } = await setup();
    const gate = TestBed.inject(DrawerGateService);

    expect(gate.anyDirty()).toBe(false);

    host.visible.set(true);
    host.dirty.set(true);
    await fixture.whenStable();
    expect(gate.anyDirty()).toBe(true);

    host.dirty.set(false);
    await fixture.whenStable();
    expect(gate.anyDirty()).toBe(false);
  });

  /**
   * `closed` and `hidden` are two different moments: the ask, and the pane
   * actually being gone. A host that mounts the drawer's component only while
   * it is needed destroys it on the second, so the slide-out is not cut short.
   */
  it('emits hidden when the slide-out ends, and only once', async () => {
    const { fixture, host } = await setup();
    host.visible.set(true);
    await fixture.whenStable();
    const panel = pane()!;

    host.visible.set(false);
    await fixture.whenStable();
    // Mid-slide: the pane is still attached and nothing has been announced.
    expect(host.hiddenCount).toBe(0);
    expect(drawerRoot()).not.toBeNull();

    endSlideOut(panel);
    expect(host.hiddenCount).toBe(1);
    expect(drawerRoot()).toBeNull();

    // The fallback timer is cancelled by the transition, not left to fire.
    await pastTheFallbackTimer();
    expect(host.hiddenCount).toBe(1);
  });

  /** No transition to end (reduced motion, a detached pane): the timer answers. */
  it('emits hidden from the fallback timer when no transitionend arrives', async () => {
    const { fixture, host } = await setup();
    host.visible.set(true);
    await fixture.whenStable();

    host.visible.set(false);
    await fixture.whenStable();
    expect(host.hiddenCount).toBe(0);

    await pastTheFallbackTimer();
    expect(host.hiddenCount).toBe(1);
    expect(drawerRoot()).toBeNull();
  });

  /**
   * Reopening mid-slide reuses the pane, so the drawer never became hidden —
   * announcing it would destroy the host of a drawer that is back on screen.
   */
  it('does not emit hidden when a reopen cancels the slide-out', async () => {
    const { fixture, host } = await setup();
    host.visible.set(true);
    await fixture.whenStable();
    const panel = pane()!;

    host.visible.set(false);
    await fixture.whenStable();
    host.visible.set(true);
    await fixture.whenStable();

    await pastTheFallbackTimer();
    expect(host.hiddenCount).toBe(0);
    expect(drawerRoot()).not.toBeNull();
    expect(pane()).toBe(panel);

    // The next close still ends the way any other does.
    host.visible.set(false);
    await fixture.whenStable();
    endSlideOut(panel);
    expect(host.hiddenCount).toBe(1);
  });

  it('blocks beforeunload only while visible and dirty', async () => {
    const { fixture, host } = await setup();

    const fire = (): boolean => {
      const event = new Event('beforeunload', { cancelable: true });
      window.dispatchEvent(event);
      return event.defaultPrevented;
    };

    expect(fire()).toBe(false);

    host.visible.set(true);
    host.dirty.set(true);
    await fixture.whenStable();
    expect(fire()).toBe(true);

    host.dirty.set(false);
    await fixture.whenStable();
    expect(fire()).toBe(false);
  });
});
