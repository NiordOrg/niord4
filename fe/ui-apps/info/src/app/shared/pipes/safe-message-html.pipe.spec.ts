import { SecurityContext } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { DomSanitizer } from '@angular/platform-browser';

import { SafeMessageHtmlPipe } from './safe-message-html.pipe';

describe('SafeMessageHtmlPipe', () => {
  let pipe: SafeMessageHtmlPipe;
  let dom: DomSanitizer;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    dom = TestBed.inject(DomSanitizer);
    pipe = TestBed.runInInjectionContext(() => new SafeMessageHtmlPipe());
  });

  it('sanitises what it is given', () => {
    expect(
      dom.sanitize(SecurityContext.HTML, pipe.transform('<p>Safe</p><script>alert(1)</script>')),
    ).toBe('<p>Safe</p>');
  });

  // The shared sanitiser has already run, so the value is marked safe rather than sanitised a
  // second time at the binding — which is what lets the two allowed declarations reach the page.
  it('keeps the indent a message carries', () => {
    expect(
      dom.sanitize(SecurityContext.HTML, pipe.transform('<p style="padding-left: 30px">x</p>')),
    ).toBe('<p style="padding-left: 30px">x</p>');
  });

  it('is empty for nothing to render', () => {
    expect(dom.sanitize(SecurityContext.HTML, pipe.transform(undefined))).toBe('');
    expect(dom.sanitize(SecurityContext.HTML, pipe.transform(null))).toBe('');
  });
});
