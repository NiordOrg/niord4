/**
 * The country-only utilities (fe-country-split-plan §4.7, §4.8): a Tailwind class with an
 * arbitrary value that a country template uses is built only because the generated style entry
 * puts the country's folders into Tailwind's utility scan (lib/workspace.mjs, styleWrapper). Set-up
 * records the first such class of each country template in .niord/build/country-utilities.json,
 * and the build check (lib/build-checks.mjs) finds each one's selector in its application's built
 * CSS, so a scan that lost the country's folders fails the build instead of shipping unstyled
 * pages.
 *
 * A country template is an `.html` file under `<app>/app/` or `<app>/overrides/`, or the inline
 * `template:` string of a `.ts` file there that is not a spec. Its classes are read from static
 * `class` attributes and `[class.<name>]` bindings.
 */
import fs from 'node:fs';
import path from 'node:path';

export const APPS = ['admin', 'info'];
const FOLDERS = ['app', 'overrides'];

// A utility with an arbitrary value (`sm:grid-cols-[3fr_2fr]`, `-mt-[3px]`) or an arbitrary
// property (`[mask-type:luminance]`), behind any variants.
const ARBITRARY = new RegExp(
  String.raw`^(?:[a-z0-9-]+:)*(?:-?[a-z][a-z0-9-]*-\[[^\]\s]+\]|\[[a-z-]+:[^\]\s]+\])$`,
);
const CLASS_ATTRIBUTE = /(?<![\w.[-])class\s*=\s*(["'])(.*?)\1/gs;
const CLASS_BINDING = /\[class\.((?:[^\]\s=[]|\[[^\]\s]*\])+)\]\s*=/g;
const INLINE_TEMPLATE =
  /\btemplate\s*:\s*(`(?:[^`\\]|\\.)*`|'(?:[^'\\\n]|\\.)*'|"(?:[^"\\\n]|\\.)*")/gs;

/** Every file under `dir`, relative to `fe` and with `/`. */
function filesUnder(fe, dir) {
  const folder = path.join(fe, dir);
  if (!fs.existsSync(folder)) return [];
  return fs
    .readdirSync(folder, { recursive: true, withFileTypes: true })
    .filter((entry) => entry.isFile())
    .map((entry) =>
      path.relative(fe, path.join(entry.parentPath, entry.name)).replaceAll('\\', '/'),
    )
    .sort();
}

/** The template texts of one file: the whole of an `.html`, the inline templates of a `.ts`. */
function templatesOf(file, text) {
  if (file.endsWith('.html')) return [text];
  return [...text.matchAll(INLINE_TEMPLATE)].map((match) => match[1].slice(1, -1));
}

/** The first class with an arbitrary value in a template, in the order of the text, or undefined. */
export function firstArbitrary(template) {
  const found = [];
  for (const match of template.matchAll(CLASS_ATTRIBUTE)) {
    let offset = match.index + match[0].indexOf(match[2]);
    for (const token of match[2].split(/(\s+)/)) {
      if (ARBITRARY.test(token)) found.push({ index: offset, utility: token });
      offset += token.length;
    }
  }
  for (const match of template.matchAll(CLASS_BINDING))
    if (ARBITRARY.test(match[1])) found.push({ index: match.index, utility: match[1] });
  return found.sort((a, b) => a.index - b.index)[0]?.utility;
}

/**
 * Per application, the first arbitrary-value class of every country template that has one:
 * `{ admin: [{ file, utility }], info: [...] }`, sorted by file, the paths relative to `fe`.
 */
export function countryUtilities(fe) {
  const out = {};
  for (const app of APPS) {
    out[app] = [];
    for (const file of FOLDERS.flatMap((folder) => filesUnder(fe, `${app}/${folder}`))) {
      const template = file.endsWith('.html');
      const code = file.endsWith('.ts') && !file.endsWith('.spec.ts');
      if (!template && !code) continue;
      const text = fs.readFileSync(path.join(fe, file), 'utf8');
      for (const each of templatesOf(file, text)) {
        const utility = firstArbitrary(each);
        if (utility) {
          out[app].push({ file, utility });
          break;
        }
      }
    }
  }
  return out;
}

/**
 * A class as Tailwind 4 writes it in a selector: `.` and the name with every character but a
 * letter, a digit, `-` and `_` escaped by a backslash, and a leading digit as its code point
 * (`sm:grid-cols-[3fr_2fr]` → `.sm\:grid-cols-\[3fr_2fr\]`).
 */
export function cssSelector(utility) {
  let out = '';
  [...utility].forEach((c, i) => {
    if (/[A-Za-z_-]/.test(c) || (/[0-9]/.test(c) && i > 0)) out += c;
    else if (/[0-9]/.test(c)) out += `\\${c.codePointAt(0).toString(16)} `;
    else out += `\\${c}`;
  });
  return `.${out}`;
}
