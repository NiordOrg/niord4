import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component, viewChild } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it } from 'vitest';

import { APP_CONFIG } from '../../../../../core/config/app-config.token';
import { provideNiordIcons } from '../../../../../shared/icons/niord-icons';
import { provideTestTransloco } from '../../../../../shared/testing/transloco-testing';
import { ScriptResourceHistoryDialog } from './script-resource-history-dialog';

@Component({
  imports: [ScriptResourceHistoryDialog],
  template: `<app-script-resource-history-dialog />`,
})
class Host {
  readonly dialog = viewChild.required(ScriptResourceHistoryDialog);
}

async function openWith(history: unknown[]) {
  TestBed.configureTestingModule({
    providers: [
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
      provideHttpClient(),
      provideHttpClientTesting(),
      provideTestTransloco(),
      provideNiordIcons(),
    ],
  });
  const fixture = TestBed.createComponent(Host);
  fixture.autoDetectChanges();
  await fixture.whenStable();
  fixture.componentInstance.dialog().open(5, 'FM');
  TestBed.inject(HttpTestingController)
    .expectOne('/rest/script-resources/script-resource/5/history')
    .flush(history);
  await fixture.whenStable();
  const doc = fixture.nativeElement.ownerDocument as Document;
  return {
    header: Array.from(doc.querySelectorAll('[role="dialog"] thead th')).map((th) =>
      th.textContent?.trim(),
    ),
    // Version, user and source of each row; the first cell is the tick, the third the time.
    rows: Array.from(doc.querySelectorAll('[role="dialog"] tbody tr')).map((tr) => {
      const cells = Array.from(tr.querySelectorAll('td')).map((td) => td.textContent?.trim());
      return [cells[1], cells[3], cells[4]];
    }),
  };
}

describe('ScriptResourceHistoryDialog', () => {
  afterEach(() => {
    document.querySelectorAll('.cdk-overlay-container').forEach((el) => el.replaceChildren());
    TestBed.inject(HttpTestingController).verify();
  });

  it('shows who wrote each revision and from where; an older revision has no source', async () => {
    const { header, rows } = await openWith([
      { version: 3, created: 3000, user: 'sysadmin', source: 'import', snapshot: '{}' },
      { version: 2, created: 2000, user: 'editor-user', source: 'editor', snapshot: '{}' },
      { version: 1, created: 1000, snapshot: '{"content":"x"}' },
    ]);
    expect(header).toEqual([
      '',
      'admin.scriptResources.history.version',
      'admin.scriptResources.history.created',
      'admin.scriptResources.history.user',
      'admin.scriptResources.history.source',
    ]);
    expect(rows).toEqual([
      ['3', 'sysadmin', 'admin.scriptResources.history.sourceImport'],
      ['2', 'editor-user', 'admin.scriptResources.history.sourceEditor'],
      ['1', 'admin.scriptResources.history.system', ''],
    ]);
  });

  it('names a revision without an author System, whatever its source', async () => {
    const { rows } = await openWith([
      { version: 1, created: 1000, user: null, source: 'import', snapshot: '{}' },
    ]);
    expect(rows).toEqual([
      ['1', 'admin.scriptResources.history.system', 'admin.scriptResources.history.sourceImport'],
    ]);
  });
});
