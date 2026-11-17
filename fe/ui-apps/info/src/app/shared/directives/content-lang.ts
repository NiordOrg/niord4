import { computed, Directive, inject, input } from '@angular/core';
import { LanguageService } from '../../core/services/language.service';

/**
 * Marks text the backend wrote in another language than the page's with the language it is in.
 *
 * A message, an area or a publication with no text in the reader's language arrives in one it
 * has, so a Kalaallisut page can hold Danish titles and an English page Danish details. Text
 * without a `lang` of its own is read aloud by the page's rules — Danish with Greenlandic
 * pronunciation — and a screen reader gives no sign that the language changed. Text in the
 * page's own language gets nothing: `<html lang>` already says what it is.
 */
@Directive({
  selector: '[appContentLang]',
  host: { '[attr.lang]': 'lang()' },
})
export class ContentLang {
  /** The language the text is written in, as the backend names it. */
  readonly appContentLang = input<string | undefined>();

  private readonly pageLanguage = inject(LanguageService).language;

  protected readonly lang = computed(() => {
    const lang = this.appContentLang();
    return lang && lang !== this.pageLanguage() ? lang : null;
  });
}
