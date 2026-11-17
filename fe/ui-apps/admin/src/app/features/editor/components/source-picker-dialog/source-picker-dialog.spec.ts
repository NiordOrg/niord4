import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { TranslocoService } from '@jsverse/transloco';
import { of, throwError } from 'rxjs';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { NotificationService } from '../../../../core/notifications/notification.service';
import { provideNiordIcons } from '../../../../shared/icons/niord-icons';
import { provideTestTransloco } from '../../../../shared/testing/transloco-testing';
import { provideTestSessionZone } from '../../../../shared/testing/zone-testing';
import { SourcesApiService, type Source } from '../../../messages/services/api/sources-api.service';
import { SourcePickerDialog } from './source-picker-dialog';
import { provideTestLanguages } from '../../../../shared/testing/language-testing';

const activeSource: Source = {
  id: 1,
  active: true,
  descs: [{ lang: 'en', name: 'Danish Met Office', abbreviation: 'DMI' }],
};
const inactiveSource: Source = {
  id: 2,
  active: false,
  descs: [{ lang: 'en', name: 'Retired Source', abbreviation: 'OLD' }],
};
const otherActive: Source = {
  id: 3,
  active: true,
  descs: [{ lang: 'en', name: 'Coast Guard', abbreviation: 'CG' }],
};

// getSource returns ALL-language descs (unlike the single-language list rows), so the
// composed text carrying a non-empty Danish entry proves the details fetch was used.
const detailById: Record<number, Source> = {
  1: {
    id: 1,
    active: true,
    descs: [
      { lang: 'en', name: 'Danish Met Office', abbreviation: 'DMI-EN' },
      { lang: 'da', name: 'Dansk Met', abbreviation: 'DMI-DA' },
    ],
  },
  3: {
    id: 3,
    active: true,
    descs: [
      { lang: 'en', name: 'Coast Guard', abbreviation: 'CG-EN' },
      { lang: 'da', name: 'Kystvagt', abbreviation: 'CG-DA' },
    ],
  },
};

@Component({
  imports: [SourcePickerDialog],
  template: `<app-source-picker-dialog [(visible)]="visible" />`,
})
class Host {
  readonly visible = signal(false);
}

interface Options {
  getSourceError?: boolean;
  /** The session domain's zone — the one a source date is picked in and printed in. */
  timeZone?: string;
  /** No session domain at all, so there is no zone to read a source date in. */
  noSessionDomain?: boolean;
}

function setup(options: Options = {}) {
  const notifyError = vi.fn();
  const getAll = vi.fn(() => of([activeSource, inactiveSource, otherActive]));
  const getSource = vi.fn((id: number) =>
    options.getSourceError ? throwError(() => new Error('boom')) : of(detailById[id]),
  );

  TestBed.configureTestingModule({
    providers: [
      provideTestLanguages(),
      provideTestTransloco(),
      provideNiordIcons(),
      provideTestSessionZone(options.noSessionDomain ? undefined : (options.timeZone ?? 'UTC')),
      { provide: SourcesApiService, useValue: { getAll, getSource } },
      { provide: NotificationService, useValue: { error: notifyError } },
    ],
  });
  TestBed.inject(TranslocoService).load('en').subscribe();

  const fixture = TestBed.createComponent(Host);
  fixture.autoDetectChanges();
  const dialog = fixture.debugElement.query(By.directive(SourcePickerDialog))
    .componentInstance as SourcePickerDialog;
  // Capture the composed text the dialog emits back for the host to append.
  const sourcesAdded = vi.fn();
  dialog.sourcesAdded.subscribe(sourcesAdded);
  return {
    fixture,
    host: fixture.componentInstance,
    dialog,
    sourcesAdded,
    notifyError,
    getSource,
  };
}

const q = (sel: string) => document.querySelector(sel);
const qa = (sel: string) => [...document.querySelectorAll(sel)];
const listCheckboxes = () => qa('.dialog-body input[type="checkbox"]') as HTMLInputElement[];
const addButton = () => q('.dialog-foot .btn-primary') as HTMLButtonElement;

async function open(fixture: ReturnType<typeof setup>['fixture']) {
  fixture.componentInstance.visible.set(true);
  await fixture.whenStable();
}

/** Hand the selected source the instant its (zone-aware) date picker would emit. */
async function pickDate(
  fixture: ReturnType<typeof setup>['fixture'],
  dialog: SourcePickerDialog,
  instant: Date,
) {
  (dialog as unknown as { setDate(id: number, date: Date): void }).setDate(1, instant);
  await fixture.whenStable();
}

afterEach(() => {
  document.querySelectorAll('.cdk-overlay-container').forEach((el) => el.replaceChildren());
});

describe('SourcePickerDialog', () => {
  it('lists only ACTIVE sources', async () => {
    const { fixture } = setup();
    await open(fixture);

    expect(q('.dialog-panel')).not.toBeNull();
    expect(listCheckboxes().length).toBe(2);
    expect(q('.dialog-body')!.textContent).toContain('Danish Met Office');
    expect(q('.dialog-body')!.textContent).toContain('Coast Guard');
    expect(q('.dialog-body')!.textContent).not.toContain('Retired Source');
  });

  it('filters the list by name/abbreviation (case-insensitive)', async () => {
    const { fixture } = setup();
    await open(fixture);

    const filter = q('.dialog-body input[type="text"]') as HTMLInputElement;
    filter.value = 'coast';
    filter.dispatchEvent(new Event('input', { bubbles: true }));
    await fixture.whenStable();

    expect(listCheckboxes().length).toBe(1);
    expect(q('.dialog-body')!.textContent).toContain('Coast Guard');
    expect(q('.dialog-body')!.textContent).not.toContain('Danish Met Office');
  });

  it('shows the selection table only once a source is selected', async () => {
    const { fixture } = setup();
    await open(fixture);

    expect(q('app-date-picker')).toBeNull();

    listCheckboxes()[0].click();
    await fixture.whenStable();

    expect(q('app-date-picker')).not.toBeNull();
  });

  it('disables Add until a source is selected', async () => {
    const { fixture } = setup();
    await open(fixture);

    expect(addButton().disabled).toBe(true);

    listCheckboxes()[0].click();
    await fixture.whenStable();

    expect(addButton().disabled).toBe(false);
  });

  it('composes from the all-language getSource descs and emits it via sourcesAdded', async () => {
    const { fixture, sourcesAdded, getSource } = setup();
    await open(fixture);

    listCheckboxes()[0].click();
    await fixture.whenStable();
    addButton().click();
    await fixture.whenStable();

    expect(getSource).toHaveBeenCalledWith(1);
    expect(sourcesAdded).toHaveBeenCalledTimes(1);
    const map = sourcesAdded.mock.calls[0][0] as Record<string, string>;
    expect(map['en']).toContain('DMI-EN');
    // The Danish entry is non-empty only because the details fetch carried the da desc.
    expect(map['da']).toContain('DMI-DA');
    expect(fixture.componentInstance.visible()).toBe(false);
  });

  it('flows the picked date into the composed text', async () => {
    const { fixture, sourcesAdded, dialog } = setup();
    await open(fixture);

    listCheckboxes()[0].click();
    await fixture.whenStable();
    // What the zone-aware picker emits for a clicked 4 March in a UTC domain.
    await pickDate(fixture, dialog, new Date('2026-03-04T00:00:00.000Z'));
    addButton().click();
    await fixture.whenStable();

    const map = sourcesAdded.mock.calls[0][0] as Record<string, string>;
    expect(map['en']).toBe('DMI-EN 4 March 2026');
  });

  // The same instant is two different days depending on the zone it is read in; the dialog
  // reads it in the session domain's, so the day the user picked is the day that prints.
  it('prints a late-evening source date as that day in a UTC domain', async () => {
    const { fixture, sourcesAdded, dialog } = setup({ timeZone: 'UTC' });
    await open(fixture);

    listCheckboxes()[0].click();
    await fixture.whenStable();
    await pickDate(fixture, dialog, new Date('2026-03-05T23:30:00.000Z'));
    addButton().click();
    await fixture.whenStable();

    const map = sourcesAdded.mock.calls[0][0] as Record<string, string>;
    expect(map['en']).toBe('DMI-EN 5 March 2026');
    expect(map['da']).toBe('DMI-DA 5. marts 2026');
  });

  it('prints the same instant as the next day in a Copenhagen domain', async () => {
    const { fixture, sourcesAdded, dialog } = setup({ timeZone: 'Europe/Copenhagen' });
    await open(fixture);

    listCheckboxes()[0].click();
    await fixture.whenStable();
    await pickDate(fixture, dialog, new Date('2026-03-05T23:30:00.000Z'));
    addButton().click();
    await fixture.whenStable();

    const map = sourcesAdded.mock.calls[0][0] as Record<string, string>;
    expect(map['en']).toBe('DMI-EN 6 March 2026');
    expect(map['da']).toBe('DMI-DA 6. marts 2026');
  });

  /**
   * With no session domain there is no zone to read a source date in, and the browser's own
   * would name a day nobody chose — so Add says so instead of composing.
   */
  it('refuses to compose and toasts when no session domain gives a zone', async () => {
    const { fixture, sourcesAdded, notifyError, getSource } = setup({ noSessionDomain: true });
    await open(fixture);

    listCheckboxes()[0].click();
    await fixture.whenStable();
    addButton().click();
    await fixture.whenStable();

    expect(notifyError).toHaveBeenCalledWith('common.dateTime.noTimeZone');
    expect(getSource).not.toHaveBeenCalled();
    expect(sourcesAdded).not.toHaveBeenCalled();
    expect(fixture.componentInstance.visible()).toBe(true);
  });

  it('toasts and stays open when the details fetch fails', async () => {
    const { fixture, sourcesAdded, notifyError } = setup({ getSourceError: true });
    await open(fixture);

    listCheckboxes()[0].click();
    await fixture.whenStable();
    addButton().click();
    await fixture.whenStable();

    expect(sourcesAdded).not.toHaveBeenCalled();
    expect(notifyError).toHaveBeenCalledWith('editor.dialog.source.addError');
    expect(fixture.componentInstance.visible()).toBe(true);
    expect(q('.dialog-panel')).not.toBeNull();
  });
});
