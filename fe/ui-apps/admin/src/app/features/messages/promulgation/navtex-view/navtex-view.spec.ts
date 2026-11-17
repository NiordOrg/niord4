import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';

import { NavtexView } from './navtex-view';
import type { NavtexPromulgation } from './navtex-promulgation.model';
import { provideTestTransloco } from '../../../../shared/testing/transloco-testing';

function setup(promulgation: NavtexPromulgation) {
  TestBed.configureTestingModule({ providers: [provideTestTransloco()] });
  const fixture = TestBed.createComponent(NavtexView);
  fixture.componentRef.setInput('promulgation', promulgation);
  fixture.detectChanges();
  return fixture;
}

const base = {
  type: { typeId: 'navtex', serviceId: 'navtex', name: 'NAVTEX' },
  promulgate: true,
} as const;

describe('NavtexView', () => {
  it('renders the priority value', () => {
    const fixture = setup({ ...base, priority: 'VITAL' });
    expect(fixture.nativeElement.textContent).toContain('VITAL');
  });

  it('renders only the transmitters whose flag is true', () => {
    const fixture = setup({
      ...base,
      transmitters: { Skagen: true, Rønne: false, Lyngby: true },
    });
    const text: string = fixture.nativeElement.textContent;
    expect(text).toContain('Skagen');
    expect(text).toContain('Lyngby');
    expect(text).not.toContain('Rønne');
  });

  it('renders preamble and text via plainTextToHtml in a monospace container', () => {
    const fixture = setup({ ...base, preamble: 'PREAMBLE TEXT', text: 'BODY' });
    const mono: HTMLElement | null = fixture.nativeElement.querySelector('.font-mono');
    expect(mono).not.toBeNull();
    expect(mono!.textContent).toContain('PREAMBLE');
    expect(mono!.textContent).toContain('BODY');
  });

  it('omits the body container when both preamble and text are absent', () => {
    const fixture = setup({ ...base });
    expect(fixture.nativeElement.querySelector('.font-mono')).toBeNull();
  });
});
