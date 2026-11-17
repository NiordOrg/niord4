import { Injectable } from '@angular/core';

import type { MessageDescription } from '../../messages/models/message.model';

/**
 * Pure, signal-friendly helpers for the per-language `descs` arrays carried by a message
 * (and its parts/references/attachments).
 *
 * Every helper is side-effect free and treats its input as immutable — in particular
 * `sortMessageDescs` returns a copy and never reorders a shared message's descs in place
 * (reordering a shared message's descs in place would corrupt a sorted preview elsewhere).
 */
@Injectable({ providedIn: 'root' })
export class MessageDescService {
  /** The desc whose `lang` matches, or `undefined` when none does. */
  descForLanguage(
    descs: readonly MessageDescription[] | undefined,
    lang: string,
  ): MessageDescription | undefined {
    return descs?.find((desc) => desc.lang === lang);
  }

  /** The desc for `lang`, falling back to the first desc. */
  descForLangOrDefault(
    descs: readonly MessageDescription[] | undefined,
    lang: string,
  ): MessageDescription | undefined {
    if (!descs || descs.length === 0) {
      return undefined;
    }
    return descs.find((desc) => desc.lang === lang) ?? descs[0];
  }

  /**
   * A copy of `descs` ordered by `languages`. Descs whose language is not in the list keep
   * their relative order at the end (Array.prototype.sort is stable). Never mutates `descs`.
   */
  sortMessageDescs(
    descs: readonly MessageDescription[] | undefined,
    languages: readonly string[],
  ): MessageDescription[] {
    const rank = (lang: string | undefined): number => {
      const index = languages.indexOf(lang ?? '');
      return index === -1 ? languages.length : index;
    };
    return [...(descs ?? [])].sort((a, b) => rank(a.lang) - rank(b.lang));
  }

  /**
   * Ensure exactly one desc per language in `languages`, ordered by it. Existing descs are
   * reused; a missing language gets a fresh desc from `create`. Returns a new array — the
   * source is never mutated.
   */
  checkDescs(
    descs: readonly MessageDescription[] | undefined,
    languages: readonly string[],
    create: (lang: string) => MessageDescription,
  ): MessageDescription[] {
    const existing = descs ?? [];
    return languages.map((lang) => existing.find((desc) => desc.lang === lang) ?? create(lang));
  }
}
