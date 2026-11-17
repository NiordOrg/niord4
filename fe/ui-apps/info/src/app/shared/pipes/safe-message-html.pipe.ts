import { inject, Pipe, PipeTransform } from '@angular/core';
import type { SafeHtml } from '@angular/platform-browser';

import { MessageHtmlSanitizer } from '@niord/html/message-html-sanitizer';

/**
 * Backend-authored message HTML, sanitised, for a `[innerHTML]` binding to render.
 *
 * The shared sanitiser is what makes the markup safe — it runs Angular's own sanitiser over it
 * and puts back only the two inline declarations a message is allowed to keep — so the result is
 * marked safe rather than sanitised a second time at the binding, which would remove those two
 * declarations again.
 */
@Pipe({ name: 'safeMessageHtml' })
export class SafeMessageHtmlPipe implements PipeTransform {
  private readonly sanitizer = inject(MessageHtmlSanitizer);

  transform(html: string | undefined | null): SafeHtml {
    return this.sanitizer.sanitize(html);
  }
}
