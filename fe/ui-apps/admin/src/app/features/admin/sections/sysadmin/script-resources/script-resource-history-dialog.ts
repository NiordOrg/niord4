import { Component, computed, inject, signal } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';
import { FormsModule } from '@angular/forms';

import { LocaleFormatService } from '../../../../../core/main-shell/services/locale-format.service';
import { Dialog } from '../../../../../shared/components/dialog/dialog';
import { ProgressBar } from '../../../../../shared/components/progress-bar/progress-bar';
import { CodeMergeView } from '../../../components/code-merge-view/code-merge-view';
import { CodeTextInput } from '../../../components/code-text-input/code-text-input';
import type { CodeLanguage } from '../../../components/code-text-input/codemirror-editor';
import {
  ScriptResource,
  ScriptResourceHistory,
  ScriptResourceHistorySource,
  ScriptResourcesApiService,
  ScriptResourceType,
} from '../../../services/api/script-resources-api.service';

interface HistoryRow {
  readonly version: number;
  readonly created: number;
  readonly user?: string;
  /** What wrote the revision; `undefined` for a revision written before it was recorded. */
  readonly source?: ScriptResourceHistorySource;
  /** The resource content parsed out of the JSON snapshot. */
  readonly content: string;
}

/**
 * Revision-history viewer for a script resource. Lists revisions newest-first;
 * selecting one shows its source read-only, selecting two shows a side-by-side
 * text diff (oldest → newest) via the CodeMirror merge view. Selection is capped
 * at two.
 */
@Component({
  selector: 'app-script-resource-history-dialog',
  imports: [Dialog, FormsModule, ProgressBar, CodeTextInput, CodeMergeView, TranslocoPipe],
  templateUrl: './script-resource-history-dialog.html',
  host: { class: 'contents' },
})
export class ScriptResourceHistoryDialog {
  private readonly api = inject(ScriptResourcesApiService);
  private readonly localeFormat = inject(LocaleFormatService);

  protected readonly visible = signal(false);
  protected readonly loading = signal(false);
  protected readonly type = signal<ScriptResourceType>('FM');
  protected readonly rows = signal<readonly HistoryRow[]>([]);
  /** Versions currently checked, capped at two. */
  protected readonly selected = signal<readonly number[]>([]);

  protected readonly editorLanguage = computed<CodeLanguage>(() =>
    this.type() === 'JS' ? 'javascript' : 'ftl',
  );

  /** The selected rows in chronological order (oldest first) for the diff. */
  private readonly selectedRows = computed(() => {
    const versions = this.selected();
    return this.rows()
      .filter((row) => versions.includes(row.version))
      .sort((a, b) => a.version - b.version);
  });

  protected readonly singleContent = computed(() =>
    this.selectedRows().length === 1 ? this.selectedRows()[0].content : undefined,
  );

  protected readonly diffPair = computed(() => {
    const selectedRows = this.selectedRows();
    return selectedRows.length === 2
      ? { original: selectedRows[0].content, modified: selectedRows[1].content }
      : undefined;
  });

  open(id: number, type: ScriptResourceType): void {
    this.type.set(type);
    this.selected.set([]);
    this.rows.set([]);
    this.loading.set(true);
    this.visible.set(true);
    this.api.getHistory(id).subscribe({
      next: (history) => {
        this.rows.set(history.map((entry) => this.toRow(entry)));
        this.loading.set(false);
      },
      error: () => {
        this.rows.set([]);
        this.loading.set(false);
      },
    });
  }

  private toRow(entry: ScriptResourceHistory): HistoryRow {
    const base = {
      version: entry.version,
      created: entry.created,
      user: entry.user,
      source: entry.source,
    };
    try {
      const snapshot = JSON.parse(entry.snapshot) as Partial<ScriptResource>;
      return { ...base, content: snapshot.content ?? '' };
    } catch {
      return { ...base, content: '' };
    }
  }

  protected isSelectable(version: number): boolean {
    return this.selected().includes(version) || this.selected().length < 2;
  }

  protected toggle(version: number, checked: boolean): void {
    this.selected.update((versions) =>
      checked ? [...versions, version] : versions.filter((value) => value !== version),
    );
  }

  protected formatTime(epoch: number): string {
    return this.localeFormat.formatDateTime(new Date(epoch));
  }

  protected close(): void {
    this.visible.set(false);
  }
}
