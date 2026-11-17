import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NO_ERRORS_SCHEMA } from '@angular/core';
import { MessageFilterComponent } from './message-filter.component';
import { Area } from '../../../../core/models/message.model';
import { getTranslocoTestingModule, provideTestNiordConfig } from '../../../../testing/test-config';
import { FakeResizeObserver } from '../../../../testing/fake-resize-observer';

const ANCHORLAND: Area = { id: 453, descs: [{ lang: 'en', name: 'Anchorland' }] };
const BUOYLAND: Area = { id: 454, descs: [{ lang: 'en', name: 'Buoyland' }] };
const SOUND: Area = { id: 11, parent: ANCHORLAND, descs: [{ lang: 'en', name: 'The Sound' }] };

describe('MessageFilterComponent', () => {
  let fixture: ComponentFixture<MessageFilterComponent>;
  let component: MessageFilterComponent;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [MessageFilterComponent, getTranslocoTestingModule()],
      providers: [provideTestNiordConfig()],
      schemas: [NO_ERRORS_SCHEMA],
    }).compileComponents();
    fixture = TestBed.createComponent(MessageFilterComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  /** The labels of the chips the bar renders, in order. */
  function chipLabels(): string[] {
    return [...(fixture.nativeElement as HTMLElement).querySelectorAll('button.chip')].map((chip) =>
      chip.textContent!.trim(),
    );
  }

  /** The chip with this label. */
  function chip(label: string): HTMLButtonElement {
    const found = [
      ...(fixture.nativeElement as HTMLElement).querySelectorAll<HTMLButtonElement>('button.chip'),
    ].find((button) => button.textContent!.trim() === label);
    if (!found) throw new Error(`no chip "${label}"`);
    return found;
  }

  it('asks for the main types with the pressed one turned off', () => {
    const spy = vi.fn();
    component.mainTypesChange.subscribe(spy);
    expect(chip('Navigational Warnings').getAttribute('aria-pressed')).toBe('true');

    chip('Navigational Warnings').click();
    expect(spy).toHaveBeenCalledWith({ NW: false, NM: true });
  });

  // The public API reads no main type as both, so turning the last one off listed every message
  // under two chips that read as off.
  it('does not let the one main type left on be turned off, and says so', () => {
    fixture.componentRef.setInput('mainTypes', { NW: false, NM: true });
    fixture.detectChanges();
    const spy = vi.fn();
    component.mainTypesChange.subscribe(spy);

    expect(chip('Notices to Mariners').getAttribute('aria-pressed')).toBe('true');
    expect(chip('Notices to Mariners').getAttribute('aria-disabled')).toBe('true');
    chip('Notices to Mariners').click();
    expect(spy).not.toHaveBeenCalled();

    // The one that is off can still be turned on.
    expect(chip('Navigational Warnings').hasAttribute('aria-disabled')).toBe(false);
    chip('Navigational Warnings').click();
    expect(spy).toHaveBeenCalledWith({ NW: true, NM: true });
  });

  it('marks neither main type as locked while both are on', () => {
    expect(chip('Navigational Warnings').hasAttribute('aria-disabled')).toBe(false);
    expect(chip('Notices to Mariners').hasAttribute('aria-disabled')).toBe(false);
  });

  function showCountryWithOneSubArea(): void {
    fixture.componentRef.setInput('areaRoots', [ANCHORLAND, BUOYLAND]);
    fixture.componentRef.setInput('rootArea', ANCHORLAND);
    fixture.componentRef.setInput('subAreas', [SOUND]);
  }

  it('offers the whole filter for the current list', () => {
    showCountryWithOneSubArea();
    fixture.detectChanges();
    expect(chipLabels()).toEqual([
      'Navigational Warnings',
      'Notices to Mariners',
      'Show Active Now',
      'Print',
      'Anchorland',
      'Buoyland',
      'The Sound',
    ]);
  });

  // A publication is read as the list it was published with: the main types, active-now and the
  // root area would change nothing on screen, so they are not offered at all.
  it('offers only printing and the sub-areas while a publication is browsed', () => {
    showCountryWithOneSubArea();
    fixture.componentRef.setInput('browsingPublication', true);
    fixture.detectChanges();
    expect(chipLabels()).toEqual(['Print', 'The Sound']);
  });

  it('draws no empty area row for a publication whose messages fall in no sub-area', () => {
    showCountryWithOneSubArea();
    fixture.componentRef.setInput('subAreas', []);
    fixture.componentRef.setInput('browsingPublication', true);
    fixture.detectChanges();
    const rows = (fixture.nativeElement as HTMLElement).querySelectorAll('[class~="border-b"]');
    expect(rows).toHaveLength(1);
  });

  it('asks for the printed list when the print chip is pressed', () => {
    const spy = vi.fn();
    component.printRequested.subscribe(spy);
    expect(chip('Print').disabled).toBe(false);

    chip('Print').click();
    expect(spy).toHaveBeenCalledOnce();
  });

  // A list that is loading, empty or failed has no series to name, and a sheet asked for without
  // one carries every published series.
  it('holds the print chip inert while there is no list to print', () => {
    const spy = vi.fn();
    component.printRequested.subscribe(spy);
    fixture.componentRef.setInput('canPrint', false);
    fixture.detectChanges();

    expect(chip('Print').disabled).toBe(true);
    chip('Print').click();
    expect(spy).not.toHaveBeenCalled();

    fixture.componentRef.setInput('canPrint', true);
    fixture.detectChanges();
    expect(chip('Print').disabled).toBe(false);
  });

  // The bar takes its own room in the flow and is pinned by the stylesheet, which lets it scroll
  // away on a short screen; a fixed bar covers the list at every size. The layout itself is
  // checked in a browser by npm run layout:info.
  it('is pinned by the stylesheet, not fixed over the list', () => {
    const host = fixture.nativeElement as HTMLElement;
    const bar = host.firstElementChild as HTMLElement;
    const classes = bar.className.split(/\s+/);

    expect(host.classList).toContain('contents');
    expect(classes).toContain('filter-bar');
    expect(classes).not.toContain('fixed');
  });

  it('publishes its height for the map view and the scroll padding', () => {
    vi.stubGlobal('ResizeObserver', FakeResizeObserver);
    const root = document.documentElement;
    try {
      const measured = TestBed.createComponent(MessageFilterComponent);
      TestBed.tick();
      // Five rows of chips, as on a 320 px screen.
      FakeResizeObserver.last!.report({ borderBoxSize: [{ blockSize: 166, inlineSize: 320 }] });
      expect(root.style.getPropertyValue('--niord-filter-bar-height')).toBe('166px');

      // The publications route has no filter bar, and nothing may be sized against the old one.
      measured.destroy();
      expect(root.style.getPropertyValue('--niord-filter-bar-height')).toBe('');
    } finally {
      vi.unstubAllGlobals();
      FakeResizeObserver.last = undefined;
      root.style.removeProperty('--niord-filter-bar-height');
    }
  });
});
