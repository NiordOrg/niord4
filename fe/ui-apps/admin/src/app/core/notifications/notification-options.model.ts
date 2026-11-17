export type NotificationSeverity = 'success' | 'info' | 'warn' | 'error';

export interface NotificationOptions {
  readonly severity: NotificationSeverity;
  readonly summaryKey: string;
  readonly summaryParams?: Record<string, unknown>;
  readonly detailKey?: string;
  readonly detailParams?: Record<string, unknown>;
  /** Override severity-default TTL (ms). 0 keeps the toast sticky until dismissed. */
  readonly life?: number;
  /**
   * Optional grouping key. The app renders a single global `<app-toast>`, so it
   * is not used for routing today.
   */
  readonly key?: string;
}

/** A live toast in the {@link NotificationService} store, rendered by `<app-toast>`. */
export interface ToastMessage {
  /** Monotonic id — the render key + the dismiss handle. */
  readonly id: number;
  readonly severity: NotificationSeverity;
  /** Already-translated headline. */
  readonly summary: string;
  /** Already-translated secondary line (optional). */
  readonly detail?: string;
  /** Auto-dismiss delay in ms; 0 keeps the toast until manually closed. */
  readonly life: number;
}
