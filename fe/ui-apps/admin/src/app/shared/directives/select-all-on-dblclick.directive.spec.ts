import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { describe, expect, it, vi } from 'vitest';

import { SelectAllOnDblclickDirective } from './select-all-on-dblclick.directive';

@Component({
  standalone: true,
  imports: [SelectAllOnDblclickDirective],
  template: `<span appSelectAllOnDblclick>Local Warning-003-26</span>`,
})
class HostComponent {}

function dblclickTarget() {
  const fixture = TestBed.createComponent(HostComponent);
  fixture.detectChanges();
  return fixture.debugElement.query(By.directive(SelectAllOnDblclickDirective))
    .nativeElement as HTMLElement;
}

describe('SelectAllOnDblclickDirective', () => {
  it('selects the host element’s entire text on double-click', () => {
    const removeAllRanges = vi.fn();
    const addRange = vi.fn();
    vi.spyOn(window, 'getSelection').mockReturnValue({
      removeAllRanges,
      addRange,
    } as unknown as Selection);

    const span = dblclickTarget();
    span.dispatchEvent(new MouseEvent('dblclick'));

    expect(removeAllRanges).toHaveBeenCalledOnce();
    const range = addRange.mock.calls[0][0] as Range;
    expect(range.toString()).toBe('Local Warning-003-26');
  });

  it('no-ops when the browser reports no selection', () => {
    vi.spyOn(window, 'getSelection').mockReturnValue(null);
    const span = dblclickTarget();
    expect(() => span.dispatchEvent(new MouseEvent('dblclick'))).not.toThrow();
  });
});
