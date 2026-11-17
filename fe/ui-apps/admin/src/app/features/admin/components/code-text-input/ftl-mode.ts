import { LanguageSupport, StreamLanguage, type StreamParser } from '@codemirror/language';

/**
 * Minimal Freemarker (FTL) highlighting via a stream parser — enough to colour
 * directives, macros, interpolations and comments in the script-resource editor;
 * not a full grammar. Only reached through a dynamic import, so `@codemirror/language`
 * stays in the lazy editor chunk.
 *
 * FTL syntax recognised: comments `<#-- ... -->`, directives `<#if>`/`</#if>`,
 * macro calls `<@macro>`/`</@macro>`, and interpolations `${ ... }`.
 */
interface FtlState {
  inComment: boolean;
}

const ftlParser: StreamParser<FtlState> = {
  name: 'ftl',
  startState: () => ({ inComment: false }),
  token(stream, state) {
    if (state.inComment) {
      if (stream.match(/^.*?-->/)) {
        state.inComment = false;
      } else {
        stream.skipToEnd();
      }
      return 'comment';
    }
    if (stream.match('<#--')) {
      // Single-line comment closes on the same line; otherwise span subsequent lines.
      if (!stream.match(/^.*?-->/)) {
        state.inComment = true;
        stream.skipToEnd();
      }
      return 'comment';
    }
    if (stream.match(/^\$\{[^}]*\}?/)) {
      return 'string';
    }
    if (stream.match(/^<\/?[#@][\w.]*/)) {
      return 'keyword';
    }
    if (stream.match(/^\/?>/)) {
      return 'keyword';
    }
    // Plain text — consume up to the next special char; always advance the stream.
    if (stream.eatWhile(/[^<$]/)) {
      return null;
    }
    stream.next();
    return null;
  },
};

export const ftlLanguage = StreamLanguage.define(ftlParser);

export function ftl(): LanguageSupport {
  return new LanguageSupport(ftlLanguage);
}
