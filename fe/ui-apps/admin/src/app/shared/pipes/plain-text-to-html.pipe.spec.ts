import { TestBed } from '@angular/core/testing';
import { DomSanitizer } from '@angular/platform-browser';
import { describe, expect, it, vi } from 'vitest';

import { PlainTextToHtmlPipe } from './plain-text-to-html.pipe';

function createPipe() {
  // Pass-through sanitizer so we can assert on the escaped string directly.
  const bypassSecurityTrustHtml = vi.fn((html: string) => html);
  const sanitizer = { bypassSecurityTrustHtml } as unknown as DomSanitizer;

  TestBed.configureTestingModule({
    providers: [{ provide: DomSanitizer, useValue: sanitizer }],
  });

  return TestBed.runInInjectionContext(() => new PlainTextToHtmlPipe());
}

describe('PlainTextToHtmlPipe — HTML escaping', () => {
  it('escapes ampersand first so other escapes do not double-encode', () => {
    const pipe = createPipe();
    // Bare & must become &amp; — NOT &amp;amp; (which would happen if & ran after < or >)
    expect(pipe.transform('a & b')).toContain('a&#8203;&nbsp;&#8203;&amp;&#8203;&nbsp;&#8203;b');
  });

  it('escapes < and > so HTML tags do not render', () => {
    const pipe = createPipe();
    expect(pipe.transform('<b>')).toBe('&lt;b&gt;');
    expect(pipe.transform('a<b>c')).toBe('a&lt;b&gt;c');
  });

  it('XSS regression: <script> payload is fully escaped, not rendered as a tag', () => {
    const pipe = createPipe();
    const payload = '<script>alert(1)</script>';
    const result = pipe.transform(payload) as string;
    expect(result).not.toContain('<script>');
    expect(result).not.toContain('</script>');
    expect(result).toContain('&lt;script&gt;');
    expect(result).toContain('&lt;/script&gt;');
  });

  it('XSS regression: img onerror attribute payload is escaped', () => {
    const pipe = createPipe();
    const payload = '<img src=x onerror="alert(1)">';
    const result = pipe.transform(payload) as string;
    expect(result).not.toContain('<img');
    expect(result).toContain('&lt;img');
  });
});

describe('PlainTextToHtmlPipe — whitespace transforms', () => {
  it('newline (\\n) becomes <br />', () => {
    const pipe = createPipe();
    expect(pipe.transform('a\nb')).toBe('a<br />b');
  });

  it('Windows newline (\\r\\n) becomes a single <br />', () => {
    const pipe = createPipe();
    expect(pipe.transform('a\r\nb')).toBe('a<br />b');
  });

  it('classic Mac newline (\\r) becomes <br />', () => {
    const pipe = createPipe();
    expect(pipe.transform('a\rb')).toBe('a<br />b');
  });

  it('tab becomes four spaces (each sandwiched with zero-width-nbsp)', () => {
    const pipe = createPipe();
    // \t -> '    ' (4 spaces) -> each space becomes the entity sandwich
    expect(pipe.transform('a\tb')).toBe(
      'a' +
        '&#8203;&nbsp;&#8203;&#8203;&nbsp;&#8203;&#8203;&nbsp;&#8203;&#8203;&nbsp;&#8203;' +
        'b',
    );
  });

  it('single space becomes the zero-width-nbsp sandwich', () => {
    const pipe = createPipe();
    expect(pipe.transform('a b')).toBe('a&#8203;&nbsp;&#8203;b');
  });
});

describe('PlainTextToHtmlPipe — edge cases', () => {
  it('null returns empty', () => {
    const pipe = createPipe();
    expect(pipe.transform(null)).toBe('');
  });

  it('undefined returns empty', () => {
    const pipe = createPipe();
    expect(pipe.transform(undefined)).toBe('');
  });

  it('empty string returns empty', () => {
    const pipe = createPipe();
    expect(pipe.transform('')).toBe('');
  });

  it('passes the escaped result through DomSanitizer.bypassSecurityTrustHtml', () => {
    const bypassSecurityTrustHtml = vi.fn((html: string) => `SAFE(${html})`);
    const sanitizer = { bypassSecurityTrustHtml } as unknown as DomSanitizer;
    TestBed.configureTestingModule({
      providers: [{ provide: DomSanitizer, useValue: sanitizer }],
    });
    const pipe = TestBed.runInInjectionContext(() => new PlainTextToHtmlPipe());

    expect(pipe.transform('a<b')).toBe('SAFE(a&lt;b)');
    expect(bypassSecurityTrustHtml).toHaveBeenCalledWith('a&lt;b');
  });
});
