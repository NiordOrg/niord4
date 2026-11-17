import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { TranslocoService } from '@jsverse/transloco';
import { of } from 'rxjs';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { APP_CONFIG } from '../../../../core/config/app-config.token';
import { ConfirmService } from '../../../../shared/services/confirm.service';
import { LanguageService } from '../../../../core/main-shell/services/language.service';
import { LastMessagesViewService } from '../../../messages/services/last-messages-view.service';
import { provideTestTransloco } from '../../../../shared/testing/transloco-testing';
import { EditorFacade } from '../../services/editor-facade';
import { MessageDescService } from '../../services/message-desc.service';
import { DiscardConfirmService } from '../../../../shared/services/discard-confirm.service';
import { EditorViewHeader } from './editor-view-header';

class StubFacade {
  readonly messageState = signal<
    { mainType?: string; status?: string; created?: number; descs?: unknown[] } | undefined
  >({
    mainType: 'NW',
  });
  readonly dirtyState = signal(false);
  readonly editableState = signal(true);
  readonly userTouchedState = signal(false);
  readonly savingState = signal(false);
  /** The facade's not-stored half: a copy, a new message or a template's result, untouched. */
  readonly unstoredState = signal(false);
  message = () => this.messageState();
  dirty = () => this.dirtyState();
  editable = () => this.editableState();
  editableNow = () => this.editableState();
  userTouched = () => this.userTouchedState();
  // The header keys on hasUnsavedChanges ((touched AND content differs) OR not stored); the tests
  // drive `userTouchedState` to mean "has a genuine edit" and `unstoredState` for a born-dirty load.
  hasUnsavedChanges = () => this.userTouchedState() || this.unstoredState();
  saving = () => this.savingState();
  validateForSave = vi.fn(() => ({ ok: true }));
  save = vi.fn(() => of({ id: 'm-1' }));
  reload = vi.fn();
  resetWorkingState = vi.fn();
  bypassGuardOnce = vi.fn();
}

function setup(opts: { helpSection?: string } = {}) {
  const facade = new StubFacade();
  const router = {
    navigate: vi.fn(),
    navigateByUrl: vi.fn(),
    url: '/editor/edit/m-1',
    // The header now hosts a route-based <app-help-anchor>, whose HelpUrlService reads these.
    events: of(),
    routerState: {
      snapshot: {
        root: {
          data: opts.helpSection ? { help: { section: opts.helpSection } } : {},
          firstChild: null,
        },
      },
    },
  };
  const lastView = { lastUrl: vi.fn(() => '/messages/table?tag=abc') };
  const discard = { result: true, confirmDiscard: vi.fn(() => Promise.resolve(discard.result)) };

  TestBed.configureTestingModule({
    imports: [EditorViewHeader],
    providers: [
      provideTestTransloco(),
      { provide: EditorFacade, useValue: facade },
      {
        provide: APP_CONFIG,
        useValue: {
          apiBaseUrl: '/rest',
          appName: 'Niord',
          documentationUrl: 'https://docs.example',
        },
      },
      { provide: Router, useValue: router },
      { provide: LastMessagesViewService, useValue: lastView },
      { provide: DiscardConfirmService, useValue: discard },
      { provide: MessageDescService, useValue: { descForLangOrDefault: () => ({ title: 'T' }) } },
      { provide: LanguageService, useValue: { selectedLanguage: signal('en') } },
    ],
  });
  TestBed.inject(TranslocoService).load('en').subscribe();

  const fixture = TestBed.createComponent(EditorViewHeader);
  fixture.autoDetectChanges();
  const el = fixture.nativeElement as HTMLElement;
  const buttons = () => [...el.querySelectorAll<HTMLButtonElement>('.btn-tertiary')];
  const backButton = () => buttons()[0];
  const clearButton = () => buttons().at(-1)!;
  // The header re-provides its own ConfirmService — read that scoped instance.
  const confirm = fixture.debugElement.injector.get(ConfirmService);
  return { fixture, facade, router, lastView, discard, confirm, backButton, clearButton };
}

afterEach(() => {
  // <app-confirm> portals its overlay to <body>; clear it between tests.
  document.body.replaceChildren();
});

describe('EditorViewHeader', () => {
  it('renders a route-based help anchor for the active editor tab section', async () => {
    const { fixture } = setup({ helpSection: 'editor' });
    await fixture.whenStable();
    const link = (fixture.nativeElement as HTMLElement).querySelector<HTMLAnchorElement>(
      'a[href="https://docs.example/editor-manual/manual.html#editor"]',
    );
    expect(link).not.toBeNull();
    expect(link?.target).toBe('_blank');
  });

  it('renders no help anchor when the active route defines no help section', async () => {
    const { fixture } = setup();
    await fixture.whenStable();
    expect((fixture.nativeElement as HTMLElement).querySelector('a[target="_blank"]')).toBeNull();
  });

  it('Back navigates to the remembered messages view URL', async () => {
    const { fixture, router, lastView, backButton } = setup();
    await fixture.whenStable();
    backButton().click();
    expect(lastView.lastUrl).toHaveBeenCalled();
    expect(router.navigateByUrl).toHaveBeenCalledWith('/messages/table?tag=abc');
  });

  it('Clear on a pristine message resets straight to the Start screen (no confirm)', async () => {
    const { fixture, router, facade, discard, clearButton } = setup();
    await fixture.whenStable();
    clearButton().click();
    await fixture.whenStable();
    expect(discard.confirmDiscard).not.toHaveBeenCalled();
    expect(facade.resetWorkingState).toHaveBeenCalled();
    expect(router.navigate).toHaveBeenCalledWith(['/editor']);
  });

  it('Clear with unsaved user edits confirms before discarding', async () => {
    const { fixture, router, facade, discard, clearButton } = setup();
    facade.userTouchedState.set(true);
    await fixture.whenStable();

    discard.result = false;
    clearButton().click();
    await fixture.whenStable();
    // Rejected — nothing discarded.
    expect(discard.confirmDiscard).toHaveBeenCalled();
    expect(facade.resetWorkingState).not.toHaveBeenCalled();
    expect(router.navigate).not.toHaveBeenCalled();

    discard.result = true;
    clearButton().click();
    await fixture.whenStable();
    expect(facade.resetWorkingState).toHaveBeenCalled();
    expect(router.navigate).toHaveBeenCalledWith(['/editor']);
  });

  it('Save is disabled while untouched and enabled once the user edits', async () => {
    const { fixture, facade } = setup();
    await fixture.whenStable();
    const saveButton = () =>
      (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('.btn-primary')!;
    expect(saveButton().disabled).toBe(true);

    facade.userTouchedState.set(true);
    await fixture.whenStable();
    expect(saveButton().disabled).toBe(false);
  });

  it('a copy or template result never saved can be saved at once and reads unsaved, though untouched', async () => {
    const { fixture, facade, discard, clearButton } = setup();
    facade.dirtyState.set(true);
    facade.unstoredState.set(true);
    await fixture.whenStable();
    const el = fixture.nativeElement as HTMLElement;
    expect(facade.userTouched()).toBe(false);
    expect(el.querySelector<HTMLButtonElement>('.btn-primary')!.disabled).toBe(false);
    expect(el.textContent).toContain('editor.header.unsaved');
    expect(el.textContent).not.toContain('editor.header.saved');

    // Clear asks before dropping it.
    discard.result = false;
    clearButton().click();
    await fixture.whenStable();
    expect(discard.confirmDiscard).toHaveBeenCalled();
    expect(facade.resetWorkingState).not.toHaveBeenCalled();
  });

  it('Save validates, saves through the facade, and lands the edit route on the saved id', async () => {
    const { fixture, facade, router } = setup();
    facade.userTouchedState.set(true);
    await fixture.whenStable();

    (fixture.nativeElement as HTMLElement)
      .querySelector<HTMLButtonElement>('.btn-primary')!
      .click();
    await fixture.whenStable();
    expect(facade.validateForSave).toHaveBeenCalled();
    expect(facade.save).toHaveBeenCalled();
    expect(router.navigate).toHaveBeenCalledWith(['/editor/edit', 'm-1']);
  });

  it('Save from the status tab stays put (no tab switch)', async () => {
    const { fixture, facade, router } = setup();
    router.url = '/editor/status/m-1';
    facade.userTouchedState.set(true);
    await fixture.whenStable();

    (fixture.nativeElement as HTMLElement)
      .querySelector<HTMLButtonElement>('.btn-primary')!
      .click();
    await fixture.whenStable();
    expect(facade.save).toHaveBeenCalled();
    expect(router.navigate).not.toHaveBeenCalled();
  });

  it('Reload is hidden for a never-saved draft, reloads straight through when pristine', async () => {
    const { fixture, facade, discard } = setup();
    await fixture.whenStable();
    const reloadButton = () =>
      (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('.icon-button');
    expect(reloadButton()).toBeNull();

    facade.messageState.set({ mainType: 'NW', created: 1 });
    await fixture.whenStable();
    expect(reloadButton()).not.toBeNull();

    reloadButton()!.click();
    await fixture.whenStable();
    expect(discard.confirmDiscard).not.toHaveBeenCalled();
    expect(facade.reload).toHaveBeenCalled();
  });

  it('Reload with unsaved user edits confirms (reload wording) before discarding', async () => {
    const { fixture, facade, discard } = setup();
    facade.messageState.set({ mainType: 'NW', created: 1 });
    facade.userTouchedState.set(true);
    await fixture.whenStable();

    discard.result = false;
    (fixture.nativeElement as HTMLElement)
      .querySelector<HTMLButtonElement>('.icon-button')!
      .click();
    await fixture.whenStable();
    expect(discard.confirmDiscard).toHaveBeenCalledWith('editor.header.reloadConfirm');
    expect(facade.reload).not.toHaveBeenCalled();

    discard.result = true;
    (fixture.nativeElement as HTMLElement)
      .querySelector<HTMLButtonElement>('.icon-button')!
      .click();
    await fixture.whenStable();
    expect(facade.reload).toHaveBeenCalled();
  });

  it('a VERIFIED message confirms the status reset before saving', async () => {
    const { fixture, facade, confirm } = setup();
    facade.messageState.set({ mainType: 'NW', status: 'VERIFIED' });
    facade.userTouchedState.set(true);
    await fixture.whenStable();

    (fixture.nativeElement as HTMLElement)
      .querySelector<HTMLButtonElement>('.btn-primary')!
      .click();
    await fixture.whenStable();
    expect(confirm.active()).toBeDefined();
    expect(facade.save).not.toHaveBeenCalled();

    confirm.active()!.accept();
    expect(facade.save).toHaveBeenCalled();
  });
});
