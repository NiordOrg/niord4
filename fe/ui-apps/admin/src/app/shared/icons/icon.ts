import { Component, ElementRef, inject, input, isDevMode, type OnChanges } from '@angular/core';
import { injectNgIcons } from '@ng-icons/core';

/**
 * Each glyph's `<svg>`, parsed from its markup the first time it is shown and cloned for every
 * icon after that. `<ng-icon>` parsed the markup once per icon: a message list of 176 rows shows
 * some 700 icons, and parsing them took about a tenth of the time it took to build the list.
 */
const glyphs = new Map<string, SVGElement>();

/**
 * Inline SVG icon (Lucide via `@ng-icons`). `name` is the `@ng-icons/lucide`
 * export identifier — e.g. `lucidePencil`, `lucideStar`. Decorative by default
 * (`aria-hidden`): icon-only controls keep their `aria-label` on the interactive
 * parent. Size defaults to `1em` so the glyph scales with the surrounding
 * font-size (a `text-lg` context sizes it up).
 *
 * The glyphs are the ones `provideNiordIcons()` registers, read as `<ng-icon>` reads them, and the
 * markup is the one `<ng-icon>` rendered — a box sized to `size` that holds the `<svg>` — built
 * here by hand, without a template, so an icon costs a clone and not a parse.
 */
@Component({
  selector: 'app-icon',
  template: '',
  host: { class: 'inline-flex items-center' },
})
export class Icon implements OnChanges {
  /** `@ng-icons/lucide` export identifier, e.g. `lucideStar`. */
  readonly name = input.required<string>();
  /** CSS size of the glyph; defaults to `1em` (scales with font-size). */
  readonly size = input('1em');
  /** Spin the glyph (loading indicators). */
  readonly spin = input(false);
  /**
   * Render filled (`fill-current`). Lucide glyphs are stroke-only, so a solid
   * look — e.g. a filled star for the selected state — is opt-in per use.
   */
  readonly filled = input(false);

  private readonly icons = injectNgIcons();
  private readonly box: HTMLElement;
  private shown: string | undefined;

  constructor() {
    const host: HTMLElement = inject(ElementRef).nativeElement;
    // The box `<ng-icon>` drew: an inline block of the icon's size that clips its glyph.
    this.box = host.ownerDocument.createElement('span');
    this.box.setAttribute('aria-hidden', 'true');
    this.box.style.cssText =
      'display:inline-block;line-height:initial;vertical-align:initial;overflow:hidden';
    host.appendChild(this.box);
  }

  ngOnChanges(): void {
    const name = this.name();
    if (name !== this.shown) {
      this.shown = name;
      const glyph = this.glyph(name);
      this.box.replaceChildren(...(glyph ? [glyph.cloneNode(true)] : []));
    }
    this.box.style.width = this.size();
    this.box.style.height = this.size();
    this.box.classList.toggle('animate-spin', this.spin());
    this.box.classList.toggle('fill-current', this.filled());
  }

  /** The parsed glyph of `name`, or `undefined` for a name no registered icon set has. */
  private glyph(name: string): SVGElement | undefined {
    let glyph = glyphs.get(name);
    if (glyph === undefined) {
      // The set registered last wins, as it does for `<ng-icon>`. A name no set has is looked up
      // again next time, not remembered: another injector may register it.
      const markup = [...this.icons].reverse().find((set) => set[name] !== undefined)?.[name];
      glyph = markup === undefined ? undefined : parse(markup, this.box.ownerDocument);
      if (glyph) glyphs.set(name, glyph);
    }
    if (!glyph && isDevMode()) {
      console.warn(`No icon named ${name} is registered; add it to niord-icons.ts.`);
    }
    return glyph;
  }
}

/** The `<svg>` of a glyph's markup, sized to fill the box it is put in, as `<ng-icon>` sized it. */
function parse(markup: string, document: Document): SVGElement | undefined {
  const template = document.createElement('template');
  template.innerHTML = markup;
  const svg = template.content.firstElementChild;
  if (!(svg instanceof SVGElement)) return undefined;
  svg.style.width = 'inherit';
  svg.style.height = 'inherit';
  svg.style.verticalAlign = 'inherit';
  return svg;
}
