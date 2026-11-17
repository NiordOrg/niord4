import { TestBed } from '@angular/core/testing';
import type { WritableSignal } from '@angular/core';
import { afterEach, describe, expect, it } from 'vitest';

import { provideNiordIcons } from '../../../../shared/icons/niord-icons';
import { provideTestTransloco } from '../../../../shared/testing/transloco-testing';
import type { RichTextLinkState } from '../../../../shared/forms/rich-text-editor/rich-text-editor';
import { LinkDialog } from './link-dialog';

/** The dialog's protected surface, reached for a focused logic test (no overlay needed). */
interface DialogInternals {
  url: WritableSignal<string>;
  text: WritableSignal<string>;
  title: WritableSignal<string>;
  target: WritableSignal<string>;
  onOpened(): void;
  save(): void;
  remove(): void;
}

function setup(state?: RichTextLinkState) {
  TestBed.configureTestingModule({
    providers: [provideTestTransloco(), provideNiordIcons()],
  });
  const fixture = TestBed.createComponent(LinkDialog);
  if (state) {
    fixture.componentRef.setInput('state', state);
  }
  fixture.componentRef.setInput('visible', true);
  fixture.detectChanges();
  const internals = fixture.componentInstance as unknown as DialogInternals;
  const emitted: RichTextLinkState[] = [];
  fixture.componentInstance.confirmed.subscribe((value) => emitted.push(value));
  return { fixture, internals, emitted };
}

afterEach(() => {
  document.querySelectorAll('.cdk-overlay-container').forEach((el) => el.replaceChildren());
});

describe('LinkDialog', () => {
  it('seeds the fields from the invoking editor state on open', () => {
    const { internals } = setup({
      href: 'https://x.test',
      text: 'Dansk Fyrliste',
      title: 'Nautical',
      target: '_blank',
    });
    internals.onOpened();
    expect(internals.url()).toBe('https://x.test');
    expect(internals.text()).toBe('Dansk Fyrliste');
    expect(internals.title()).toBe('Nautical');
    expect(internals.target()).toBe('_blank');
  });

  it('emits the trimmed edited link on save', () => {
    const { internals, emitted } = setup({ href: '', text: '', title: '', target: '' });
    internals.url.set('  https://x.test  ');
    internals.text.set('Dansk Fyrliste');
    internals.title.set('  Nautical  ');
    internals.target.set('_blank');

    internals.save();

    expect(emitted).toEqual([
      { href: 'https://x.test', text: 'Dansk Fyrliste', title: 'Nautical', target: '_blank' },
    ]);
  });

  it('emits a blank href on remove (the host clears the link)', () => {
    const { internals, emitted } = setup({
      href: 'https://x.test',
      text: 'hi',
      title: '',
      target: '',
    });
    internals.remove();
    expect(emitted).toEqual([{ href: '', text: '', title: '', target: '' }]);
  });
});
