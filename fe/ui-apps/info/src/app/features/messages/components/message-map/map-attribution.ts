import { SecurityContext } from '@angular/core';
import type { DomSanitizer } from '@angular/platform-browser';

/**
 * A credit as the map's attribution line renders it.
 *
 * OpenLayers writes an attribution into the page with `innerHTML`, past Angular's binding, so the
 * sanitising a plain `[innerHTML]` binding would do happens here — the credits are the site
 * owner's translations, and nothing that reaches the page's markup skips the sanitiser. A link
 * then opens where the footer's links open, in the same tab: a reader who follows the
 * accessibility statement from the map is not moved to a new tab without being told.
 */
export function attributionHtml(html: string, sanitizer: DomSanitizer): string {
  const sanitized = sanitizer.sanitize(SecurityContext.HTML, html) ?? '';
  if (!/\starget\s*=/i.test(sanitized)) return sanitized;

  const document = new DOMParser().parseFromString(sanitized, 'text/html');
  for (const element of document.body.querySelectorAll('[target]')) {
    element.removeAttribute('target');
    element.removeAttribute('rel');
  }
  return document.body.innerHTML;
}
