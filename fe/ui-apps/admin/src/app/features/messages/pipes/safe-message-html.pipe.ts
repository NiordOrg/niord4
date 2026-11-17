import { inject, Pipe, PipeTransform } from '@angular/core';
import type { SafeHtml } from '@angular/platform-browser';

import { MessageHtmlSanitizer } from '@niord/html/message-html-sanitizer';

@Pipe({ name: 'safeMessageHtml', pure: true })
export class SafeMessageHtmlPipe implements PipeTransform {
  private readonly sanitizer = inject(MessageHtmlSanitizer);

  transform(html: string | undefined | null): SafeHtml {
    return this.sanitizer.sanitize(html);
  }
}
