import { describe, expect, it } from 'vitest';

import { allowedDeclaration, allowedDeclarations } from './message-html-declarations';

describe('message-html-declarations', () => {
  it('rebuilds the kept declarations in the order they were written and drops the rest', () => {
    expect(
      allowedDeclarations(
        'font-family: Verdana; padding-left: 30px; color: red; TEXT-ALIGN: Center;',
      ),
    ).toBe('padding-left: 30px; text-align: center');
  });

  it('reads one property of a style attribute', () => {
    const style = 'text-align: justify; padding-left: 1.5em';
    expect(allowedDeclaration(style, 'text-align')).toBe('justify');
    expect(allowedDeclaration(style, 'padding-left')).toBe('1.5em');
  });

  it('answers the last allowed declaration of a property, as the cascade would render it', () => {
    expect(allowedDeclaration('padding-left: 10px; padding-left: 20px', 'padding-left')).toBe(
      '20px',
    );
    // An indent beyond the cap is dropped, so the one before it is what the message shows.
    expect(allowedDeclaration('padding-left: 20px; padding-left: 400px', 'padding-left')).toBe(
      '20px',
    );
  });

  it('answers nothing for a property the style does not carry in an allowed form', () => {
    expect(allowedDeclaration('', 'padding-left')).toBeNull();
    expect(allowedDeclaration('padding: 0 0 0 30px', 'padding-left')).toBeNull();
    expect(allowedDeclaration('padding-left: calc(10px + 1em)', 'padding-left')).toBeNull();
    expect(allowedDeclaration('padding-left: 161px', 'padding-left')).toBeNull();
    expect(allowedDeclaration('padding-left: -10px', 'padding-left')).toBeNull();
    expect(allowedDeclaration('text-align: start', 'text-align')).toBeNull();
  });

  it('holds every unit to the same 160-pixel cap', () => {
    expect(allowedDeclaration('padding-left: 160px', 'padding-left')).toBe('160px');
    expect(allowedDeclaration('padding-left: 10rem', 'padding-left')).toBe('10rem');
    expect(allowedDeclaration('padding-left: 120pt', 'padding-left')).toBe('120pt');
    expect(allowedDeclaration('padding-left: 121pt', 'padding-left')).toBeNull();
    expect(allowedDeclaration('padding-left: 5cm', 'padding-left')).toBeNull();
  });
});
