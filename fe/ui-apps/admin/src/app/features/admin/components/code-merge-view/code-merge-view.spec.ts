import { ComponentFixture, TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it } from 'vitest';

import { CodeMergeView } from './code-merge-view';

// The merge view is CodeMirror behind a lazy dynamic import. The Angular unit-test
// system forbids `vi.mock` on relative imports, so these tests drive the REAL glue
// and assert on the observable side-effect: the CodeMirror merge DOM the component
// mounts (and re-mounts / tears down) inside its host `<div>`.

const HOST = 'div';

/** Poll change-detection until the predicate holds (the lazy import + mount are async). */
async function poll(
  fixture: ComponentFixture<CodeMergeView>,
  predicate: () => boolean,
): Promise<void> {
  for (let i = 0; i < 100; i++) {
    fixture.detectChanges();
    if (predicate()) return;
    await new Promise((resolve) => setTimeout(resolve, 20));
  }
  fixture.detectChanges();
}

function hostEl(fixture: ComponentFixture<CodeMergeView>): HTMLElement {
  return fixture.nativeElement.querySelector(HOST) as HTMLElement;
}

async function mount(inputs: {
  original: string;
  modified: string;
  language?: 'ftl' | 'javascript' | 'json';
}): Promise<{ fixture: ComponentFixture<CodeMergeView>; host: HTMLElement }> {
  const fixture = TestBed.createComponent(CodeMergeView);
  fixture.componentRef.setInput('original', inputs.original);
  fixture.componentRef.setInput('modified', inputs.modified);
  if (inputs.language) fixture.componentRef.setInput('language', inputs.language);
  await poll(fixture, () => !!hostEl(fixture).querySelector('.cm-mergeView'));
  return { fixture, host: hostEl(fixture) };
}

describe('CodeMergeView', () => {
  const fixtures: ComponentFixture<CodeMergeView>[] = [];
  afterEach(() => {
    while (fixtures.length) fixtures.pop()?.destroy();
  });

  it('mounts a side-by-side merge view showing both source snapshots', async () => {
    const { fixture, host } = await mount({
      original: 'shared-head\nORIGINAL-ONLY',
      modified: 'shared-head\nMODIFIED-ONLY',
    });
    fixtures.push(fixture);

    expect(host.querySelector('.cm-mergeView')).not.toBeNull();
    // Two editor panes — the original (a) and the modified (b).
    expect(host.querySelectorAll('.cm-editor').length).toBe(2);
    const text = host.textContent ?? '';
    expect(text).toContain('ORIGINAL-ONLY');
    expect(text).toContain('MODIFIED-ONLY');
  });

  it('re-mounts when a snapshot input changes: old view is detached, new content is shown', async () => {
    const { fixture, host } = await mount({ original: 'CONST-ORIGINAL', modified: 'REV-ONE' });
    fixtures.push(fixture);
    const firstView = host.querySelector('.cm-mergeView') as HTMLElement;
    expect((host.textContent ?? '').includes('REV-ONE')).toBe(true);

    fixture.componentRef.setInput('modified', 'REV-TWO');
    await poll(
      fixture,
      () =>
        !!host.querySelector('.cm-mergeView') &&
        host.querySelector('.cm-mergeView') !== firstView &&
        (host.textContent ?? '').includes('REV-TWO'),
    );

    const secondView = host.querySelector('.cm-mergeView') as HTMLElement;
    expect(secondView).not.toBe(firstView); // rebuilt, not reused
    expect(firstView.isConnected).toBe(false); // the old view was torn down + removed
    const text = host.textContent ?? '';
    expect(text).toContain('REV-TWO');
    expect(text).not.toContain('REV-ONE');
    expect(text).toContain('CONST-ORIGINAL'); // unchanged pane persists
  });

  it('honors the language input (mounts a JSON diff without error)', async () => {
    const { fixture, host } = await mount({
      original: '{ "a": 1 }',
      modified: '{ "a": 2 }',
      language: 'json',
    });
    fixtures.push(fixture);

    expect(host.querySelector('.cm-mergeView')).not.toBeNull();
    expect(host.textContent ?? '').toContain('"a": 2');
  });

  it('tears the merge view down on destroy without throwing', async () => {
    const { fixture, host } = await mount({ original: 'x', modified: 'y' });
    const view = host.querySelector('.cm-mergeView') as HTMLElement;
    expect(view).not.toBeNull();

    expect(() => fixture.destroy()).not.toThrow();
    expect(view.isConnected).toBe(false);
  });
});
