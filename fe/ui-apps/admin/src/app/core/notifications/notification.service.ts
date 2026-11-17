import { Injectable, inject, signal } from '@angular/core';
import { TranslocoService } from '@jsverse/transloco';

import type {
  NotificationOptions,
  NotificationSeverity,
  ToastMessage,
} from './notification-options.model';

const SUCCESS_TTL_MS = 3000;
const INFO_TTL_MS = 3000;
const WARN_TTL_MS = 5000;
const ERROR_TTL_MS = 5000;

/**
 * App-wide toast facade. Translates a Transloco key + severity into a live
 * {@link ToastMessage} and pushes it into a signal-backed store that the shared
 * `<app-toast>` renders (CDK overlay). The public API is `success/info/warn/error`
 * and `notify(opts)`.
 */
@Injectable({ providedIn: 'root' })
export class NotificationService {
  private readonly transloco = inject(TranslocoService);

  private readonly store = signal<readonly ToastMessage[]>([]);
  /** The live toasts, oldest first. Read by `<app-toast>`. */
  readonly messages = this.store.asReadonly();

  private seq = 0;

  success(key: string, params?: Record<string, unknown>): void {
    this.emit('success', key, params, SUCCESS_TTL_MS);
  }

  info(key: string, params?: Record<string, unknown>): void {
    this.emit('info', key, params, INFO_TTL_MS);
  }

  warn(key: string, params?: Record<string, unknown>): void {
    this.emit('warn', key, params, WARN_TTL_MS);
  }

  error(key: string, params?: Record<string, unknown>): void {
    this.emit('error', key, params, ERROR_TTL_MS);
  }

  notify(opts: NotificationOptions): void {
    const summary = this.transloco.translate(opts.summaryKey, opts.summaryParams);
    const detail =
      opts.detailKey === undefined
        ? undefined
        : this.transloco.translate(opts.detailKey, opts.detailParams);
    const life = opts.life ?? defaultTtl(opts.severity);
    this.push({ severity: opts.severity, summary, detail, life });
  }

  /** Remove a toast (the close button + the auto-dismiss timer both route here). */
  dismiss(id: number): void {
    this.store.update((list) => list.filter((message) => message.id !== id));
  }

  private emit(
    severity: NotificationSeverity,
    key: string,
    params: Record<string, unknown> | undefined,
    life: number,
  ): void {
    const summary = this.transloco.translate(key, params);
    this.push({ severity, summary, life });
  }

  private push(message: Omit<ToastMessage, 'id'>): void {
    const id = ++this.seq;
    this.store.update((list) => [...list, { id, ...message }]);
    // life 0 keeps the toast sticky until the user closes it.
    if (message.life > 0) {
      setTimeout(() => this.dismiss(id), message.life);
    }
  }
}

function defaultTtl(severity: NotificationSeverity): number {
  switch (severity) {
    case 'success':
      return SUCCESS_TTL_MS;
    case 'info':
      return INFO_TTL_MS;
    case 'warn':
      return WARN_TTL_MS;
    case 'error':
      return ERROR_TTL_MS;
  }
}
