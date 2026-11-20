import { inject, Injectable } from '@angular/core';

import { Desc, Message, MessageStatus, PROVISIONAL_TYPE_SUFFIXES } from '../models/message.model';
import { LanguageService } from './language.service';

/** The status chip beside an identifier: which of the two states it names, and the key of its word. */
export interface MessageStatusChip {
  readonly kind: MessageStatus;
  readonly key: string;
}

/**
 * What the id badge draws: the identifier, an optional status chip, an optional marker. The words
 * are keys, translated where the badge puts them in the page, so they follow a language switch
 * and a bundle that arrives after it.
 */
export interface MessageBadge {
  /** The short id, when the message has one. */
  readonly shortId?: string;
  /** For a message with no short id, which is named by what it is: its type's word. */
  readonly typeKey?: string;
  /** For a message with no short id: its main type's word, after the type's. */
  readonly mainTypeKey?: string;
  /** Present only for an expired notice or a cancellation — the two a reader has to notice. */
  readonly status?: MessageStatusChip;
  /** `(T)` for a temporary notice, `(P)` for a preliminary one. */
  readonly suffix?: string;
}

/**
 * What a message is called in the language the reader is in. Its description is chosen by the
 * active language, which is why this is a service and the functions in `message.util.ts` are not.
 * The badge's words are handed out as keys: a word translated here, once, would keep the language
 * it was translated in until the list is read again.
 */
@Injectable({ providedIn: 'root' })
export class MessageLabelService {
  private readonly languageService = inject(LanguageService);

  /** The description record for the given language, falling back to the first one there is. */
  desc(o: { readonly descs?: readonly Desc[] } | undefined, lang?: string): Desc | undefined {
    lang = lang || this.languageService.language();
    if (o?.descs && o.descs.length > 0) {
      const match = o.descs.find((d) => d.lang === lang);
      return match || o.descs[0];
    }
    return undefined;
  }

  /**
   * The identifier a reader sees, as a model the badge renders. A message with no short id is
   * named by what it is instead, which is how the site has always labelled an unnumbered one.
   */
  badge(msg: Message, showStatus = false): MessageBadge {
    return {
      shortId: msg.shortId || undefined,
      typeKey: !msg.shortId && msg.type ? 'TYPE_' + msg.type : undefined,
      mainTypeKey: !msg.shortId && msg.mainType ? 'MAIN_TYPE_' + msg.mainType : undefined,
      status: showStatus ? statusChip(msg) : undefined,
      suffix: suffixFor(msg.type),
    };
  }
}

/**
 * An expired permanent notice is not marked: a permanent notice that has run out is the ordinary
 * end of its life and says nothing a reader has to act on.
 */
function statusChip(msg: Message): MessageStatusChip | undefined {
  if (msg.status === 'CANCELLED') return { kind: 'CANCELLED', key: 'STATUS_CANCELLED' };
  if (msg.status === 'EXPIRED' && msg.type !== 'PERMANENT_NOTICE') {
    return { kind: 'EXPIRED', key: 'STATUS_EXPIRED' };
  }
  return undefined;
}

function suffixFor(type: string | undefined): string | undefined {
  return type && Object.hasOwn(PROVISIONAL_TYPE_SUFFIXES, type)
    ? PROVISIONAL_TYPE_SUFFIXES[type as keyof typeof PROVISIONAL_TYPE_SUFFIXES]
    : undefined;
}
