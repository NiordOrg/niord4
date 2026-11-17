import { Injectable, inject } from '@angular/core';
import { NIORD_INFO_CONFIG } from '../../niord-info.config';
import { Message } from '../models/message.model';
import { normalizeAttachmentPath, normalizeRepoUrlsInHtml, repoHosts } from './repo-url';

/**
 * The wire shape of a message. It differs from the model in one field: the API declares
 * `publishDateFrom` a string and sends epoch milliseconds. Everything else arrives in the
 * model's own shape, so there is one pair here rather than one per interface.
 */
interface MessageVo extends Omit<Message, 'publishDateFrom'> {
  readonly publishDateFrom?: string | number;
}

/**
 * The boundary where a message stops being a response and becomes the model: repository links
 * point at this origin, and the one field the API declares loosely is resolved.
 */
@Injectable({ providedIn: 'root' })
export class MessageService {
  private readonly repoHosts = repoHosts(inject(NIORD_INFO_CONFIG).backendHosts);

  /** A message list as it came off the wire. */
  parseList(raw: unknown): Message[] {
    return (raw as MessageVo[]).map((msg) => this.normalizeMessage(msg));
  }

  /**
   * One message. The backend answers an id it does not know with a 404, which the resource
   * reports as an error; a body that is empty all the same is read as no message rather than as
   * a malformed one.
   */
  parseOne(raw: unknown): Message | undefined {
    return raw ? this.normalizeMessage(raw as MessageVo) : undefined;
  }

  /**
   * Points every repository link of this deployment at this origin. `externalize=false` already
   * returns the stored form, but an attachment comes back as a bare repository path, and stored
   * HTML can hold an absolute URL on the backend's public host, so both are reduced here — once,
   * at the boundary, rather than in each of the components that render them. See `repo-url.ts`.
   */
  private normalizeMessage(msg: MessageVo): Message {
    return {
      ...msg,
      publishDateFrom: toEpochMillis(msg.publishDateFrom),
      attachments: msg.attachments?.map((attachment) => ({
        ...attachment,
        path: normalizeAttachmentPath(attachment.path, this.repoHosts) ?? attachment.path,
      })),
      parts: msg.parts?.map((part) => ({
        ...part,
        descs: part.descs?.map((desc) => ({
          ...desc,
          details: normalizeRepoUrlsInHtml(desc.details, this.repoHosts),
        })),
      })),
      descs: msg.descs?.map((desc) => ({
        ...desc,
        publication: normalizeRepoUrlsInHtml(desc.publication, this.repoHosts),
      })),
    };
  }
}

/**
 * The publication instant as a number. The API declares the field a string and the backend
 * sends epoch milliseconds; a string of digits is that same number, and a date the browser can
 * read is accepted too. Anything else is dropped rather than rendered as an invalid date.
 */
function toEpochMillis(value: string | number | undefined): number | undefined {
  if (value === undefined || value === '') return undefined;
  const millis =
    typeof value === 'number' ? value : /^\d+$/.test(value) ? Number(value) : Date.parse(value);
  return Number.isFinite(millis) ? millis : undefined;
}
