import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';

import { provideNiordIcons } from '../../icons/niord-icons';
import { provideTestTransloco } from '../../testing/transloco-testing';
import { Dialog } from './dialog';

@Component({
  imports: [Dialog],
  template: `
    <app-dialog
      [visible]="open()"
      (visibleChange)="open.set($event)"
      [closeOnEscape]="closeOnEscape()"
      header="Test dialog"
      (opened)="openedCount.set(openedCount() + 1)"
      (closed)="closedCount.set(closedCount() + 1)"
    >
      <p class="dlg-body-content">Body</p>
      <ng-template #footer><button class="dlg-foot-btn">OK</button></ng-template>
    </app-dialog>
  `,
})
class DialogHost {
  readonly open = signal(false);
  readonly closeOnEscape = signal(true);
  readonly openedCount = signal(0);
  readonly closedCount = signal(0);
}

// Mirrors the shape every custom-header consumer uses: a projected `#header` template AND the
// `header` string, which is what the panel falls back to for its name.
@Component({
  imports: [Dialog],
  template: `
    <app-dialog [visible]="open()" (visibleChange)="open.set($event)" header="Compare messages">
      <ng-template #header><span class="custom-head">Compare messages</span></ng-template>
      <p>Body</p>
    </app-dialog>
  `,
})
class CustomHeaderDialogHost {
  readonly open = signal(false);
}

describe('Dialog', () => {
  async function setup() {
    TestBed.configureTestingModule({ providers: [provideTestTransloco(), provideNiordIcons()] });
    const fixture = TestBed.createComponent(DialogHost);
    fixture.autoDetectChanges();
    await fixture.whenStable();
    return fixture;
  }

  const q = (sel: string) => document.querySelector(sel);

  afterEach(() => {
    // Dispose any leaked overlay panels across the shared single-fork TestBed.
    document.querySelectorAll('.cdk-overlay-container').forEach((el) => el.replaceChildren());
  });

  it('does not render the panel while closed', async () => {
    await setup();
    expect(q('.dialog-panel')).toBeNull();
  });

  it('portals the panel + projected body/footer/title when opened', async () => {
    const fixture = await setup();
    fixture.componentInstance.open.set(true);
    await fixture.whenStable();
    expect(q('.dialog-panel')).not.toBeNull();
    expect(q('.dlg-body-content')?.textContent).toContain('Body');
    expect(q('.dlg-foot-btn')).not.toBeNull();
    expect(q('.dialog-title')?.textContent).toContain('Test dialog');
    expect(fixture.componentInstance.openedCount()).toBe(1);
  });

  it('closes + emits (closed) when the header close button is clicked', async () => {
    const fixture = await setup();
    fixture.componentInstance.open.set(true);
    await fixture.whenStable();
    (q('.dialog-header .icon-button') as HTMLButtonElement).click();
    await fixture.whenStable();
    expect(fixture.componentInstance.open()).toBe(false);
    expect(q('.dialog-panel')).toBeNull();
    expect(fixture.componentInstance.closedCount()).toBe(1);
  });

  it('closes on Escape (routed through the CDK overlay keydown dispatcher)', async () => {
    const fixture = await setup();
    fixture.componentInstance.open.set(true);
    await fixture.whenStable();

    q('.dialog-panel')!.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }),
    );
    await fixture.whenStable();

    expect(fixture.componentInstance.open()).toBe(false);
    expect(q('.dialog-panel')).toBeNull();
    expect(fixture.componentInstance.closedCount()).toBe(1);
  });

  it('does not close on Escape when closeOnEscape is false', async () => {
    const fixture = await setup();
    fixture.componentInstance.closeOnEscape.set(false);
    fixture.componentInstance.open.set(true);
    await fixture.whenStable();

    q('.dialog-panel')!.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }),
    );
    await fixture.whenStable();

    expect(fixture.componentInstance.open()).toBe(true);
    expect(q('.dialog-panel')).not.toBeNull();
    expect(fixture.componentInstance.closedCount()).toBe(0);
  });

  it('exposes the modal ARIA contract with aria-labelledby bound to the rendered title id', async () => {
    const fixture = await setup();
    fixture.componentInstance.open.set(true);
    await fixture.whenStable();

    const root = q('.dialog-panel')!;
    expect(root.getAttribute('role')).toBe('dialog');
    expect(root.getAttribute('aria-modal')).toBe('true');

    const title = q('.dialog-title')!;
    expect(title.id).not.toBe('');
    expect(root.getAttribute('aria-labelledby')).toBe(title.id);
  });

  it('names a dialog that projects its OWN header, rather than dangling the IDREF', async () => {
    // The `.dialog-title` element lives only in the default-header branch, so a projected header
    // leaves nothing for aria-labelledby to point at. Pointing it at an absent id is not a
    // cosmetic slip: the dialog then has no accessible name at all and announces as "dialog".
    // Seven dialogs ship a custom header, and the case above passes without covering any of them.
    TestBed.configureTestingModule({ providers: [provideTestTransloco(), provideNiordIcons()] });
    const fixture = TestBed.createComponent(CustomHeaderDialogHost);
    fixture.autoDetectChanges();
    await fixture.whenStable();
    fixture.componentInstance.open.set(true);
    await fixture.whenStable();

    const root = q('.dialog-panel')!;
    expect(q('.dialog-title')).toBeNull();
    expect(root.getAttribute('aria-labelledby')).toBeNull();
    expect(root.getAttribute('aria-label')).toBe('Compare messages');
  });

  it('wires the auto-capturing focus trap onto the panel', async () => {
    // The panel carries CdkTrapFocus. Whether auto-capture actually pulls focus
    // in on open is not assertable under jsdom (its InteractivityChecker treats
    // zero-geometry elements as non-tabbable, so no first element is found); the
    // restore half below exercises the trap's capture→restore lifecycle instead.
    const fixture = await setup();
    fixture.componentInstance.open.set(true);
    await fixture.whenStable();

    expect(q('.dialog-panel')!.hasAttribute('cdkTrapFocus')).toBe(true);
  });

  it('restores focus to the previously focused element on close', async () => {
    const fixture = await setup();
    const opener = document.createElement('button');
    document.body.appendChild(opener);
    opener.focus();
    expect(document.activeElement).toBe(opener);

    fixture.componentInstance.open.set(true);
    await fixture.whenStable();

    // Move focus into the dialog so the restore is proven to actively run, not
    // merely to leave focus untouched.
    const inside = q('.dlg-foot-btn') as HTMLButtonElement;
    inside.focus();
    expect(document.activeElement).toBe(inside);

    fixture.componentInstance.open.set(false);
    await fixture.whenStable();

    expect(document.activeElement).toBe(opener);
    opener.remove();
  });
});
