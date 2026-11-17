import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { APP_CONFIG } from '../../../../../core/config/app-config.token';
import { LanguageService } from '../../../../../core/main-shell/services/language.service';
import { NotificationService } from '../../../../../core/notifications/notification.service';
import { provideNiordIcons } from '../../../../../shared/icons/niord-icons';
import { provideTestTransloco } from '../../../../../shared/testing/transloco-testing';
import type { AdminMessageSeries } from '../../../models/admin-message-series.model';
import { SeriesEditor } from './series-editor';
import type { SeriesEditorModel } from './series-page';
import { SeriesFacade } from './series.facade';

@Component({
  imports: [SeriesEditor],
  template: `
    <app-series-editor
      [model]="model()"
      (saved)="savedCount = savedCount + 1"
      (closed)="model.set(undefined)"
    />
  `,
})
class Host {
  readonly model = signal<SeriesEditorModel | undefined>(undefined);
  savedCount = 0;
}

interface EditorInternals {
  form: SeriesEditor['form'];
  typeChecks: SeriesEditor['typeChecks'];
  editorFields: SeriesEditor['editorFields'];
  displayNextNumber: () => number | undefined;
  changed: () => boolean;
  saveDisabled: () => boolean;
  saving: () => boolean;
  showShortFormat: () => boolean;
  showNavtex: () => boolean;
  showNextNumber: () => boolean;
  shortFormatTokens: () => readonly { token: string; label: string }[];
  setMainType: (mainType: 'NW' | 'NM') => void;
  setSequenceType: (type: AdminMessageSeries['numberSequenceType']) => void;
  toggleType: (mainType: 'NW' | 'NM', type: string) => void;
  onNextNumberUpdated: () => void;
  save: () => void;
}

const NEW_SERIES: AdminMessageSeries = {
  seriesId: '',
  mainType: 'NW',
  numberSequenceType: 'YEARLY',
  shortFormat: '',
  types: [],
  excludeFromMessageIndex: false,
};

const NW_YEARLY: AdminMessageSeries = {
  seriesId: 'dma-nw',
  mainType: 'NW',
  types: ['COASTAL_WARNING'],
  numberSequenceType: 'YEARLY',
  shortFormat: 'NW-${number}-${year}',
  navtexFormat: 'DANISH NAV. WARNING ${short-id}',
  nextMessageNumber: 14,
  editorFields: ['charts'],
  excludeFromMessageIndex: false,
};

function setup() {
  TestBed.configureTestingModule({
    providers: [
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
      provideHttpClient(),
      provideHttpClientTesting(),
      provideTestTransloco(),
      provideNiordIcons(),
      { provide: LanguageService, useValue: { selectedLanguage: () => 'en' } },
      {
        provide: NotificationService,
        useValue: { success: vi.fn(), error: vi.fn() },
      },
      { provide: SeriesFacade, useValue: { refresh: vi.fn() } },
    ],
  });
  const fixture = TestBed.createComponent(Host);
  fixture.detectChanges();
  return {
    fixture,
    host: fixture.componentInstance,
    httpTesting: TestBed.inject(HttpTestingController),
  };
}

function open(
  fixture: ReturnType<typeof setup>['fixture'],
  host: Host,
  model: SeriesEditorModel,
): EditorInternals {
  host.model.set(model);
  fixture.detectChanges();
  return fixture.debugElement.children[0].componentInstance as unknown as EditorInternals;
}

/** The drawer portals its body to the overlay container — query the document. */
function drawerField<T extends HTMLElement>(selector: string): T | null {
  return document.querySelector<T>(selector);
}

describe('SeriesEditor', () => {
  afterEach(() => {
    TestBed.inject(HttpTestingController).verify();
    document.querySelectorAll('.cdk-overlay-container').forEach((el) => el.replaceChildren());
  });

  it('requires the seriesId in add mode and locks it in edit mode', () => {
    const { fixture, host } = setup();
    let editor = open(fixture, host, { mode: 'add', series: NEW_SERIES });
    expect(editor.form.seriesId().disabled()).toBe(false);
    expect(editor.saveDisabled()).toBe(true); // pristine AND invalid (empty id)
    editor.form.seriesId().value.set('dma-new');
    expect(editor.saveDisabled()).toBe(false);

    editor = open(fixture, host, { mode: 'edit', series: NW_YEARLY });
    expect(editor.form.seriesId().disabled()).toBe(true);
    expect(drawerField<HTMLInputElement>('#series-id')?.disabled).toBe(true);
  });

  it('copy (add mode with a blanked id) leaves the id editable and required again', () => {
    const { fixture, host } = setup();
    const editor = open(fixture, host, {
      mode: 'add',
      series: { ...NW_YEARLY, seriesId: '' },
    });
    expect(editor.form.seriesId().disabled()).toBe(false);
    // The blanked id makes the form invalid until a new one is typed.
    editor.form.shortFormat().value.set('changed');
    expect(editor.saveDisabled()).toBe(true);
    editor.form.seriesId().value.set('dma-copy');
    expect(editor.saveDisabled()).toBe(false);
  });

  it('a freshly opened edit form has Save disabled; edit-then-revert re-disables it', () => {
    const { fixture, host } = setup();
    const editor = open(fixture, host, { mode: 'edit', series: NW_YEARLY });
    expect(editor.saveDisabled()).toBe(true);
    editor.form.shortFormat().value.set('NW-${number}');
    expect(editor.saveDisabled()).toBe(false);
    editor.form.shortFormat().value.set('NW-${number}-${year}');
    expect(editor.saveDisabled()).toBe(true);
  });

  it('out-of-form edits (restrict types, editor fields) flip the save gate too', () => {
    const { fixture, host } = setup();
    const editor = open(fixture, host, { mode: 'edit', series: NW_YEARLY });
    expect(editor.saveDisabled()).toBe(true);

    editor.toggleType('NW', 'LOCAL_WARNING');
    expect(editor.changed()).toBe(true);
    expect(editor.saveDisabled()).toBe(false);
    editor.toggleType('NW', 'LOCAL_WARNING');
    expect(editor.saveDisabled()).toBe(true); // reverted

    editor.editorFields.set(['charts', 'signals']);
    expect(editor.saveDisabled()).toBe(false);
    editor.editorFields.set(['charts']);
    expect(editor.saveDisabled()).toBe(true);
  });

  it('hides the Short Format group for NONE while the working text survives in-session', () => {
    const { fixture, host } = setup();
    const editor = open(fixture, host, { mode: 'edit', series: NW_YEARLY });
    expect(editor.showShortFormat()).toBe(true);
    expect(drawerField<HTMLInputElement>('#series-short-format')?.value).toBe(
      'NW-${number}-${year}',
    );

    editor.setSequenceType('NONE');
    fixture.detectChanges();
    expect(editor.showShortFormat()).toBe(false);
    expect(drawerField('#series-short-format')).toBeNull();

    // Flip back: the text was never deleted from the working model.
    editor.setSequenceType('YEARLY');
    fixture.detectChanges();
    expect(drawerField<HTMLInputElement>('#series-short-format')?.value).toBe(
      'NW-${number}-${year}',
    );
  });

  it('shows the NAVTEX preamble for NW only — and it survives NONE (unlike the short format)', () => {
    const { fixture, host } = setup();
    const editor = open(fixture, host, { mode: 'edit', series: NW_YEARLY });
    expect(editor.showNavtex()).toBe(true);
    editor.setSequenceType('NONE');
    expect(editor.showNavtex()).toBe(true); // NONE does not hide NAVTEX

    editor.setMainType('NM');
    fixture.detectChanges();
    expect(editor.showNavtex()).toBe(false);
    expect(drawerField('#series-navtex-format')).toBeNull();
  });

  it('offers the ${t-or-p} short-format token for NM series only', () => {
    const { fixture, host } = setup();
    const editor = open(fixture, host, { mode: 'edit', series: NW_YEARLY });
    const tokensOf = () => editor.shortFormatTokens().map((t) => t.token);
    expect(tokensOf()).not.toContain('${t-or-p}');
    expect(tokensOf()).toEqual([
      '${year}',
      '${year-2-digits}',
      '${week}',
      '${week-2-digits}',
      '${number}',
      '${number-3-digits}',
      '${id}',
      '${legacy-id}',
    ]);

    editor.setMainType('NM');
    expect(tokensOf()).toContain('${t-or-p}');
  });

  it('keeps restrict-type checks per main type across NW→NM→NW switches (session memory)', () => {
    const { fixture, host } = setup();
    const editor = open(fixture, host, { mode: 'edit', series: NW_YEARLY });
    // Seeded from types: COASTAL ticked, others not; NM map all unticked.
    expect(editor.typeChecks().NW['COASTAL_WARNING']).toBe(true);
    expect(editor.typeChecks().NM['TEMPORARY_NOTICE']).toBe(false);

    editor.toggleType('NW', 'LOCAL_WARNING');
    editor.setMainType('NM');
    editor.toggleType('NM', 'TEMPORARY_NOTICE');
    editor.setMainType('NW');

    // The session's NW checks survived the round trip; the NM check waits in its map.
    expect(editor.typeChecks().NW['LOCAL_WARNING']).toBe(true);
    expect(editor.typeChecks().NW['COASTAL_WARNING']).toBe(true);
    expect(editor.typeChecks().NM['TEMPORARY_NOTICE']).toBe(true);
  });

  it('shows the next-number row in edit mode for auto-numbered series only, pencil locked while dirty', () => {
    const { fixture, host } = setup();
    let editor = open(fixture, host, { mode: 'add', series: NEW_SERIES });
    expect(editor.showNextNumber()).toBe(false); // add mode

    editor = open(fixture, host, { mode: 'edit', series: NW_YEARLY });
    expect(editor.showNextNumber()).toBe(true);
    expect(editor.displayNextNumber()).toBe(14);
    fixture.detectChanges();
    const pencil = document.querySelector<HTMLButtonElement>(
      '[aria-label="admin.series.editNextNumber"]',
    );
    expect(pencil?.disabled).toBe(false);

    // A working-copy change hides nothing but locks the pencil (dirty-gate)…
    editor.form.shortFormat().value.set('changed');
    fixture.detectChanges();
    expect(pencil?.disabled).toBe(true);

    // …and a MANUAL/NONE sequence type hides the row outright.
    editor.setSequenceType('MANUAL');
    expect(editor.showNextNumber()).toBe(false);
  });

  it('saves the normalized payload: current-mainType types only, shortFormat stripped for NONE', () => {
    const { fixture, host, httpTesting } = setup();
    const editor = open(fixture, host, { mode: 'edit', series: NW_YEARLY });
    editor.setMainType('NM');
    editor.toggleType('NM', 'PERMANENT_NOTICE');
    editor.setSequenceType('NONE');
    editor.save();

    const req = httpTesting.expectOne('/rest/message-series/series/dma-nw');
    expect(req.request.method).toBe('PUT');
    const body = req.request.body as AdminMessageSeries;
    expect(body.mainType).toBe('NM');
    // NW checks (COASTAL) dropped; only the current NM check travels.
    expect(body.types).toEqual(['PERMANENT_NOTICE']);
    expect('shortFormat' in body).toBe(false);
    // The working model still holds the text (stripped from the payload only).
    expect(editor.form.shortFormat().value()).toBe('NW-${number}-${year}');
    // NAVTEX survives NONE and the NM main type.
    expect(body.navtexFormat).toBe('DANISH NAV. WARNING ${short-id}');
    req.flush({});
    expect(host.savedCount).toBe(1);
  });

  it('a short or NAVTEX format longer than its column keeps Save disabled, with a counter under each (ERR7)', () => {
    const { fixture, host } = setup();
    const editor = open(fixture, host, { mode: 'edit', series: NW_YEARLY });
    expect(document.querySelectorAll('app-length-counter p')).toHaveLength(2);

    for (const field of ['shortFormat', 'navtexFormat'] as const) {
      editor.form[field]().value.set('x'.repeat(256));
      fixture.detectChanges();
      expect(editor.saveDisabled()).toBe(true);
      expect(document.querySelector('app-length-counter p[role="alert"]')).not.toBeNull();

      editor.form[field]().value.set('x'.repeat(255));
      fixture.detectChanges();
      expect(editor.saveDisabled()).toBe(false);
    }
  });

  it('a long short format no longer blocks Save once NONE strips it from the payload (ERR7)', () => {
    const { fixture, host } = setup();
    const editor = open(fixture, host, { mode: 'edit', series: NW_YEARLY });
    editor.form.shortFormat().value.set('x'.repeat(256));
    expect(editor.saveDisabled()).toBe(true);

    editor.setSequenceType('NONE');
    expect(editor.saveDisabled()).toBe(false);
  });

  it('add saves via POST to the trailing-slash collection path', () => {
    const { fixture, host, httpTesting } = setup();
    const editor = open(fixture, host, { mode: 'add', series: NEW_SERIES });
    editor.form.seriesId().value.set('dma-new');
    editor.save();

    const req = httpTesting.expectOne('/rest/message-series/series/');
    expect(req.request.method).toBe('POST');
    const body = req.request.body as AdminMessageSeries;
    expect(body.seriesId).toBe('dma-new');
    expect(body.types).toEqual([]); // unrestricted stays []
    expect(body.shortFormat).toBe('');
    expect('navtexFormat' in body).toBe(false);
    req.flush({});
    expect(TestBed.inject(NotificationService).success).toHaveBeenCalledWith(
      'admin.series.toast.saved',
    );
    expect(host.savedCount).toBe(1);
  });

  it('a failed save keeps the drawer open with values intact and toasts the error', () => {
    const { fixture, host, httpTesting } = setup();
    const editor = open(fixture, host, { mode: 'edit', series: NW_YEARLY });
    editor.form.shortFormat().value.set('NW-${number}');
    editor.save();

    httpTesting
      .expectOne('/rest/message-series/series/dma-nw')
      .flush('boom', { status: 500, statusText: 'Server Error' });

    expect(host.savedCount).toBe(0);
    expect(host.model()).toBeDefined(); // drawer still open
    expect(editor.saving()).toBe(false); // retry possible
    expect(editor.form.shortFormat().value()).toBe('NW-${number}');
    expect(TestBed.inject(NotificationService).error).toHaveBeenCalledWith(
      'admin.series.toast.saveError',
    );
  });

  it('a popover update refreshes the list and the row display in the background', () => {
    const { fixture, host, httpTesting } = setup();
    const editor = open(fixture, host, { mode: 'edit', series: NW_YEARLY });
    editor.onNextNumberUpdated();

    expect(TestBed.inject(SeriesFacade).refresh).toHaveBeenCalled();
    const year = new Date().getFullYear();
    httpTesting.expectOne(`/rest/message-series/series/dma-nw/number/${year}`).flush(43);
    expect(editor.displayNextNumber()).toBe(43);
    expect(host.model()).toBeDefined(); // the drawer stayed open
  });
});
