import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

/**
 * The images a first visit fetches besides what `bundle:info` measures: the tab icon every page
 * names, and the home-screen icon. Their size is held here instead, for the base build's default
 * overlay — the tab icon at the 32 px a browser tab draws, and the home-screen icon at the 152 px
 * only a phone asks for. Paths are relative to the workspace folder, where the runner starts.
 */
const BRANDING = 'ui-apps/info/public/assets/default/branding';

/** A PNG's pixel size, from its header: the IHDR chunk always comes first. */
function pngSize(file: string): { width: number; height: number } {
  const bytes = readFileSync(file);
  expect(new TextDecoder().decode(bytes.subarray(12, 16)), file).toBe('IHDR');
  const header = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  return { width: header.getUint32(16), height: header.getUint32(20) };
}

describe('branding images', () => {
  it('default: the tab icon is 32 px and the home-screen icon 152 px', () => {
    expect(pngSize(`${BRANDING}/favicon.png`)).toEqual({ width: 32, height: 32 });
    expect(pngSize(`${BRANDING}/apple-touch-icon.png`)).toEqual({ width: 152, height: 152 });
  });
});
