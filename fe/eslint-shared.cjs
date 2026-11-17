'use strict';
/**
 * The lint lists the base product's configuration and the country lint share: the packages the
 * public site never imports, the sanitiser bypass calls and the pipes a country template may bind
 * `[innerHTML]` through.
 *
 * `eslint.config.js` reads them from here. The kit keeps its own copy in
 * `country-kit/lib/country-lint.cjs`, because a country's configuration loads that file from the
 * kit; `scripts/eslint-shared.test.mjs` holds the two equal. CommonJS, as both configurations are,
 * and without a require.
 */

/** Packages the public site never imports: it has no sign-in and no edit flow. */
const INFO_ANONYMOUS_PACKAGES = ['keycloak-js', 'keycloak-js/*', '@angular/forms/signals'];
const INFO_ANONYMOUS_MESSAGE =
  'The public site is anonymous and read-only: it has no authentication and no edit flow.';

/** The DomSanitizer calls that switch Angular's own sanitiser off for one value. */
const BYPASS_SECURITY_TRUST = [
  'bypassSecurityTrustHtml',
  'bypassSecurityTrustScript',
  'bypassSecurityTrustStyle',
  'bypassSecurityTrustUrl',
  'bypassSecurityTrustResourceUrl',
];

/**
 * The pipes a country template may bind `[innerHTML]` through: the message-HTML sanitiser, the
 * plain-text pipe, which escapes, and a translation, whose markup the pack and branding checks
 * hold to the English text's and which Angular sanitises as any plain string.
 */
const INNER_HTML_PIPES = ['safeMessageHtml', 'plainTextToHtml', 'transloco'];

module.exports = {
  INFO_ANONYMOUS_PACKAGES,
  INFO_ANONYMOUS_MESSAGE,
  BYPASS_SECURITY_TRUST,
  INNER_HTML_PIPES,
};
