import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it } from 'vitest';

import { Autocomplete } from './autocomplete';

interface Item {
  readonly id: number;
  readonly name: string;
}
const A: Item = { id: 1, name: 'Alpha' };
const B: Item = { id: 2, name: 'Bravo' };

@Component({
  imports: [Autocomplete],
  template: `<app-autocomplete
    [value]="value()"
    [suggestions]="suggestions()"
    [itemLabel]="labelOf"
    [multiple]="multiple()"
    [fetchError]="fetchError()"
    [leadingIcon]="leadingIcon()"
    [panelWidth]="panelWidth()"
    [loading]="loading()"
    emptyMessage="No results found"
    errorMessage="Error fetching data"
    clearLabel="Clear"
    removeLabel="Remove"
    (query)="lastQuery.set($event)"
    (selectionChange)="onSelectionChange($event)"
    (clear)="cleared.set(cleared() + 1)"
    (touched)="touched.set(touched() + 1)"
  />`,
})
class Host {
  readonly value = signal<Item[]>([]);
  readonly suggestions = signal<Item[]>([]);
  readonly multiple = signal(true);
  readonly fetchError = signal(false);
  readonly leadingIcon = signal<string | undefined>(undefined);
  readonly panelWidth = signal<'control' | 'content'>('control');
  readonly loading = signal(false);
  readonly labelOf = (item: Item): string => item.name;
  readonly lastQuery = signal('');
  readonly cleared = signal(0);
  readonly touched = signal(0);
  onSelectionChange(items: readonly Item[]): void {
    this.value.set([...items]);
  }
}

describe('Autocomplete (app-autocomplete primitive)', () => {
  function setup() {
    TestBed.configureTestingModule({});
    const fixture = TestBed.createComponent(Host);
    fixture.autoDetectChanges();
    return fixture;
  }

  afterEach(() => {
    document.querySelectorAll('.cdk-overlay-container').forEach((el) => el.replaceChildren());
  });

  const q = (sel: string) => document.querySelector(sel);
  const input = (host: HTMLElement) => host.querySelector('.ac-input') as HTMLInputElement;
  const type = async (host: HTMLElement, text: string) => {
    const el = input(host);
    el.value = text;
    el.dispatchEvent(new Event('input'));
  };

  it('does not portal a panel until opened', () => {
    setup();
    expect(q('.ac-panel')).toBeNull();
  });

  it('renders a removable chip per value item and hides the placeholder', async () => {
    const fixture = setup();
    fixture.componentInstance.value.set([A, B]);
    await fixture.whenStable();

    const chips = fixture.nativeElement.querySelectorAll('.ac-chip');
    expect(chips.length).toBe(2);
    expect(chips[0].textContent).toContain('Alpha');
    expect(chips[0].querySelector('.ac-chip-remove')).not.toBeNull();
  });

  it('emits (query) and opens the listbox on input', async () => {
    const fixture = setup();
    fixture.componentInstance.suggestions.set([A, B]);
    await type(fixture.nativeElement, 'al');
    await fixture.whenStable();

    expect(fixture.componentInstance.lastQuery()).toBe('al');
    expect(q('.ac-panel')).not.toBeNull();
    expect(document.querySelectorAll('.ac-panel .ac-option').length).toBe(2);
  });

  it('picking a row (mousedown) emits the extended value array', async () => {
    const fixture = setup();
    fixture.componentInstance.suggestions.set([A, B]);
    await type(fixture.nativeElement, 'a');
    await fixture.whenStable();

    const options = document.querySelectorAll('.ac-panel .ac-option');
    expect(options.length).toBe(2);
    options[1].dispatchEvent(new MouseEvent('mousedown', { bubbles: true, cancelable: true }));
    await fixture.whenStable();

    expect(fixture.componentInstance.value().map((i) => i.id)).toEqual([2]);
    // Panel closes after a commit.
    expect(q('.ac-panel')).toBeNull();
  });

  it('shows "No results found" vs "Error fetching data" per fetchError', async () => {
    const fixture = setup();
    await type(fixture.nativeElement, 'zz');
    await fixture.whenStable();
    expect(q('.ac-panel .ac-empty')?.textContent).toContain('No results found');

    fixture.componentInstance.fetchError.set(true);
    await fixture.whenStable();
    expect(q('.ac-panel .ac-empty')?.textContent).toContain('Error fetching data');
  });

  it('ArrowDown + Enter commits the active row', async () => {
    const fixture = setup();
    fixture.componentInstance.suggestions.set([A, B]);
    const el = input(fixture.nativeElement);
    await type(fixture.nativeElement, 'a');
    await fixture.whenStable();

    el.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true }));
    await fixture.whenStable();
    el.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
    await fixture.whenStable();

    expect(fixture.componentInstance.value().map((i) => i.id)).toEqual([1]);
  });

  it('Backspace in the empty box removes the last chip, the keyboard way to remove a value', async () => {
    const fixture = setup();
    fixture.componentInstance.value.set([A, B]);
    await fixture.whenStable();
    const el = input(fixture.nativeElement);

    el.dispatchEvent(new KeyboardEvent('keydown', { key: 'Backspace', bubbles: true }));
    await fixture.whenStable();
    expect(fixture.componentInstance.value().map((i) => i.id)).toEqual([1]);
    expect(fixture.nativeElement.querySelectorAll('.ac-chip').length).toBe(1);

    // With text in the box, Backspace edits the text and leaves the chips alone.
    await type(fixture.nativeElement, 'b');
    el.dispatchEvent(new KeyboardEvent('keydown', { key: 'Backspace', bubbles: true }));
    await fixture.whenStable();
    expect(fixture.componentInstance.value().map((i) => i.id)).toEqual([1]);
  });

  it('the clear affordance emits (clear)', async () => {
    const fixture = setup();
    fixture.componentInstance.value.set([A]);
    await fixture.whenStable();

    const clear = fixture.nativeElement.querySelector('.ac-clear') as HTMLElement;
    clear.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, cancelable: true }));
    await fixture.whenStable();

    expect(fixture.componentInstance.cleared()).toBe(1);
  });

  it('renders a projected #option template for rows', async () => {
    @Component({
      imports: [Autocomplete],
      template: `<app-autocomplete [suggestions]="suggestions()" [itemLabel]="labelOf">
        <ng-template #option let-item>
          <span class="custom-row">row:{{ item.name }}</span>
        </ng-template>
      </app-autocomplete>`,
    })
    class TplHost {
      readonly suggestions = signal<Item[]>([A, B]);
      readonly labelOf = (item: Item): string => item.name;
    }
    TestBed.configureTestingModule({});
    const fixture = TestBed.createComponent(TplHost);
    fixture.autoDetectChanges();
    const el = fixture.nativeElement.querySelector('.ac-input') as HTMLInputElement;
    el.value = 'a';
    el.dispatchEvent(new Event('input'));
    await fixture.whenStable();

    const rows = document.querySelectorAll('.ac-panel .ac-option .custom-row');
    expect(rows.length).toBe(2);
    expect(rows[0].textContent).toContain('row:Alpha');
  });

  it('single-select replaces the value instead of accumulating', async () => {
    const fixture = setup();
    const host = fixture.componentInstance;
    host.multiple.set(false);
    host.value.set([A]);
    host.suggestions.set([A, B]);
    await fixture.whenStable();

    await type(fixture.nativeElement, 'b');
    await fixture.whenStable();
    const options = document.querySelectorAll('.ac-panel .ac-option');
    expect(options.length).toBe(2);
    options[1].dispatchEvent(new MouseEvent('mousedown', { bubbles: true, cancelable: true }));
    await fixture.whenStable();

    // Bravo replaces Alpha (single-select), never accumulates.
    expect(host.value().map((i) => i.id)).toEqual([2]);
  });

  it('Escape closes the listbox without committing or clearing the typed text', async () => {
    const fixture = setup();
    fixture.componentInstance.suggestions.set([A, B]);
    await type(fixture.nativeElement, 'al');
    await fixture.whenStable();
    expect(q('.ac-panel')).not.toBeNull();

    const el = input(fixture.nativeElement);
    el.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    await fixture.whenStable();

    // Panel closes, nothing is selected, and the typed text is left intact.
    expect(q('.ac-panel')).toBeNull();
    expect(fixture.componentInstance.value().length).toBe(0);
    expect(el.value).toBe('al');
  });

  // --- ARIA state on the combobox input ---
  it('flips aria-expanded and toggles aria-controls with the panel', async () => {
    const fixture = setup();
    fixture.componentInstance.suggestions.set([A, B]);
    const el = input(fixture.nativeElement);
    expect(el.getAttribute('aria-expanded')).toBe('false');
    expect(el.getAttribute('aria-controls')).toBeNull();

    await type(fixture.nativeElement, 'a');
    await fixture.whenStable();
    expect(el.getAttribute('aria-expanded')).toBe('true');
    const listboxId = q('.ac-panel')?.getAttribute('id');
    expect(listboxId).toBeTruthy();
    expect(el.getAttribute('aria-controls')).toBe(listboxId);
  });

  it('tracks aria-activedescendant as ArrowDown/ArrowUp move the highlight', async () => {
    const fixture = setup();
    fixture.componentInstance.suggestions.set([A, B]);
    const el = input(fixture.nativeElement);
    await type(fixture.nativeElement, 'a');
    await fixture.whenStable();
    expect(el.getAttribute('aria-activedescendant')).toBeNull();

    el.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true }));
    await fixture.whenStable();
    const opts = Array.from(document.querySelectorAll('.ac-panel .ac-option'));
    expect(el.getAttribute('aria-activedescendant')).toBe(opts[0].id);

    el.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true }));
    await fixture.whenStable();
    expect(el.getAttribute('aria-activedescendant')).toBe(opts[1].id);

    el.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowUp', bubbles: true }));
    await fixture.whenStable();
    expect(el.getAttribute('aria-activedescendant')).toBe(opts[0].id);
  });

  // --- Panel width ---
  // jsdom lays nothing out, so the control reports a width of 0 and both modes
  // would look identical. Give it one, and the two modes separate.
  const withControlWidth = (fixture: ReturnType<typeof setup>, px: number) => {
    const control = fixture.nativeElement.querySelector('.ac-control') as HTMLElement;
    Object.defineProperty(control, 'offsetWidth', { value: px, configurable: true });
  };
  const pane = () => document.querySelector('.cdk-overlay-pane.ac-overlay') as HTMLElement;

  it('pins the panel to the control width by default', async () => {
    const fixture = setup();
    withControlWidth(fixture, 320);
    fixture.componentInstance.suggestions.set([A, B]);
    await type(fixture.nativeElement, 'a');
    await fixture.whenStable();

    // A fixed width, and no floor of its own — the width IS the size.
    expect(pane().style.width).toBe('320px');
    expect(pane().style.minWidth).toBe('');
    expect(pane().classList.contains('ac-overlay-content')).toBe(false);
  });

  it('panelWidth "content" makes the control width a floor and sets no fixed width', async () => {
    const fixture = setup();
    fixture.componentInstance.panelWidth.set('content');
    await fixture.whenStable();
    withControlWidth(fixture, 320);
    fixture.componentInstance.suggestions.set([A, B]);
    await type(fixture.nativeElement, 'a');
    await fixture.whenStable();

    expect(pane().style.width).toBe('');
    expect(pane().style.minWidth).toBe('320px');
    // The cap the rows are held to rides on the panel class.
    expect(pane().classList.contains('ac-overlay-content')).toBe(true);
  });

  // --- Loading ---
  it('shows the spinner instead of "No results found" while loading', async () => {
    const fixture = setup();
    fixture.componentInstance.loading.set(true);
    await type(fixture.nativeElement, 'zz');
    await fixture.whenStable();

    expect(q('.ac-panel .ac-status')).not.toBeNull();
    expect(q('.ac-panel .ac-empty')).toBeNull();
  });

  // --- Leading icon ---
  it('draws no leading icon by default', () => {
    const fixture = setup();
    expect(fixture.nativeElement.querySelector('.ac-control app-icon')).toBeNull();
  });

  it('draws the leading icon ahead of the input, decoratively', async () => {
    const fixture = setup();
    fixture.componentInstance.leadingIcon.set('lucideSearch');
    await fixture.whenStable();

    const control = fixture.nativeElement.querySelector('.ac-control') as HTMLElement;
    const icon = control.querySelector('app-icon') as HTMLElement;
    expect(icon).not.toBeNull();
    expect(icon.getAttribute('aria-hidden')).toBe('true');
    // Ahead of the text field, so the glyph reads as a label for the box.
    expect(icon.compareDocumentPosition(input(fixture.nativeElement))).toBe(
      Node.DOCUMENT_POSITION_FOLLOWING,
    );
  });
});

/**
 * Free-text single-select, as the message id field and the Add message dialog use it: the
 * consumer holds the text, sets it to what was typed on (query) and to the picked row's label on
 * (selectionChange), and binds it back as [text].
 */
@Component({
  imports: [Autocomplete],
  template: `<app-autocomplete
    [allowFreeText]="true"
    [multiple]="false"
    [text]="text()"
    [suggestions]="suggestions()"
    [itemLabel]="labelOf"
    (query)="text.set($event)"
    (selectionChange)="onPick($event)"
  />`,
})
class FreeTextHost {
  readonly text = signal('');
  readonly suggestions = signal<Item[]>([]);
  readonly labelOf = (item: Item): string => item.name;
  onPick(items: readonly Item[]): void {
    if (items[0]) this.text.set(items[0].name);
  }
}

describe('Autocomplete — free text, single select', () => {
  const FULL: Item = { id: 819, name: 'NM-819-26' };

  function setup() {
    TestBed.configureTestingModule({});
    const fixture = TestBed.createComponent(FreeTextHost);
    fixture.autoDetectChanges();
    return fixture;
  }

  afterEach(() => {
    document.querySelectorAll('.cdk-overlay-container').forEach((el) => el.replaceChildren());
  });

  const input = (host: HTMLElement) => host.querySelector('.ac-input') as HTMLInputElement;

  async function typeAndPick(fixture: ReturnType<typeof setup>, typed: string): Promise<void> {
    fixture.componentInstance.suggestions.set([FULL]);
    const el = input(fixture.nativeElement);
    el.value = typed;
    el.dispatchEvent(new Event('input'));
    await fixture.whenStable();
    document
      .querySelector('.ac-panel .ac-option')!
      .dispatchEvent(new MouseEvent('mousedown', { bubbles: true, cancelable: true }));
    await fixture.whenStable();
  }

  // The picked label equals what was typed, so the consumer's text does not change and nothing
  // resets the box from it: the pick itself must leave the text in place.
  it('keeps the box holding the id when the row picked is the id that was typed', async () => {
    const fixture = setup();
    await typeAndPick(fixture, 'NM-819-26');

    expect(fixture.componentInstance.text()).toBe('NM-819-26');
    expect(input(fixture.nativeElement).value).toBe('NM-819-26');
    expect(document.querySelector('.ac-panel')).toBeNull();
  });

  it('shows the picked row in the box when only part of it was typed', async () => {
    const fixture = setup();
    await typeAndPick(fixture, 'NM-819');

    expect(fixture.componentInstance.text()).toBe('NM-819-26');
    expect(input(fixture.nativeElement).value).toBe('NM-819-26');
  });
});

describe('Autocomplete — chip mode after a pick', () => {
  afterEach(() => {
    document.querySelectorAll('.cdk-overlay-container').forEach((el) => el.replaceChildren());
  });

  // The chip shows the pick; the box is emptied for the next search.
  it('empties the box after a pick', async () => {
    TestBed.configureTestingModule({});
    const fixture = TestBed.createComponent(Host);
    fixture.autoDetectChanges();
    fixture.componentInstance.suggestions.set([A, B]);
    const el = fixture.nativeElement.querySelector('.ac-input') as HTMLInputElement;
    el.value = 'Alpha';
    el.dispatchEvent(new Event('input'));
    await fixture.whenStable();
    document
      .querySelector('.ac-panel .ac-option')!
      .dispatchEvent(new MouseEvent('mousedown', { bubbles: true, cancelable: true }));
    await fixture.whenStable();

    expect(fixture.componentInstance.value().map((i) => i.id)).toEqual([1]);
    expect(el.value).toBe('');
  });
});
