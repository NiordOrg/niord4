import { Injectable, inject } from '@angular/core';
import { NIORD_INFO_CONFIG } from '../../niord-info.config';
import { DateInterval, Message, MessagePart } from '../models/message.model';
import { normalizeAttachmentPath, normalizeRepoUrlsInHtml, repoHosts } from './repo-url';

/**
 * The wire shape of a message. It differs from the model in its dates: the API declares
 * `publishDateFrom` and the ends of a part's event intervals strings, and sends epoch
 * milliseconds. Everything else arrives in the model's own shape, so the pairs are kept here
 * rather than beside each interface.
 */
interface MessageVo extends Omit<Message, 'publishDateFrom' | 'parts'> {
  readonly publishDateFrom?: string | number;
  readonly parts?: readonly MessagePartVo[];
}

interface MessagePartVo extends Omit<MessagePart, 'eventDates'> {
  readonly eventDates?: readonly DateIntervalVo[];
}

interface DateIntervalVo extends Omit<DateInterval, 'fromDate' | 'toDate'> {
  readonly fromDate?: string | number;
  readonly toDate?: string | number;
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
        eventDates: part.eventDates
          ?.map((interval) => ({
            ...interval,
            fromDate: toEpochMillis(interval.fromDate),
            toDate: toEpochMillis(interval.toDate),
          }))
          .filter((interval) => interval.fromDate !== undefined || interval.toDate !== undefined),
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
 * An instant as a number. The API declares these fields strings and the backend
 * sends epoch milliseconds; a string of digits is that same number, and a date the browser can
 * read is accepted too. Anything else is dropped rather than rendered as an invalid date.
 */
function toEpochMillis(value: string | number | undefined): number | undefined {
  if (value === undefined || value === '') return undefined;
  const millis =
    typeof value === 'number' ? value : /^\d+$/.test(value) ? Number(value) : Date.parse(value);
  return Number.isFinite(millis) ? millis : undefined;
}
