import { inject, Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { encodeRepoPath } from '@niord/html/repo-path';

import { APP_CONFIG } from '../../../../core/config/app-config.token';
import { LangHttpHelper } from '../../../../shared/api/lang-http.helper';
import {
  MainType,
  Message,
  MessageDescription,
  MessageGeometryFeatureCollection,
  MessageStatus,
  ReferenceType,
} from '../../models/message.model';
import { MessageComment } from '../../models/message-comment.model';
import { MessageIdSearchResult } from '../../models/message-id-search-result.model';
import { MessageSearchResult } from '../../models/message-search-result.model';
import type { PublicationRef } from '../../models/messages-filter-params.model';
import { LanguageService } from '../../../../core/main-shell/services/language.service';

/**
 * The message-publication fragment returned by `extract-message-publication` (backend
 * `MessagePublicationVo`): the picked publication plus the resolved template `parameters`
 * and `link`. Used to prefill the Insert-Publications dialog in EDIT mode.
 */
export interface MessagePublication {
  readonly publication?: PublicationRef;
  readonly parameters?: string;
  readonly link?: string;
}

@Injectable({ providedIn: 'root' })
export class MessagesApiService {
  private readonly http = inject(HttpClient);
  private readonly languageService = inject(LanguageService);
  private readonly lang = inject(LangHttpHelper);
  private readonly apiBaseUrl = inject(APP_CONFIG).apiBaseUrl;

  /**
   * `params` is expected to contain only filter fields. `lang`, `page`, `maxSize`,
   * `viewMode`, and `includeNoPos` are owned by this method and will overwrite any
   * matching keys in `params` via HttpParams.set().
   */
  searchMessages(
    params: string,
    page: number,
    maxSize: number,
    options?: { language?: string; viewModeMap?: boolean; includeNoPos?: boolean },
  ): Observable<MessageSearchResult> {
    const lang = options?.language ?? this.languageService.selectedLanguage();
    let httpParams = new HttpParams({ fromString: params })
      .set('lang', lang)
      .set('page', String(page))
      .set('maxSize', String(maxSize));
    if (options?.viewModeMap) {
      httpParams = httpParams.set('viewMode', 'map');
    }
    if (options?.includeNoPos) {
      httpParams = httpParams.set('includeNoPos', 'true');
    }
    return this.http.get<MessageSearchResult>(`${this.apiBaseUrl}/messages/search`, {
      params: httpParams,
    });
  }

  searchMessageIds(
    query: string,
    options?: { includeDeleted?: boolean; language?: string },
  ): Observable<MessageIdSearchResult[]> {
    const lang = options?.language ?? this.languageService.selectedLanguage();
    // Always send `deleted` (defaulting to 'false'). Omitting it makes the
    // backend include deleted messages, which the reference filter never wants.
    const params = new HttpParams()
      .set('txt', query)
      .set('deleted', options?.includeDeleted ? 'true' : 'false')
      .set('lang', lang);

    return this.http.get<MessageIdSearchResult[]>(
      `${this.apiBaseUrl}/messages/search-message-ids`,
      { params },
    );
  }

  // ── Editor lifecycle ───────────────────────────────────────────────────────
  // All call the backend REST API — thin adapter methods only.
  //
  // Promulgations are NOT normalized: the backend stamps a Jackson `@class` polymorphic
  // discriminator onto each one, and it MUST round-trip back on save/adjust (the body's
  // `List<BaseMessagePromulgationVo>` is abstract — without `@class` the backend cannot
  // deserialize it and returns 500). We carry it opaquely; the UI discriminates on `type.serviceId`.

  /**
   * Load the editable (system) form of a message. An unknown id answers 200 with a `null` body,
   * not an HTTP error — callers must treat a null emission as a failed load.
   */
  editableDetails(messageId: string, options?: { language?: string }): Observable<Message | null> {
    const params = this.lang.langParams(options?.language);
    return this.http.get<Message | null>(
      `${this.apiBaseUrl}/messages/editable-message/${encodeURIComponent(messageId)}`,
      { params },
    );
  }

  /**
   * The full message in ALL languages —
   * `GET /messages/message/{id}` with NO `lang` filter, so every language's descs come back. Accepts
   * a UUID or a shortId (backend resolves either). Used by the comparison dialog to render each side
   * at any content language. Distinct from `editableDetails` (the system/editor form).
   */
  getMessageAllLanguages(id: string): Observable<Message> {
    return this.http.get<Message>(`${this.apiBaseUrl}/messages/message/${encodeURIComponent(id)}`);
  }

  /** A blank editable message seeded for the given main type (optionally pre-populated). */
  newMessageTemplate(
    mainType: MainType,
    options?: { populate?: boolean },
  ): Observable<Message | null> {
    let params = new HttpParams().set('mainType', mainType);
    if (options?.populate) {
      params = params.set('populate', 'true');
    }
    return this.http.get<Message | null>(`${this.apiBaseUrl}/messages/new-message-template`, {
      params,
    });
  }

  /**
   * A copy of an existing message as a new editable draft, optionally as a reference. Like
   * {@link editableDetails}, an unknown source id answers 200 with a `null` body.
   */
  copyMessageTemplate(
    messageId: string,
    referenceType?: ReferenceType,
    options?: { language?: string },
  ): Observable<Message | null> {
    let params = this.lang.langParams(options?.language);
    if (referenceType) {
      params = params.set('referenceType', referenceType);
    }
    return this.http.get<Message | null>(
      `${this.apiBaseUrl}/messages/copy-message-template/${encodeURIComponent(messageId)}`,
      { params },
    );
  }

  /**
   * Save a message. Dispatches POST for a never-saved message (`!created`) and PUT for an
   * existing one — the first save is what assigns the permanent id and moves the temporary
   * `editRepoPath` to the permanent repository (server-side).
   */
  saveMessage(message: Message): Observable<Message> {
    return message.created
      ? this.http.put<Message>(
          `${this.apiBaseUrl}/messages/message/${encodeURIComponent(message.id)}`,
          message,
        )
      : this.http.post<Message>(`${this.apiBaseUrl}/messages/message`, message);
  }

  /** Re-derive computed fields (areas/charts/title/source) for the in-flight editable message. */
  adjustEditableMessage(
    message: Message,
    options?: { inclPromulgations?: boolean },
  ): Observable<Message> {
    let params = new HttpParams();
    if (options?.inclPromulgations) {
      params = params.set('inclPromulgations', 'true');
    }
    return this.http.post<Message>(`${this.apiBaseUrl}/messages/adjust-editable-message`, message, {
      params,
    });
  }

  /** The attachment upload repository path for a message. */
  attachmentUploadRepoPath(message: Message): string {
    return `${this.apiBaseUrl}/messages/attachments/${message.editRepoPath}/${message.revision}`;
  }

  // ── Status workflow ────────────────────────────────────────────────────────

  /**
   * Transition a message to `status` (verify / publish / cancel / delete). The endpoint takes the
   * status as the RAW request body (an unquoted string) with an explicit JSON content type; a query
   * param or a JSON-quoted body is rejected by the backend (415 / 500).
   */
  updateMessageStatus(
    messageId: string,
    status: MessageStatus,
    options?: { cancelReferencedMessages?: boolean },
  ): Observable<Message> {
    let params = new HttpParams();
    if (options?.cancelReferencedMessages) {
      params = params.set('cancelReferencedMessages', 'true');
    }
    return this.http.put<Message>(
      `${this.apiBaseUrl}/messages/message/${encodeURIComponent(messageId)}/status`,
      status,
      { params, headers: { 'Content-Type': 'application/json' } },
    );
  }

  /**
   * Bulk status transition. The wire field is named `messageId` but carries the
   * UUID `Message.id` (the backend resolves it via `findByUid`) — never `shortId`.
   */
  updateStatuses(
    updates: readonly { messageId: string; status: MessageStatus }[],
  ): Observable<void> {
    return this.http.put<void>(`${this.apiBaseUrl}/messages/update-statuses`, updates);
  }

  /** Messages that reference this one with any of `referenceTypes`, in the given status. */
  referencedMessages(
    messageId: string,
    referenceTypes: readonly ReferenceType[],
    status: MessageStatus = 'PUBLISHED',
    options?: { language?: string },
  ): Observable<Message[]> {
    let params = this.lang.langParams(options?.language).set('status', status);
    for (const type of referenceTypes) {
      params = params.append('referenceType', type);
    }
    return this.http.get<Message[]>(
      `${this.apiBaseUrl}/messages/referenced-messages/${encodeURIComponent(messageId)}`,
      { params },
    );
  }

  // ── Comments ───────────────────────────────────────────────────────────────

  createComment(
    messageId: string,
    comment: MessageComment,
    options?: { language?: string },
  ): Observable<MessageComment> {
    const params = this.lang.langParams(options?.language);
    return this.http.post<MessageComment>(
      `${this.apiBaseUrl}/messages/message/${encodeURIComponent(messageId)}/comment`,
      comment,
      { params },
    );
  }

  updateComment(
    messageId: string,
    comment: MessageComment,
    options?: { language?: string },
  ): Observable<MessageComment> {
    const params = this.lang.langParams(options?.language);
    return this.http.put<MessageComment>(
      `${this.apiBaseUrl}/messages/message/${encodeURIComponent(messageId)}/comment/${encodeURIComponent(comment.id ?? '')}`,
      comment,
      { params },
    );
  }

  acknowledgeComment(messageId: string, commentId: string): Observable<MessageComment> {
    return this.http.put<MessageComment>(
      `${this.apiBaseUrl}/messages/message/${encodeURIComponent(messageId)}/comment/${encodeURIComponent(commentId)}/ack`,
      null,
    );
  }

  // ── Editor content dialogs & repository ────────────────────────────────────
  // All hit the backend REST API — thin adapter methods only.

  /**
   * Render a geometry as HTML for the Insert-Locations dialog. The body is the FeatureCollection
   * of the *selected* features (only the selected features, not the whole message —
   * the backend param is a `FeatureCollectionVo`). The endpoint answers `text/html`, so the
   * result is a raw HTML string (`responseType: 'text'`), never JSON. `template`
   * (numbered-list/bullet-list/table/from-to-table/text) and `format` (dec/dec-2/dec-1/sec/navtex)
   * are the dialog's value sets, sent verbatim.
   */
  formatMessageGeometry(
    geometry: MessageGeometryFeatureCollection,
    options: { lang: string; template: string; format: string },
  ): Observable<string> {
    const params = new HttpParams()
      .set('lang', options.lang)
      .set('template', options.template)
      .set('format', options.format);
    return this.http.post(`${this.apiBaseUrl}/messages/format-message-geometry`, geometry, {
      params,
      responseType: 'text',
    });
  }

  /**
   * Extract the message publication for `publicationId` — the EDIT-mode prefill for the
   * Insert-Publications dialog (the resolved `parameters` + `link` of the publication already
   * embedded in the message). The endpoint answers 200 with a `null` body when the publication
   * id is unknown, so callers must treat a null emission as "nothing to prefill". `lang` defaults
   * to the selected UI language.
   */
  extractMessagePublication(
    message: Message,
    publicationId: string,
    lang?: string,
  ): Observable<MessagePublication | null> {
    const params = this.lang.langParams(lang).set('publicationId', publicationId);
    return this.http.post<MessagePublication | null>(
      `${this.apiBaseUrl}/messages/extract-message-publication`,
      this.publicationTemplate(message),
      { params },
    );
  }

  /**
   * Re-render the message's publications after picking a publication (+ optional `parameters` /
   * `link`). The backend returns the message with freshly rendered per-language `publication` /
   * `internalPublication` HTML. Omitting `lang` updates ALL languages (the `lang` param is sent
   * only when set); `parameters` / `link` are likewise sent only when present. The
   * endpoint always returns a message — it echoes the posted one unchanged when the publication
   * id is unknown — so this never nulls.
   */
  updateMessagePublications(
    message: Message,
    options: { publicationId: string; parameters?: string; link?: string; lang?: string },
  ): Observable<Message> {
    let params = new HttpParams().set('publicationId', options.publicationId);
    if (options.parameters) {
      params = params.set('parameters', options.parameters);
    }
    if (options.link) {
      params = params.set('link', options.link);
    }
    if (options.lang) {
      params = params.set('lang', options.lang);
    }
    return this.http.post<Message>(
      `${this.apiBaseUrl}/messages/update-message-publications`,
      this.publicationTemplate(message),
      { params },
    );
  }

  /**
   * The pruned message body the two publication endpoints accept — ONLY
   * the per-language publication HTML (`{ descs: [{ lang, publication, internalPublication }] }`).
   * Posting the full working message 500s the backend — its `MessageVo` can't round-trip every
   * editor field (dates, parts, geometry, …) — and both endpoints read/write nothing but these desc
   * fields, echoing the rest back unchanged.
   *
   * One desc PER configured model language, seeded blank when the working copy lacks it (existing
   * publication HTML is preserved). Seeding matters because a just-created blank message has NO
   * descs, so an "update all languages" would otherwise post an empty list and the backend would
   * render nothing back — the picked publication would never reach the field.
   */
  private publicationTemplate(message: Message): { descs: MessageDescription[] } {
    const byLang = new Map((message.descs ?? []).map((desc) => [desc.lang, desc]));
    return {
      descs: this.languageService.modelLanguages.map((option) => {
        const desc = byLang.get(option.value);
        return {
          lang: option.value,
          publication: desc?.publication,
          internalPublication: desc?.internalPublication,
        };
      }),
    };
  }

  /**
   * Physically delete a repository file (a just-uploaded attachment or a thumbnail on the current
   * revision). `path` is the backend-encoded repo path exactly as it arrives on the model
   * (`attachment.path` / `thumbnailPath`); it is encoded segment by segment by `encodeRepoPath`,
   * which keeps the slashes the `/repo/file/{file:.+}` route matches and leaves what the backend
   * already escaped as it is — `encodeURIComponent` on the whole path would do neither. The
   * endpoint answers `text/plain`, so JSON parsing is disabled.
   */
  deleteRepoFile(path: string): Observable<string> {
    return this.http.delete(`${this.apiBaseUrl}/repo/file/${encodeRepoPath(path)}`, {
      responseType: 'text',
    });
  }

  /**
   * Upload a custom thumbnail image FILE for the message. POSTs `multipart/form-data`
   * (a single part named `file`) to
   * `/message-map-image/{editRepoPath}/{revision}`; the backend generates the thumbnail and
   * answers `text/plain` with the new repository thumbnail path. The `editRepoPath`/`revision`
   * pair is spliced in un-encoded (the `{folder:.+}` route keeps the embedded slashes), mirroring
   * {@link attachmentUploadRepoPath}. The base-64 data-URL snapshot variant
   * ({@link changeMessageMapImage}, a PUT of `data:image/png;base64,…`) is a separate endpoint and
   * out of scope here.
   */
  updateMessageMapImage(message: Message, file: File): Observable<string> {
    const form = new FormData();
    form.append('file', file);
    return this.http.post(
      `${this.apiBaseUrl}/message-map-image/${message.editRepoPath}/${message.revision}`,
      form,
      { responseType: 'text' },
    );
  }

  /**
   * Replace the message thumbnail with a client-captured map snapshot (the "Select thumbnail from
   * map" flow). PUTs the base-64 PNG **data-URL string**
   * (`data:image/png;base64,…`) to `/message-map-image/{editRepoPath}/{revision}`; the backend decodes
   * and stores it and answers `text/plain` with the new repository thumbnail path. This is the
   * snapshot counterpart to {@link updateMessageMapImage}'s multipart file upload. The body is sent as
   * `application/json` — the endpoint's `@Consumes` — so the Content-Type is set explicitly (a string
   * body would otherwise default to `text/plain` and 415). The `editRepoPath`/`revision` pair is
   * spliced in un-encoded (the `{folder:.+}` route keeps the embedded slashes).
   */
  changeMessageMapImage(message: Message, dataUrl: string): Observable<string> {
    return this.http.put(
      `${this.apiBaseUrl}/message-map-image/${message.editRepoPath}/${message.revision}`,
      dataUrl,
      { responseType: 'text', headers: { 'Content-Type': 'application/json' } },
    );
  }
}
