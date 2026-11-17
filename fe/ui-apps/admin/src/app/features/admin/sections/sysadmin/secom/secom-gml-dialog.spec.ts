import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it } from 'vitest';

import { APP_CONFIG } from '../../../../../core/config/app-config.token';
import { provideTestTransloco } from '../../../../../shared/testing/transloco-testing';
import { S124Dataset } from '../../../services/api/secom-admin-api.service';
import { SecomGmlDialog } from './secom-gml-dialog';

interface DialogInternals {
  gml: () => string;
  loading: () => boolean;
  title: () => string;
  visible: () => boolean;
}

function setup() {
  TestBed.configureTestingModule({
    providers: [
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
      provideHttpClient(),
      provideHttpClientTesting(),
      provideTestTransloco(),
    ],
  });
  const fixture = TestBed.createComponent(SecomGmlDialog);
  fixture.detectChanges();
  return {
    fixture,
    dialog: fixture.componentInstance,
    internals: fixture.componentInstance as unknown as DialogInternals,
    httpTesting: TestBed.inject(HttpTestingController),
  };
}

const dataset = (uuid: string, mrn: string): S124Dataset => ({
  uuid,
  mrn,
  messageUid: 'msg',
  status: 'ACTIVE',
  gmlSize: 10,
});

describe('SecomGmlDialog', () => {
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('fetches the dataset GML as text and shows it', async () => {
    const { fixture, dialog, internals, httpTesting } = setup();
    dialog.open(dataset('u-a', 'urn:mrn:s124:a'));
    await fixture.whenStable();

    const req = httpTesting.expectOne('/rest/secom/datasets/u-a/gml');
    expect(req.request.responseType).toBe('text');
    expect(internals.loading()).toBe(true);
    req.flush('<S124:DataSet>A</S124:DataSet>');

    expect(internals.gml()).toBe('<S124:DataSet>A</S124:DataSet>');
    expect(internals.loading()).toBe(false);
    expect(internals.title()).toBe('urn:mrn:s124:a');
  });

  it('never shows the previous dataset GML under the new dataset title', async () => {
    const { fixture, dialog, internals, httpTesting } = setup();

    // Dataset A loads fully.
    dialog.open(dataset('u-a', 'urn:mrn:s124:a'));
    await fixture.whenStable();
    httpTesting.expectOne('/rest/secom/datasets/u-a/gml').flush('<S124:DataSet>A</S124:DataSet>');
    expect(internals.gml()).toBe('<S124:DataSet>A</S124:DataSet>');

    // Now open B. The title flips immediately — so the BODY must not still be A's,
    // or the operator reads A's contents believing they are B's.
    dialog.open(dataset('u-b', 'urn:mrn:s124:b'));
    await fixture.whenStable();
    expect(internals.title()).toBe('urn:mrn:s124:b');
    expect(internals.gml()).toBe('');
    expect(internals.loading()).toBe(true);

    httpTesting.expectOne('/rest/secom/datasets/u-b/gml').flush('<S124:DataSet>B</S124:DataSet>');
    expect(internals.gml()).toBe('<S124:DataSet>B</S124:DataSet>');
    expect(internals.loading()).toBe(false);
  });

  it('a superseded fetch is cancelled, so it cannot land over the newer dataset', async () => {
    const { fixture, dialog, internals, httpTesting } = setup();

    dialog.open(dataset('u-a', 'urn:mrn:s124:a'));
    await fixture.whenStable();
    const slow = httpTesting.expectOne('/rest/secom/datasets/u-a/gml');
    expect(slow.cancelled).toBe(false);

    // Switching before A answers must unsubscribe A — otherwise a slow earlier
    // response could overwrite B's content after it had already rendered.
    dialog.open(dataset('u-b', 'urn:mrn:s124:b'));
    await fixture.whenStable();
    expect(slow.cancelled).toBe(true);

    httpTesting.expectOne('/rest/secom/datasets/u-b/gml').flush('<S124:DataSet>B</S124:DataSet>');
    expect(internals.gml()).toBe('<S124:DataSet>B</S124:DataSet>');
  });

  it('renders a failed fetch inline in the viewer instead of toasting', async () => {
    const { fixture, dialog, internals, httpTesting } = setup();
    dialog.open(dataset('u-a', 'urn:mrn:s124:a'));
    await fixture.whenStable();
    httpTesting
      .expectOne('/rest/secom/datasets/u-a/gml')
      .flush('Unknown dataset u-a', { status: 400, statusText: 'Bad Request' });

    expect(internals.gml()).toBe('Unknown dataset u-a');
    expect(internals.loading()).toBe(false);
  });

  it('surfaces the problem-document detail, not a generic transport message', async () => {
    const { fixture, dialog, internals, httpTesting } = setup();
    dialog.open(dataset('u-gone', 'urn:mrn:s124:gone'));
    await fixture.whenStable();

    // What the backend really sends when the dataset was removed after the list
    // loaded. The request is responseType:'text', so Angular does NOT parse it —
    // the body arrives as the raw problem document.
    httpTesting.expectOne('/rest/secom/datasets/u-gone/gml').flush(
      JSON.stringify({
        title: 'Bad Request',
        status: 400,
        detail: 'Unknown dataset u-gone',
        instance: '/rest/secom/datasets/u-gone/gml',
      }),
      { status: 400, statusText: 'Bad Request' },
    );

    expect(internals.gml()).toBe('Unknown dataset u-gone');
    // Neither the raw JSON nor Angular's "Http failure response for ..." message.
    expect(internals.gml()).not.toContain('{');
    expect(internals.gml()).not.toContain('Http failure');
  });

  it('falls back to the transport message rather than an empty viewer', async () => {
    const { fixture, dialog, internals, httpTesting } = setup();
    dialog.open(dataset('u-a', 'urn:mrn:s124:a'));
    await fixture.whenStable();
    // No body at all (a dropped connection): a blank viewer under a dataset
    // title would read as "this dataset has no GML" rather than "it failed".
    httpTesting
      .expectOne('/rest/secom/datasets/u-a/gml')
      .flush('', { status: 504, statusText: 'Gateway Timeout' });

    expect(internals.gml()).not.toBe('');
    expect(internals.loading()).toBe(false);
  });

  it('re-opening the same dataset refetches rather than showing a stale body', async () => {
    const { fixture, dialog, internals, httpTesting } = setup();
    dialog.open(dataset('u-a', 'urn:mrn:s124:a'));
    await fixture.whenStable();
    httpTesting.expectOne('/rest/secom/datasets/u-a/gml').flush('<S124:DataSet>v1</S124:DataSet>');

    // Same uuid → the computed does not change → no refetch, and the body stays.
    // (Documented behaviour: the dialog is keyed on the dataset, not on opening.)
    dialog.open(dataset('u-a', 'urn:mrn:s124:a'));
    await fixture.whenStable();
    expect(internals.gml()).toBe('<S124:DataSet>v1</S124:DataSet>');
    expect(internals.visible()).toBe(true);
  });
});
