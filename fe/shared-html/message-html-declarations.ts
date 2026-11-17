/**
 * The two inline style declarations the HTML a message carries is allowed to keep, and the values
 * each of them may take.
 *
 * Angular's sanitiser drops every `style` attribute and has no allow-list, so the choice is all
 * or nothing per declaration. `text-align` and `padding-left` are the two an editor reaches for
 * deliberately — a justified paragraph, an indented block of dates — and they are the two a
 * mariner notices when they are gone. Everything else a message carries in `style` is residue of
 * a paste out of a word processor (`font-family`, `font-size`, `color`, `background-color`,
 * `margin`) and would override the site's own type scale with an 8.5pt Verdana in pure black on
 * pure white: restoring those is worse than dropping them.
 *
 * The sanitiser puts these declarations back after sanitising, and the administration app's
 * rich-text editor keeps them through an edit. Both read them through this module, so an editor
 * never holds an indent the rendered message would not show, nor loses one it would.
 */
export type KeptStyleProperty = 'text-align' | 'padding-left';

const ALIGNMENTS = new Set(['left', 'right', 'center', 'justify']);

/** A plain, non-negative length with one of the units a word processor emits. */
const LENGTH = /^(\d+(?:\.\d+)?)(px|rem|em|pt|cm)$/;

/** How many CSS pixels one of those units is worth, so one cap covers them all. */
const PIXELS_PER_UNIT = new Map<string, number>([
  ['px', 1],
  ['rem', 16],
  ['em', 16],
  ['pt', 96 / 72],
  ['cm', 96 / 2.54],
]);

/**
 * The widest indent that is still an indent. Ten times the root font size: beyond it a paragraph
 * is not indented but pushed off a narrow screen, which is the one way this allowance could make
 * a message unreadable rather than more readable.
 */
const MAX_INDENT_PIXELS = 160;

/**
 * The allowed declarations of one `style` attribute, rebuilt from the property name and a
 * validated value, in the order they were written. Nothing is copied out of the input: a value
 * that does not match one of the two patterns is dropped whole, which is how `url(...)`,
 * `expression(...)`, `calc(...)` and `var(...)` are turned away without a rule of their own.
 */
export function allowedDeclarations(style: string): string {
  return keptDeclarations(style)
    .map(([property, value]) => `${property}: ${value}`)
    .join('; ');
}

/**
 * The value one of the two properties takes in a `style` attribute once everything else is
 * dropped — the last allowed declaration of it, as it is in CSS — or `null` when it has none.
 */
export function allowedDeclaration(style: string, property: KeptStyleProperty): string | null {
  let kept: string | null = null;
  for (const [name, value] of keptDeclarations(style)) {
    if (name === property) kept = value;
  }
  return kept;
}

function keptDeclarations(style: string): [KeptStyleProperty, string][] {
  const kept: [KeptStyleProperty, string][] = [];

  for (const declaration of style.split(';')) {
    const colon = declaration.indexOf(':');
    if (colon < 0) continue;
    const property = declaration.slice(0, colon).trim().toLowerCase();
    const value = declaration
      .slice(colon + 1)
      .trim()
      .toLowerCase();

    if (property === 'text-align') {
      if (ALIGNMENTS.has(value)) kept.push([property, value]);
    } else if (property === 'padding-left') {
      if (isIndentWithinCap(value)) kept.push([property, value]);
    }
  }

  return kept;
}

/** Whether the value is a plain length no wider than the cap. */
function isIndentWithinCap(value: string): boolean {
  const length = LENGTH.exec(value);
  if (length === null) return false;
  const pixels = Number(length[1]) * (PIXELS_PER_UNIT.get(length[2]) ?? Number.NaN);
  return pixels <= MAX_INDENT_PIXELS;
}
