import type { Message } from '../../messages/models/message.model';
import { isMessageNumberEditable } from './message-series-rules.util';

/**
 * Merge an `adjustEditableMessage` result into the working message. Title is copied per-language
 * only when `autoTitle` (source is never auto-generated). Languages absent on the working copy are
 * left untouched.
 * `shortId` follows only when the message number is hand-editable; promulgations only on request.
 * The server's `editorFields` visibility map always follows — the backend recomputes it from
 * area/category/series on every adjust, and field visibility must track it.
 */
export function applyAdjustment(
  working: Message,
  server: Message,
  options?: { inclPromulgations?: boolean },
): Message {
  let descs = working.descs;
  if (working.autoTitle && working.descs) {
    descs = working.descs.map((desc) => {
      const serverDesc = server.descs?.find((candidate) => candidate.lang === desc.lang);
      if (!serverDesc) {
        return desc;
      }
      return { ...desc, title: serverDesc.title };
    });
  }

  return {
    ...working,
    descs,
    shortId: isMessageNumberEditable(working.messageSeries) ? server.shortId : working.shortId,
    promulgations: options?.inclPromulgations ? server.promulgations : working.promulgations,
    editorFields: server.editorFields ?? working.editorFields,
  };
}
