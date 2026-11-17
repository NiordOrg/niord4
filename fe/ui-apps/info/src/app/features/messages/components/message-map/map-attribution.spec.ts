import { TestBed } from '@angular/core/testing';
import { DomSanitizer } from '@angular/platform-browser';

import { attributionHtml } from './map-attribution';

function links(html: string): HTMLAnchorElement[] {
  return [...new DOMParser().parseFromString(html, 'text/html').body.querySelectorAll('a')];
}

describe('attributionHtml', () => {
  let sanitizer: DomSanitizer;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    sanitizer = TestBed.inject(DomSanitizer);
  });

  it('keeps the credit and its link', () => {
    const html = attributionHtml(
      '&copy; <a href="https://www.example.org/da/">Example Maritime Authority</a>.',
      sanitizer,
    );
    const [link] = links(html);
    expect(link.getAttribute('href')).toBe('https://www.example.org/da/');
    expect(link.textContent).toBe('Example Maritime Authority');
    expect(html).toContain('Example Maritime Authority</a>.');
  });

  // The footer opens the same statement in the same tab.
  it('opens a link in the same tab, as the footer does', () => {
    const [link] = links(
      attributionHtml(
        '<a href="https://accessibility.example.org/x" target="_blank" rel="opener">Tilgængelighed</a>.',
        sanitizer,
      ),
    );
    expect(link.hasAttribute('target')).toBe(false);
    expect(link.hasAttribute('rel')).toBe(false);
    expect(link.getAttribute('href')).toBe('https://accessibility.example.org/x');
  });

  it('runs the credit through the sanitiser, as a plain binding would', () => {
    const html = attributionHtml(
      '<a href="javascript:alert(1)" onclick="alert(2)">x</a><script>alert(3)</script>' +
        '<img src="x" onerror="alert(4)">',
      sanitizer,
    );
    expect(html).not.toContain('href="javascript:');
    expect(html).toContain('unsafe:');
    expect(html).not.toContain('onclick');
    expect(html).not.toContain('<script');
    expect(html).not.toContain('onerror');
  });

  it('leaves a credit without a link as the sanitiser writes it', () => {
    expect(attributionHtml('OpenStreetMap contributors.', sanitizer)).toBe(
      'OpenStreetMap contributors.',
    );
  });
});
