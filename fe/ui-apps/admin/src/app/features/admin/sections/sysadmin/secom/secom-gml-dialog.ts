import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { TranslocoPipe } from '@jsverse/transloco';
import { catchError, map, of, startWith, switchMap } from 'rxjs';

import { Dialog } from '../../../../../shared/components/dialog/dialog';
import { ProgressBar } from '../../../../../shared/components/progress-bar/progress-bar';
import { S124Dataset, SecomAdminApiService } from '../../../services/api/secom-admin-api.service';
import { problemDetail } from '../../../../../shared/api/problem-detail';

/** What the viewer is showing: the body plus whether a fetch is still running. */
interface GmlState {
  readonly gml: string;
  readonly loading: boolean;
}

const IDLE: GmlState = { gml: '', loading: false };
const LOADING: GmlState = { gml: '', loading: true };

/**
 * Read-only viewer for a dataset's stored S-124 GML. Opening sets the dataset;
 * the content then auto-loads, with `switchMap` cancelling a superseded request
 * so a slow earlier response cannot land over a newer dataset's GML.
 *
 * The `startWith(LOADING)` is load-bearing, not cosmetic: `switchMap` cancels the
 * previous REQUEST but `toSignal` keeps the previously EMITTED value, so without
 * it opening dataset B would show B's title above A's GML until B's fetch
 * resolved — the operator reads one dataset's contents believing it is another's.
 * Emitting on every uuid change clears the body at the moment the title changes.
 *
 * A fetch failure renders inline in the viewer (no toast) — the read-failure
 * convention, and the body is often the useful part.
 */
@Component({
  selector: 'app-secom-gml-dialog',
  imports: [Dialog, ProgressBar, TranslocoPipe],
  templateUrl: './secom-gml-dialog.html',
  // The dialog portals to the overlay container, so this host renders nothing in
  // place — keep it out of the tab's layout entirely (ui-standards §6).
  host: { class: 'contents' },
})
export class SecomGmlDialog {
  private readonly api = inject(SecomAdminApiService);

  protected readonly visible = signal(false);
  protected readonly dataset = signal<S124Dataset | undefined>(undefined);

  protected readonly title = computed(() => this.dataset()?.mrn ?? '');

  private readonly uuid = computed(() => this.dataset()?.uuid);

  private readonly state = toSignal(
    toObservable(this.uuid).pipe(
      switchMap((uuid) =>
        uuid === undefined
          ? of(IDLE)
          : this.api.getDatasetGml(uuid).pipe(
              map((gml): GmlState => ({ gml, loading: false })),
              catchError((err: unknown) =>
                of<GmlState>({ gml: stringifyError(err), loading: false }),
              ),
              startWith(LOADING),
            ),
      ),
    ),
    { initialValue: IDLE },
  );

  protected readonly gml = computed(() => this.state().gml);
  protected readonly loading = computed(() => this.state().loading);

  open(dataset: S124Dataset): void {
    this.dataset.set(dataset);
    this.visible.set(true);
  }

  protected close(): void {
    this.visible.set(false);
  }
}

/**
 * What to show in the viewer when the fetch failed, most useful first:
 *
 * 1. the problem document's `detail` ("Unknown dataset <uuid>" — the case an
 *    operator actually hits, when the dataset was removed after the list
 *    loaded, or reconciliation cancelled it);
 * 2. a non-JSON body verbatim, since a plain-text error IS the message;
 * 3. Angular's transport message, which at least names the status.
 *
 * Never an empty viewer: a blank box under a dataset title reads as "this
 * dataset has no GML" rather than "the fetch failed".
 */
function stringifyError(err: unknown): string {
  const detail = problemDetail(err);
  if (detail !== undefined) {
    return detail;
  }
  if (err instanceof HttpErrorResponse) {
    const body: unknown = err.error;
    return typeof body === 'string' && body.trim() !== '' ? body : err.message;
  }
  return err instanceof Error ? err.message : '';
}
