import { DOCUMENT, DestroyRef, Injectable, inject, signal } from '@angular/core';

import { BatchApiService } from './api/batch-api.service';

const POLL_INTERVAL_MS = 10_000;

/**
 * Polls the running-batch-jobs count for the rail badge: initial load + every
 * 10 seconds while the providing shell is mounted, stopping on destroy. The
 * poll pauses while the tab is hidden and resumes with an immediate load on
 * return (deliberately no background-tab polling). Poll failures keep the last
 * known value — the badge never surfaces transport errors.
 *
 * Provide at the area-shell level so the timer's lifetime is the area visit.
 */
@Injectable()
export class BatchStatusService {
  private readonly api = inject(BatchApiService);
  private readonly document = inject(DOCUMENT);

  readonly runningExecutions = signal(0);

  private started = false;
  private timer: ReturnType<typeof setInterval> | undefined;
  private readonly onVisibilityChange = (): void => {
    if (this.document.hidden) {
      this.stopTimer();
    } else {
      this.beginPolling();
    }
  };

  constructor() {
    inject(DestroyRef).onDestroy(() => {
      this.stopTimer();
      this.document.removeEventListener('visibilitychange', this.onVisibilityChange);
    });
  }

  start(): void {
    if (this.started) {
      return;
    }
    this.started = true;
    this.document.addEventListener('visibilitychange', this.onVisibilityChange);
    if (!this.document.hidden) {
      this.beginPolling();
    }
  }

  private beginPolling(): void {
    if (this.timer !== undefined) {
      return;
    }
    this.load();
    this.timer = setInterval(() => this.load(), POLL_INTERVAL_MS);
  }

  private stopTimer(): void {
    clearInterval(this.timer);
    this.timer = undefined;
  }

  private load(): void {
    this.api.getStatus().subscribe({
      next: (status) => this.runningExecutions.set(status.runningExecutions),
      error: () => undefined,
    });
  }
}
