import { inject, Pipe, PipeTransform } from '@angular/core';
import { DomSanitizer, SafeHtml } from '@angular/platform-browser';

@Pipe({
  name: 'plainTextToHtml',
  standalone: true,
})
export class PlainTextToHtmlPipe implements PipeTransform {
  private readonly sanitizer = inject(DomSanitizer);

  transform(value: string | null | undefined): SafeHtml {
    const text = value ?? '';
    if (text === '') {
      return this.sanitizer.bypassSecurityTrustHtml('');
    }
    const escaped = text
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/\t/g, '    ')
      .replace(/ /g, '&#8203;&nbsp;&#8203;')
      .replace(/\r\n|\r|\n/g, '<br />');
    return this.sanitizer.bypassSecurityTrustHtml(escaped);
  }
}
