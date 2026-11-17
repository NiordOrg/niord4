import { inject, Injectable } from '@angular/core';
import { TranslocoService } from '@jsverse/transloco';

import {
  Desc,
  MainType,
  Message,
  MessageStatus,
  PROVISIONAL_TYPE_SUFFIXES,
} from '../models/message.model';
import { LanguageService } from './language.service';

/** The status chip beside an identifier: the word, and which of the two states it names. */
export interface MessageStatusChip {
  readonly kind: MessageStatus;
  readonly label: string;
}

/** What the id badge draws: the identifier, an optional status chip, an optional marker. */
export interface MessageBadge {
  /** The short id, or the composed type and main-type labels when the message has none. */
  readonly label: string;
  /** Present only for an expired notice or a cancellation — the two a reader has to notice. */
  readonly status?: MessageStatusChip;
  /** `(T)` for a temporary notice, `(P)` for a preliminary one. */
  readonly suffix?: string;
}

/**
 * What a message is called in the language the reader is in. The words come from the bundle and
 * the active language, which is why this is a service and the functions in `message.util.ts`
 * are not.
 */
@Injectable({ providedIn: 'root' })
export class MessageLabelService {
  private readonly transloco = inject(TranslocoService);
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
      label: msg.shortId || this.composedLabel(msg.type, msg.mainType),
      status: showStatus ? this.statusChip(msg) : undefined,
      suffix: suffixFor(msg.type),
    };
  }

  private composedLabel(type: string | undefined, mainType: MainType | undefined): string {
    const typeLabel = type ? this.transloco.translate('TYPE_' + type) + ' ' : '';
    return typeLabel + (mainType ? this.transloco.translate('MAIN_TYPE_' + mainType) : '');
  }

  /**
   * An expired permanent notice is not marked: a permanent notice that has run out is the
   * ordinary end of its life and says nothing a reader has to act on.
   */
  private statusChip(msg: Message): MessageStatusChip | undefined {
    if (msg.status === 'CANCELLED') {
      return { kind: 'CANCELLED', label: this.transloco.translate('STATUS_CANCELLED') };
    }
    if (msg.status === 'EXPIRED' && msg.type !== 'PERMANENT_NOTICE') {
      return { kind: 'EXPIRED', label: this.transloco.translate('STATUS_EXPIRED') };
    }
    return undefined;
  }
}

function suffixFor(type: string | undefined): string | undefined {
  return type && Object.hasOwn(PROVISIONAL_TYPE_SUFFIXES, type)
    ? PROVISIONAL_TYPE_SUFFIXES[type as keyof typeof PROVISIONAL_TYPE_SUFFIXES]
    : undefined;
}
