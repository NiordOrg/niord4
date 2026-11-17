import { describe, expect, it } from 'vitest';

import { allowedClasses, MESSAGE_HTML_CLASSES } from './message-html-classes';

describe('message-html-classes', () => {
  it('keeps every class of the vocabulary', () => {
    for (const name of MESSAGE_HTML_CLASSES) {
      expect(allowedClasses(name)).toBe(name);
    }
  });

  it('drops the utilities that could lay a message over the page, and keeps the rest', () => {
    expect(allowedClasses('fixed inset-0 z-50 no-border bg-surface-0 condensed')).toBe(
      'no-border condensed',
    );
  });

  it('answers null when nothing is left, so no empty class is written back', () => {
    expect(allowedClasses('fixed inset-0 z-40')).toBeNull();
    expect(allowedClasses('')).toBeNull();
    expect(allowedClasses('   ')).toBeNull();
    expect(allowedClasses(null)).toBeNull();
    expect(allowedClasses(undefined)).toBeNull();
  });

  it('splits on any ASCII whitespace and keeps each class once', () => {
    expect(allowedClasses('\tno-border\n\ncondensed  no-border\r\f')).toBe('no-border condensed');
  });

  it('matches whole tokens only, and is case-sensitive as the stylesheet is', () => {
    expect(allowedClasses('no-border-x xno-wrap NO-WRAP')).toBeNull();
  });
});
