/**
 * Tests for the record of country-only utilities, lib/utilities.mjs.
 *
 *   npm run test:guards      # from fe/
 */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { after, describe, it } from 'node:test';
import { fileURLToPath } from 'node:url';
import { countryUtilities, cssSelector, firstArbitrary } from './lib/utilities.mjs';

const KIT = path.dirname(fileURLToPath(import.meta.url));
const temporary = [];
after(() => {
  for (const folder of temporary) fs.rmSync(folder, { recursive: true, force: true });
});

/** A country fe/ holding `files`; returns it. */
function country(files) {
  const fe = fs.mkdtempSync(path.join(os.tmpdir(), 'niord-utilities-'));
  temporary.push(fe);
  for (const [file, text] of Object.entries(files)) {
    fs.mkdirSync(path.dirname(path.join(fe, file)), { recursive: true });
    fs.writeFileSync(path.join(fe, file), text);
  }
  return fe;
}

// What Tailwind 4.2 writes for these classes, compiled with breakpoints and a spacing scale (the
// build itself minifies the rules, never the selectors' escapes).
const TAILWIND = String.raw`.aspect-\[4\/3\] { aspect-ratio: 4/3; }
.w-\[1\.5rem\] { width: 1.5rem; }
.w-\[calc\(100\%-2rem\)\] { width: calc(100% - 2rem); }
.bg-\[\#0a3d62\] { background-color: #0a3d62; }
.\[mask-type\:luminance\] { mask-type: luminance; }
@media (width >= 40rem) { .sm\:grid-cols-\[3fr_2fr\] { grid-template-columns: 3fr 2fr; } }
@media (width >= 48rem) { .md\:hover\:-mt-\[3px\] { &:hover { margin-top: calc(3px * -1); } } }
@media (width >= 96rem) { .\32 xl\:p-\[7px\] { padding: 7px; } }`;

describe('the country-only utilities', () => {
  it("records the example footer's utility, and nothing for a template without one", () => {
    const { admin, info } = countryUtilities(path.join(KIT, 'example', 'fe'));
    assert.deepEqual(info, [
      { file: 'info/app/footer/footer.component.ts', utility: 'sm:grid-cols-[3fr_2fr]' },
    ]);
    assert.deepEqual(admin, []);
  });

  it('records the first per template, ignoring specs and assets', () => {
    const fe = country({
      'admin/app/a.html': '<p class="p-2 w-[1.5rem]"></p><p class="bg-[#0a3d62]"></p>',
      'admin/overrides/x/b.html': '<i class="text-sm"></i><i class="aspect-[4/3] -mt-[3px]"></i>',
      'admin/app/a.spec.ts': '@Component({ template: \'<p class="w-[9px]"></p>\' })',
      'admin/assets/legal/disclaimer.en.html': '<p class="w-[9px]"></p>',
      'info/app/plain.html': '<p class="grid gap-2"></p>',
    });
    assert.deepEqual(countryUtilities(fe), {
      admin: [
        { file: 'admin/app/a.html', utility: 'w-[1.5rem]' },
        { file: 'admin/overrides/x/b.html', utility: 'aspect-[4/3]' },
      ],
      info: [],
    });
  });

  it('finds an inline template, a [class.x-[..]] binding and an arbitrary property', () => {
    const fe = country({
      'info/app/c.ts':
        'class C {}\n@Component({\n  template: `<div [class.w-[calc(100%-2rem)]]="wide()" class="flex"></div>`,\n})',
      'admin/app/d.ts': `@Component({ template: '<span class="[mask-type:luminance]"></span>' })`,
    });
    assert.deepEqual(countryUtilities(fe), {
      admin: [{ file: 'admin/app/d.ts', utility: '[mask-type:luminance]' }],
      info: [{ file: 'info/app/c.ts', utility: 'w-[calc(100%-2rem)]' }],
    });
    assert.equal(firstArbitrary('<p [class]="x" ngClass="w-[1px]" class="m-1"></p>'), undefined);
    assert.equal(firstArbitrary('<p class="md:hover:-mt-[3px]"></p>'), 'md:hover:-mt-[3px]');
  });

  it('escapes a utility as Tailwind writes its selector', () => {
    for (const utility of [
      'sm:grid-cols-[3fr_2fr]',
      'md:hover:-mt-[3px]',
      'aspect-[4/3]',
      'w-[1.5rem]',
      'bg-[#0a3d62]',
      'w-[calc(100%-2rem)]',
      '[mask-type:luminance]',
      '2xl:p-[7px]',
    ])
      assert.ok(
        TAILWIND.includes(`${cssSelector(utility)} {`),
        `${cssSelector(utility)} is not in Tailwind's output`,
      );
    assert.equal(cssSelector('sm:grid-cols-[3fr_2fr]'), String.raw`.sm\:grid-cols-\[3fr_2fr\]`);
  });
});
