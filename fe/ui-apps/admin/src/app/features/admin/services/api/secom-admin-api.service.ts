import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';

import { APP_CONFIG } from '../../../../core/config/app-config.token';

/** Delivery states an outbox entry moves through. Drives the status filter + the row badge. */
export const SECOM_OUTBOX_STATUSES = [
  'PENDING',
  'SENT',
  'ACKED',
  'OPENED',
  'FAILED',
  'CANCELLED',
] as const;

export type SecomOutboxStatus = (typeof SECOM_OUTBOX_STATUSES)[number];

/** The two lifecycle states of an S-124 dataset instance. */
export const S124_DATASET_STATUSES = ['ACTIVE', 'CANCELLED'] as const;

export type S124DatasetStatus = (typeof S124_DATASET_STATUSES)[number];

/** Outbox operations the backend enqueues. */
export const SECOM_OUTBOX_OPERATIONS = ['UPLOAD', 'SUBSCRIPTION_NOTIFICATION'] as const;

export type SecomOutboxOperation = (typeof SECOM_OUTBOX_OPERATIONS)[number];

/**
 * An X.509 credential summary. `created` / `expires` are epoch millis; `ageDays`
 * and `daysToExpiry` are pre-computed server-side against the same clock, so the
 * expiry tiers never disagree with the rendered dates.
 */
export interface SecomCertificate {
  readonly subject: string;
  readonly created?: number;
  readonly ageDays: number;
  readonly expires?: number;
  readonly daysToExpiry: number;
  readonly fingerprint: string;
}

export interface SecomTrustedCertificate {
  readonly alias: string;
  readonly subject: string;
  readonly created?: number;
  readonly expires?: number;
  readonly fingerprint: string;
  /** True for the MCP root anchor, as opposed to an intermediate/peer CA. */
  readonly root: boolean;
}

/**
 * Unattended-renewal schedule. `renewsAt` is null unless renewal is both enabled
 * AND fully configured — an enabled-but-unconfigured node is the "incomplete
 * configuration" case the overview calls out.
 */
export interface SecomRenewal {
  readonly enabled: boolean;
  readonly configured: boolean;
  readonly windowDays: number;
  readonly renewsAt?: number;
  readonly renewsInDays?: number;
}

export interface SecomStatus {
  readonly enabled: boolean;
  readonly serviceCertificate: SecomCertificate;
  readonly rootCertificate: SecomCertificate;
  readonly trustedCertificates: readonly SecomTrustedCertificate[];
  readonly renewal: SecomRenewal;
  /** MCP Identity Registry portal link; empty when the `secomMirPortalUrl` setting is unset. */
  readonly mirPortalUrl: string;
  readonly scopedSeries: readonly string[];
  readonly datasets: number;
  readonly subscriptions: number;
  /** Outbox counts keyed by status name; statuses with no entries are absent. */
  readonly outboxByStatus: Readonly<Record<string, number>>;
}

export interface SecomSecurityReload {
  readonly serviceCertificateSubject: string;
  readonly serviceCertificateFingerprint: string;
  readonly serviceCertificateExpires?: number;
  readonly previousFingerprint: string;
}

export interface SecomRenewedCredential {
  /** `robot` (the renewal credential) or `service` (the node identity). */
  readonly credential: string;
  readonly mrn: string;
  readonly previousFingerprint: string;
  readonly fingerprint: string;
  readonly expires?: number;
  readonly anchored: boolean;
  readonly pendingPickup: boolean;
}

/**
 * Outcome of a forced renewal. A run that renewed nothing 400s instead, so a
 * successful response always carries at least one `renewed` entry — `failures`
 * on top of that is the partial-success case.
 */
export interface SecomRenewalResult {
  readonly renewed: readonly SecomRenewedCredential[];
  readonly failures: readonly string[];
}

export interface SecomSeriesConfig {
  readonly seriesId: string;
  readonly exposeOverSecom: boolean;
  /**
   * Restriction on the model languages the series' S-124 datasets may carry, in
   * the backend's model-language order; empty = no restriction (every model
   * language the message has content in).
   */
  readonly languages: readonly string[];
}

export interface SecomNode {
  readonly id?: number;
  readonly mrn: string;
  readonly lastInteraction?: number;
  readonly created?: number;
}

export interface SecomSubscription {
  readonly uuid: string;
  readonly nodeMrn: string;
  readonly dataProductType: string;
  readonly productVersion: string;
  readonly containerType?: string;
  readonly dataReference?: string;
  readonly wkt?: string;
  readonly periodStart?: number;
  readonly periodEnd?: number;
  readonly created?: number;
}

export interface S124Dataset {
  readonly id?: number;
  /** The SECOM dataReference — the id every dataset action is keyed by. */
  readonly uuid: string;
  readonly mrn: string;
  readonly messageUid: string;
  readonly messageShortId?: string;
  readonly status: string;
  readonly validFrom?: number;
  readonly validTo?: number;
  readonly created?: number;
  readonly updated?: number;
  readonly gmlSize: number;
}

export interface SecomOutboxEntry {
  readonly id: number;
  readonly transactionId: string;
  readonly nodeMrn: string;
  readonly operation: string;
  readonly notificationEvent?: string;
  readonly subscriptionUuid?: string;
  readonly datasetMrn?: string;
  readonly status: string;
  readonly attempts: number;
  readonly nextAttemptAt?: number;
  readonly lastError?: string;
  readonly sentAt?: number;
  readonly ackedAt?: number;
  readonly openedAt?: number;
  readonly nackType?: string;
  readonly created?: number;
}

export interface SecomReconcileResult {
  readonly created: number;
  readonly updated: number;
  readonly cancelled: number;
  readonly unchanged: number;
  /** Eligible messages whose S-124 GML could not be generated; the next sweep tries them again. */
  readonly failed: number;
}

interface CertificateVo {
  subject?: string | null;
  created?: number | null;
  ageDays?: number | null;
  expires?: number | null;
  daysToExpiry?: number | null;
  fingerprint?: string | null;
}

interface TrustedCertificateVo extends CertificateVo {
  alias?: string | null;
  root?: boolean | null;
}

interface RenewalVo {
  enabled?: boolean | null;
  configured?: boolean | null;
  windowDays?: number | null;
  renewsAt?: number | null;
  renewsInDays?: number | null;
}

interface StatusVo {
  enabled?: boolean | null;
  serviceCertificate?: CertificateVo | null;
  rootCertificate?: CertificateVo | null;
  trustedCertificates?: TrustedCertificateVo[] | null;
  renewal?: RenewalVo | null;
  mirPortalUrl?: string | null;
  scopedSeries?: string[] | null;
  datasets?: number | null;
  subscriptions?: number | null;
  outboxByStatus?: Record<string, number> | null;
}

interface SecurityReloadVo {
  serviceCertificateSubject?: string | null;
  serviceCertificateFingerprint?: string | null;
  serviceCertificateExpires?: number | null;
  previousFingerprint?: string | null;
}

interface RenewedCredentialVo {
  credential?: string | null;
  mrn?: string | null;
  previousFingerprint?: string | null;
  fingerprint?: string | null;
  expires?: number | null;
  anchored?: boolean | null;
  pendingPickup?: boolean | null;
}

interface RenewalResultVo {
  renewed?: RenewedCredentialVo[] | null;
  failures?: string[] | null;
}

interface SeriesConfigVo {
  seriesId?: string | null;
  exposeOverSecom?: boolean | null;
  languages?: string[] | null;
}

interface NodeVo {
  id?: number | null;
  mrn?: string | null;
  lastInteraction?: number | null;
  created?: number | null;
}

interface SubscriptionVo {
  uuid?: string | null;
  nodeMrn?: string | null;
  dataProductType?: string | null;
  productVersion?: string | null;
  containerType?: string | null;
  dataReference?: string | null;
  wkt?: string | null;
  periodStart?: number | null;
  periodEnd?: number | null;
  created?: number | null;
}

interface DatasetVo {
  id?: number | null;
  uuid?: string | null;
  mrn?: string | null;
  messageUid?: string | null;
  messageShortId?: string | null;
  status?: string | null;
  validFrom?: number | null;
  validTo?: number | null;
  created?: number | null;
  updated?: number | null;
  gmlSize?: number | null;
}

interface OutboxVo {
  id?: number | null;
  transactionId?: string | null;
  nodeMrn?: string | null;
  operation?: string | null;
  notificationEvent?: string | null;
  subscriptionUuid?: string | null;
  datasetMrn?: string | null;
  status?: string | null;
  attempts?: number | null;
  nextAttemptAt?: number | null;
  lastError?: string | null;
  sentAt?: number | null;
  ackedAt?: number | null;
  openedAt?: number | null;
  nackType?: string | null;
  created?: number | null;
}

interface ReconcileResultVo {
  created?: number | null;
  updated?: number | null;
  cancelled?: number | null;
  unchanged?: number | null;
  failed?: number | null;
}

/** `null` → `undefined`, so an absent date is one falsy value rather than two. */
const opt = <T>(value: T | null | undefined): T | undefined => value ?? undefined;

const certificateFromVo = (vo: CertificateVo | null | undefined): SecomCertificate => ({
  subject: vo?.subject ?? '',
  created: opt(vo?.created),
  ageDays: vo?.ageDays ?? 0,
  expires: opt(vo?.expires),
  daysToExpiry: vo?.daysToExpiry ?? 0,
  fingerprint: vo?.fingerprint ?? '',
});

const trustedFromVo = (vo: TrustedCertificateVo): SecomTrustedCertificate => ({
  alias: vo.alias ?? '',
  subject: vo.subject ?? '',
  created: opt(vo.created),
  expires: opt(vo.expires),
  fingerprint: vo.fingerprint ?? '',
  root: vo.root ?? false,
});

const renewalFromVo = (vo: RenewalVo | null | undefined): SecomRenewal => ({
  enabled: vo?.enabled ?? false,
  configured: vo?.configured ?? false,
  windowDays: vo?.windowDays ?? 0,
  renewsAt: opt(vo?.renewsAt),
  renewsInDays: opt(vo?.renewsInDays),
});

const statusFromVo = (vo: StatusVo): SecomStatus => ({
  enabled: vo.enabled ?? false,
  serviceCertificate: certificateFromVo(vo.serviceCertificate),
  rootCertificate: certificateFromVo(vo.rootCertificate),
  trustedCertificates: (vo.trustedCertificates ?? []).map(trustedFromVo),
  renewal: renewalFromVo(vo.renewal),
  mirPortalUrl: vo.mirPortalUrl ?? '',
  scopedSeries: vo.scopedSeries ?? [],
  datasets: vo.datasets ?? 0,
  subscriptions: vo.subscriptions ?? 0,
  outboxByStatus: vo.outboxByStatus ?? {},
});

const securityReloadFromVo = (vo: SecurityReloadVo): SecomSecurityReload => ({
  serviceCertificateSubject: vo.serviceCertificateSubject ?? '',
  serviceCertificateFingerprint: vo.serviceCertificateFingerprint ?? '',
  serviceCertificateExpires: opt(vo.serviceCertificateExpires),
  previousFingerprint: vo.previousFingerprint ?? '',
});

const renewalResultFromVo = (vo: RenewalResultVo): SecomRenewalResult => ({
  renewed: (vo.renewed ?? []).map((credential) => ({
    credential: credential.credential ?? '',
    mrn: credential.mrn ?? '',
    previousFingerprint: credential.previousFingerprint ?? '',
    fingerprint: credential.fingerprint ?? '',
    expires: opt(credential.expires),
    anchored: credential.anchored ?? false,
    pendingPickup: credential.pendingPickup ?? false,
  })),
  failures: vo.failures ?? [],
});

const seriesConfigFromVo = (vo: SeriesConfigVo): SecomSeriesConfig => ({
  seriesId: vo.seriesId ?? '',
  exposeOverSecom: vo.exposeOverSecom ?? false,
  languages: vo.languages ?? [],
});

const nodeFromVo = (vo: NodeVo): SecomNode => ({
  id: opt(vo.id),
  mrn: vo.mrn ?? '',
  lastInteraction: opt(vo.lastInteraction),
  created: opt(vo.created),
});

const subscriptionFromVo = (vo: SubscriptionVo): SecomSubscription => ({
  uuid: vo.uuid ?? '',
  nodeMrn: vo.nodeMrn ?? '',
  dataProductType: vo.dataProductType ?? '',
  productVersion: vo.productVersion ?? '',
  containerType: opt(vo.containerType),
  dataReference: opt(vo.dataReference),
  wkt: opt(vo.wkt),
  periodStart: opt(vo.periodStart),
  periodEnd: opt(vo.periodEnd),
  created: opt(vo.created),
});

const datasetFromVo = (vo: DatasetVo): S124Dataset => ({
  id: opt(vo.id),
  uuid: vo.uuid ?? '',
  mrn: vo.mrn ?? '',
  messageUid: vo.messageUid ?? '',
  messageShortId: opt(vo.messageShortId),
  status: vo.status ?? '',
  validFrom: opt(vo.validFrom),
  validTo: opt(vo.validTo),
  created: opt(vo.created),
  updated: opt(vo.updated),
  gmlSize: vo.gmlSize ?? 0,
});

const outboxFromVo = (vo: OutboxVo): SecomOutboxEntry => ({
  id: vo.id ?? 0,
  transactionId: vo.transactionId ?? '',
  nodeMrn: vo.nodeMrn ?? '',
  operation: vo.operation ?? '',
  notificationEvent: opt(vo.notificationEvent),
  subscriptionUuid: opt(vo.subscriptionUuid),
  datasetMrn: opt(vo.datasetMrn),
  status: vo.status ?? '',
  attempts: vo.attempts ?? 0,
  nextAttemptAt: opt(vo.nextAttemptAt),
  lastError: opt(vo.lastError),
  sentAt: opt(vo.sentAt),
  ackedAt: opt(vo.ackedAt),
  openedAt: opt(vo.openedAt),
  nackType: opt(vo.nackType),
  created: opt(vo.created),
});

const reconcileFromVo = (vo: ReconcileResultVo): SecomReconcileResult => ({
  created: vo.created ?? 0,
  updated: vo.updated ?? 0,
  cancelled: vo.cancelled ?? 0,
  unchanged: vo.unchanged ?? 0,
  failed: vo.failed ?? 0,
});

/**
 * Sysadmin API for operating the SECOM/S-124 integration: node status, the
 * per-series exposure config, subscriptions and peer nodes, dataset instances,
 * and the delivery outbox.
 *
 * The whole `/secom` controller is registered only when the backend runs with
 * `niord.secom.enabled=true`; on a node without it EVERY endpoint here answers
 * **404**. That is the signal the facade reads to tell "subsystem not deployed"
 * apart from a genuine failure — so callers must not collapse a 404 into a plain
 * error. Nothing is localized, so no request carries `?lang=`.
 */
@Injectable({ providedIn: 'root' })
export class SecomAdminApiService {
  private readonly http = inject(HttpClient);
  private readonly apiBaseUrl = inject(APP_CONFIG).apiBaseUrl;

  getStatus(): Observable<SecomStatus> {
    return this.http.get<StatusVo>(`${this.apiBaseUrl}/secom/status`).pipe(map(statusFromVo));
  }

  /**
   * Re-reads the configured keystore/truststore and swaps them in without a
   * restart. A failed reload 400s and leaves the current material active.
   */
  reloadSecurity(): Observable<SecomSecurityReload> {
    return this.http
      .post<SecurityReloadVo>(`${this.apiBaseUrl}/secom/security/reload`, null)
      .pipe(map(securityReloadFromVo));
  }

  /**
   * Forces an immediate renewal against the MCP Identity Registry, ignoring the
   * expiry window. A run that renews nothing 400s; a missing renewal
   * configuration 409s.
   */
  renewCertificates(): Observable<SecomRenewalResult> {
    return this.http
      .post<RenewalResultVo>(`${this.apiBaseUrl}/secom/security/renew`, null)
      .pipe(map(renewalResultFromVo));
  }

  /** The stored exposure rows — one per series that has ever been configured, not per series. */
  getSeriesConfigs(): Observable<SecomSeriesConfig[]> {
    return this.http
      .get<SeriesConfigVo[]>(`${this.apiBaseUrl}/secom/series-config`)
      .pipe(map((rows) => rows.map(seriesConfigFromVo)));
  }

  /**
   * Upsert: the path id and the body id are the same series id by construction.
   * The PUT replaces the whole row, so the caller always sends both the exposure
   * flag and the language restriction; the backend stores the languages in its
   * own canonical order and echoes them back.
   */
  updateSeriesConfig(
    seriesId: string,
    exposeOverSecom: boolean,
    languages: readonly string[],
  ): Observable<SecomSeriesConfig> {
    return this.http
      .put<SeriesConfigVo>(this.seriesConfigUrl(seriesId), {
        seriesId,
        exposeOverSecom,
        languages: [...languages],
      })
      .pipe(map(seriesConfigFromVo));
  }

  getNodes(): Observable<SecomNode[]> {
    return this.http
      .get<NodeVo[]>(`${this.apiBaseUrl}/secom/nodes`)
      .pipe(map((rows) => rows.map(nodeFromVo)));
  }

  getSubscriptions(): Observable<SecomSubscription[]> {
    return this.http
      .get<SubscriptionVo[]>(`${this.apiBaseUrl}/secom/subscriptions`)
      .pipe(map((rows) => rows.map(subscriptionFromVo)));
  }

  /** Removes the subscription AND notifies the subscriber (backend side effect). */
  deleteSubscription(uuid: string): Observable<SecomSubscription> {
    return this.http
      .delete<SubscriptionVo>(`${this.apiBaseUrl}/secom/subscriptions/${encodeURIComponent(uuid)}`)
      .pipe(map(subscriptionFromVo));
  }

  getDatasets(): Observable<S124Dataset[]> {
    return this.http
      .get<DatasetVo[]>(`${this.apiBaseUrl}/secom/datasets`)
      .pipe(map((rows) => rows.map(datasetFromVo)));
  }

  /**
   * The stored GML of one dataset. Served as `application/gml+xml`, so it is
   * fetched as TEXT — a JSON parse would reject it.
   */
  getDatasetGml(uuid: string): Observable<string> {
    return this.http.get(`${this.apiBaseUrl}/secom/datasets/${encodeURIComponent(uuid)}/gml`, {
      responseType: 'text',
    });
  }

  /** Re-enqueues the dataset to every matching subscription; resolves to the delivery count. */
  republishDataset(uuid: string): Observable<number> {
    return this.http.post<number>(
      `${this.apiBaseUrl}/secom/datasets/${encodeURIComponent(uuid)}/republish`,
      null,
    );
  }

  /** Diffs eligible messages against the dataset store; doubles as the first-enable backfill. */
  reconcile(): Observable<SecomReconcileResult> {
    return this.http
      .post<ReconcileResultVo>(`${this.apiBaseUrl}/secom/datasets/reconcile`, null)
      .pipe(map(reconcileFromVo));
  }

  /** Recent outbox entries. An empty status means "all" and omits the param entirely. */
  getOutbox(status: '' | SecomOutboxStatus = ''): Observable<SecomOutboxEntry[]> {
    const params = status ? new HttpParams().set('status', status) : undefined;
    return this.http
      .get<OutboxVo[]>(`${this.apiBaseUrl}/secom/outbox`, params ? { params } : {})
      .pipe(map((rows) => rows.map(outboxFromVo)));
  }

  /** Resets an entry for immediate dispatch (PENDING and FAILED entries only). */
  retryOutboxEntry(id: number): Observable<number> {
    return this.http.post<number>(`${this.apiBaseUrl}/secom/outbox/${id}/retry`, null);
  }

  /** Cancels a pending delivery. */
  cancelOutboxEntry(id: number): Observable<number> {
    return this.http.delete<number>(`${this.apiBaseUrl}/secom/outbox/${id}`);
  }

  private seriesConfigUrl(seriesId: string): string {
    return `${this.apiBaseUrl}/secom/series-config/${encodeURIComponent(seriesId)}`;
  }
}
