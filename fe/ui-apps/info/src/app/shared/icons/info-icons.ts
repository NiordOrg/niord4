/**
 * The Lucide icon set for the public site — every `<app-icon>` glyph, imported once and
 * registered app-wide by `provideInfoIcons()`, so a call site renders without an import of
 * its own. Inline SVG (`stroke="currentColor"`), no icon font: a font carries every glyph it
 * has, and every first visit would pay for all of them to draw a handful.
 *
 * Add the matching `lucide*` import AND the entry here when a new glyph is used; a name with
 * no entry renders nothing.
 */
import { provideIcons } from '@ng-icons/core';
import {
  lucideArrowLeft,
  lucideBook,
  lucideChevronLeft,
  lucideChevronRight,
  lucideDownload,
  lucideExternalLink,
  lucideFile,
  lucideGlobe,
  lucideLayoutList,
  lucideList,
  lucideLoaderCircle,
  lucidePaperclip,
  lucidePrinter,
  lucideTriangleAlert,
  lucideX,
} from '@ng-icons/lucide';

export const infoLucideIcons = {
  lucideArrowLeft,
  lucideBook,
  lucideChevronLeft,
  lucideChevronRight,
  lucideDownload,
  lucideExternalLink,
  lucideFile,
  lucideGlobe,
  lucideLayoutList,
  lucideList,
  lucideLoaderCircle,
  lucidePaperclip,
  lucidePrinter,
  lucideTriangleAlert,
  lucideX,
};

/** Register the site's icon set. Belongs in the root `providers` of `app.config.ts`. */
export function provideInfoIcons() {
  return provideIcons(infoLucideIcons);
}
