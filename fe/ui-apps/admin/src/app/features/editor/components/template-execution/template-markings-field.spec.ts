import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';

import { provideTestTransloco } from '../../../../shared/testing/transloco-testing';
import { type Marking, TemplateMarkingsField } from './template-markings-field';

interface Handle {
  setMode(mode: string): void;
  addMarking(): void;
  deleteMarking(index: number): void;
  isLightType(marking: Marking): boolean;
}

function setup() {
  TestBed.configureTestingModule({ providers: [provideTestTransloco()] });
  // Render nothing — this suite drives the marking-list logic, not the (child-heavy) template.
  TestBed.overrideComponent(TemplateMarkingsField, { set: { template: '' } });
  const fixture = TestBed.createComponent(TemplateMarkingsField);
  return { fixture, handle: fixture.componentInstance as unknown as Handle };
}

describe('TemplateMarkingsField', () => {
  it('switching to a marked mode resets to a single blank row; unmarked clears it', () => {
    const { fixture, handle } = setup();
    const modeEmit = vi.fn();
    const marksEmit = vi.fn();
    fixture.componentInstance.markingTypeChange.subscribe(modeEmit);
    fixture.componentInstance.markingsChange.subscribe(marksEmit);

    handle.setMode('buoy');
    expect(modeEmit).toHaveBeenLastCalledWith('buoy');
    expect(marksEmit).toHaveBeenLastCalledWith([{}]);

    handle.setMode('unmarked');
    expect(marksEmit).toHaveBeenLastCalledWith([]);
  });

  it('adding a marking appends a blank row to the current list', () => {
    const { fixture, handle } = setup();
    fixture.componentRef.setInput('markings', [{ distance: 1 }]);
    const marksEmit = vi.fn();
    fixture.componentInstance.markingsChange.subscribe(marksEmit);

    handle.addMarking();

    expect(marksEmit).toHaveBeenCalledWith([{ distance: 1 }, {}]);
  });

  it('flags a light AtoN type by its key', () => {
    const { handle } = setup();
    expect(handle.isLightType({ type: { key: 'light_buoy_red', descs: [] } })).toBe(true);
    expect(handle.isLightType({ type: { key: 'buoy_green', descs: [] } })).toBe(false);
    expect(handle.isLightType({})).toBe(false);
  });
});
