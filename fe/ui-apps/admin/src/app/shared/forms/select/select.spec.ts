import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { disabled, form, FormField } from '@angular/forms/signals';
import { afterEach, describe, expect, it } from 'vitest';

import { Select } from './select';

interface Item {
  readonly id: number;
  readonly name: string;
}
const A: Item = { id: 1, name: 'Alpha' };
const B: Item = { id: 2, name: 'Bravo' };
const C: Item = { id: 3, name: 'Charlie' };

describe('Select (app-select primitive)', () => {
  const q = (sel: string) => document.querySelector(sel);
  const qa = (sel: string) => Array.from(document.querySelectorAll(sel));
  const trigger = (host: HTMLElement) => host.querySelector('.sel-trigger') as HTMLButtonElement;
  const key = (el: HTMLElement, k: string) =>
    el.dispatchEvent(new KeyboardEvent('keydown', { key: k, bubbles: true }));

  afterEach(() => {
    document.querySelectorAll('.cdk-overlay-container').forEach((el) => el.replaceChildren());
  });

  // --- Scalar value via optionValue (the common {value,label} case) ---
  @Component({
    imports: [Select],
    template: `<app-select
      [options]="options()"
      optionLabel="name"
      optionValue="id"
      placeholder="Pick one"
      [showClear]="showClear()"
      [value]="value()"
      (valueChange)="onChange($any($event))"
      (touch)="touchedCount.set(touchedCount() + 1)"
    />`,
  })
  class ScalarHost {
    readonly options = signal<Item[]>([A, B, C]);
    readonly value = signal<number | null>(null);
    readonly showClear = signal(true);
    readonly touchedCount = signal(0);
    readonly changes: (number | null)[] = [];
    onChange(v: number | null): void {
      this.changes.push(v);
      this.value.set(v);
    }
  }

  function scalarSetup() {
    TestBed.configureTestingModule({});
    const fixture = TestBed.createComponent(ScalarHost);
    fixture.autoDetectChanges();
    return fixture;
  }

  it('does not portal a panel until opened', () => {
    scalarSetup();
    expect(q('.sel-panel')).toBeNull();
  });

  it('shows the placeholder when there is no value', () => {
    const fixture = scalarSetup();
    expect(fixture.nativeElement.querySelector('.sel-placeholder')?.textContent).toContain(
      'Pick one',
    );
  });

  it('opens the listbox on click and renders one option per item', async () => {
    const fixture = scalarSetup();
    trigger(fixture.nativeElement).click();
    await fixture.whenStable();
    expect(q('.sel-panel')).not.toBeNull();
    expect(qa('.sel-panel .sel-option').length).toBe(3);
  });

  it('picking a row (mousedown) emits the option value and closes', async () => {
    const fixture = scalarSetup();
    trigger(fixture.nativeElement).click();
    await fixture.whenStable();

    qa('.sel-panel .sel-option')[1].dispatchEvent(
      new MouseEvent('mousedown', { bubbles: true, cancelable: true }),
    );
    await fixture.whenStable();

    expect(fixture.componentInstance.changes).toEqual([2]);
    expect(q('.sel-panel')).toBeNull();
    // Trigger reflects the committed label.
    expect(fixture.nativeElement.querySelector('.sel-value')?.textContent).toContain('Bravo');
  });

  it('ArrowDown opens with the first row active, Enter commits it', async () => {
    const fixture = scalarSetup();
    const btn = trigger(fixture.nativeElement);
    key(btn, 'ArrowDown'); // opens + pre-highlights the first row
    await fixture.whenStable();
    key(btn, 'Enter'); // commits the active (first) row
    await fixture.whenStable();

    expect(fixture.componentInstance.changes.at(-1)).toBe(1);
    expect(q('.sel-panel')).toBeNull();
  });

  it('marks the committed option aria-selected', async () => {
    const fixture = scalarSetup();
    fixture.componentInstance.value.set(3);
    await fixture.whenStable();
    trigger(fixture.nativeElement).click();
    await fixture.whenStable();

    const selected = qa('.sel-panel .sel-option').filter(
      (el) => el.getAttribute('aria-selected') === 'true',
    );
    expect(selected.length).toBe(1);
    expect(selected[0].textContent).toContain('Charlie');
  });

  it('the clear affordance resets the value to null', async () => {
    const fixture = scalarSetup();
    fixture.componentInstance.value.set(2);
    await fixture.whenStable();

    const clear = fixture.nativeElement.querySelector('.sel-clear') as HTMLElement;
    expect(clear).not.toBeNull();
    clear.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, cancelable: true }));
    await fixture.whenStable();

    expect(fixture.componentInstance.changes.at(-1)).toBeNull();
  });

  it('Escape closes the panel without committing', async () => {
    const fixture = scalarSetup();
    const btn = trigger(fixture.nativeElement);
    btn.click();
    await fixture.whenStable();
    expect(q('.sel-panel')).not.toBeNull();

    key(btn, 'Escape');
    await fixture.whenStable();
    expect(q('.sel-panel')).toBeNull();
    expect(fixture.componentInstance.changes.length).toBe(0);
  });

  // --- Whole-object value (no optionValue) ---
  it('binds the whole option object when optionValue is absent', async () => {
    @Component({
      imports: [Select],
      template: `<app-select
        [options]="options()"
        optionLabel="name"
        [value]="value()"
        (valueChange)="onChange($any($event))"
      />`,
    })
    class ObjectHost {
      readonly options = signal<Item[]>([A, B]);
      readonly value = signal<Item | null>(null);
      readonly picked = signal<Item | null>(null);
      onChange(v: Item | null): void {
        this.picked.set(v);
        this.value.set(v);
      }
    }
    TestBed.configureTestingModule({});
    const fixture = TestBed.createComponent(ObjectHost);
    fixture.autoDetectChanges();

    trigger(fixture.nativeElement).click();
    await fixture.whenStable();
    qa('.sel-panel .sel-option')[0].dispatchEvent(
      new MouseEvent('mousedown', { bubbles: true, cancelable: true }),
    );
    await fixture.whenStable();

    expect(fixture.componentInstance.picked()).toBe(A);
    expect(fixture.nativeElement.querySelector('.sel-value')?.textContent).toContain('Alpha');
  });

  // --- Signal Forms (FormValueControl via [formField]) ---
  it('works as a FormValueControl bound via [formField]', async () => {
    @Component({
      imports: [Select, FormField],
      template: `<app-select
        [formField]="model.choice"
        [options]="options()"
        optionLabel="name"
        optionValue="id"
      />`,
    })
    class FormHost {
      readonly options = signal<Item[]>([A, B, C]);
      private readonly data = signal<{ choice: number | null }>({ choice: 2 });
      readonly model = form(this.data);
    }
    TestBed.configureTestingModule({});
    const fixture = TestBed.createComponent(FormHost);
    fixture.autoDetectChanges();
    await fixture.whenStable();

    // The field value (2) -> shows Bravo.
    expect(fixture.nativeElement.querySelector('.sel-value')?.textContent).toContain('Bravo');

    trigger(fixture.nativeElement).click();
    await fixture.whenStable();
    qa('.sel-panel .sel-option')[2].dispatchEvent(
      new MouseEvent('mousedown', { bubbles: true, cancelable: true }),
    );
    await fixture.whenStable();

    // Picking Charlie flows back into the form field.
    expect(fixture.componentInstance.model.choice().value()).toBe(3);
  });

  it('reflects the form-driven disabled state', async () => {
    @Component({
      imports: [Select, FormField],
      template: `<app-select
        [formField]="model.choice"
        [options]="options()"
        optionLabel="name"
      />`,
    })
    class DisabledHost {
      readonly options = signal<Item[]>([A, B]);
      private readonly data = signal<{ choice: Item | null }>({ choice: A });
      readonly model = form(this.data, (path) => disabled(path.choice, { when: () => true }));
    }
    TestBed.configureTestingModule({});
    const fixture = TestBed.createComponent(DisabledHost);
    fixture.autoDetectChanges();
    await fixture.whenStable();

    const btn = fixture.nativeElement.querySelector('.sel-trigger') as HTMLButtonElement;
    expect(btn.disabled).toBe(true);
    btn.click();
    await fixture.whenStable();
    expect(q('.sel-panel')).toBeNull();
  });

  // --- Projected rich-row template ---
  it('renders a projected #option template for rows and the trigger', async () => {
    @Component({
      imports: [Select],
      template: `<app-select [options]="options()" optionValue="id" [value]="value()">
        <ng-template #option let-opt>
          <span class="custom-row">row:{{ opt.name }}</span>
        </ng-template>
      </app-select>`,
    })
    class TplHost {
      readonly options = signal<Item[]>([A, B]);
      readonly value = signal<number | null>(1);
    }
    TestBed.configureTestingModule({});
    const fixture = TestBed.createComponent(TplHost);
    fixture.autoDetectChanges();
    await fixture.whenStable();

    // Trigger reuses #option for the selected display.
    expect(fixture.nativeElement.querySelector('.sel-value .custom-row')?.textContent).toContain(
      'row:Alpha',
    );

    (fixture.nativeElement.querySelector('.sel-trigger') as HTMLElement).click();
    await fixture.whenStable();
    const rows = qa('.sel-panel .sel-option .custom-row');
    expect(rows.length).toBe(2);
    expect(rows[1].textContent).toContain('row:Bravo');
  });

  // --- ARIA state on the combobox trigger ---
  it('flips aria-expanded false -> true -> false across open/close', async () => {
    const fixture = scalarSetup();
    const btn = trigger(fixture.nativeElement);
    expect(btn.getAttribute('aria-expanded')).toBe('false');

    btn.click();
    await fixture.whenStable();
    expect(btn.getAttribute('aria-expanded')).toBe('true');

    key(btn, 'Escape');
    await fixture.whenStable();
    expect(btn.getAttribute('aria-expanded')).toBe('false');
  });

  it('exposes aria-controls only while open, referencing the listbox id', async () => {
    const fixture = scalarSetup();
    const btn = trigger(fixture.nativeElement);
    expect(btn.getAttribute('aria-controls')).toBeNull();

    btn.click();
    await fixture.whenStable();
    const listboxId = q('.sel-panel')?.getAttribute('id');
    expect(listboxId).toBeTruthy();
    expect(btn.getAttribute('aria-controls')).toBe(listboxId);

    key(btn, 'Escape');
    await fixture.whenStable();
    expect(btn.getAttribute('aria-controls')).toBeNull();
  });

  it('tracks aria-activedescendant as ArrowDown/ArrowUp move the highlight', async () => {
    const fixture = scalarSetup();
    const btn = trigger(fixture.nativeElement);
    key(btn, 'ArrowDown'); // opens + pre-highlights the first row
    await fixture.whenStable();

    const opts = qa('.sel-panel .sel-option');
    expect(btn.getAttribute('aria-activedescendant')).toBe(opts[0].id);

    key(btn, 'ArrowDown');
    await fixture.whenStable();
    expect(btn.getAttribute('aria-activedescendant')).toBe(opts[1].id);

    key(btn, 'ArrowUp');
    await fixture.whenStable();
    expect(btn.getAttribute('aria-activedescendant')).toBe(opts[0].id);
  });

  it('Home jumps to the first option, End to the last', async () => {
    const fixture = scalarSetup();
    const btn = trigger(fixture.nativeElement);
    btn.click();
    await fixture.whenStable();
    const opts = qa('.sel-panel .sel-option');

    key(btn, 'End');
    await fixture.whenStable();
    expect(btn.getAttribute('aria-activedescendant')).toBe(opts[2].id);

    key(btn, 'Home');
    await fixture.whenStable();
    expect(btn.getAttribute('aria-activedescendant')).toBe(opts[0].id);
  });

  it('Space commits the active option', async () => {
    const fixture = scalarSetup();
    const btn = trigger(fixture.nativeElement);
    key(btn, 'ArrowDown'); // opens + pre-highlights the first row (Alpha)
    await fixture.whenStable();
    key(btn, ' '); // commits the active (first) row
    await fixture.whenStable();

    expect(fixture.componentInstance.changes.at(-1)).toBe(1);
    expect(q('.sel-panel')).toBeNull();
  });

  it('Tab closes the panel without committing', async () => {
    const fixture = scalarSetup();
    const btn = trigger(fixture.nativeElement);
    key(btn, 'ArrowDown');
    await fixture.whenStable();
    expect(q('.sel-panel')).not.toBeNull();

    key(btn, 'Tab');
    await fixture.whenStable();
    expect(q('.sel-panel')).toBeNull();
    expect(fixture.componentInstance.changes.length).toBe(0);
  });

  it('typeahead: a printable key makes the first matching option active', async () => {
    const fixture = scalarSetup();
    const btn = trigger(fixture.nativeElement);
    key(btn, 'c'); // opens + queues the typeahead buffer
    await fixture.whenStable();

    // The manager's typeahead is debounced (200ms) — wait past it, then flush CD.
    await new Promise((resolve) => setTimeout(resolve, 300));
    await fixture.whenStable();

    const active = q('.sel-panel .sel-option.sel-option-active');
    expect(active?.textContent).toContain('Charlie');
    expect(btn.getAttribute('aria-activedescendant')).toBe(active?.getAttribute('id'));
  });

  // --- Per-option disabled via optionDisabled ---
  it('per-option disabled: renders aria-disabled, is skipped by nav, and ignores mousedown', async () => {
    @Component({
      imports: [Select],
      template: `<app-select
        [options]="options()"
        optionLabel="name"
        optionValue="id"
        optionDisabled="off"
        [value]="value()"
        (valueChange)="onChange($any($event))"
      />`,
    })
    class DisabledOptionHost {
      readonly options = signal([A, { id: 2, name: 'Bravo', off: true }, C]);
      readonly value = signal<number | null>(null);
      readonly changes: (number | null)[] = [];
      onChange(v: number | null): void {
        this.changes.push(v);
        this.value.set(v);
      }
    }
    TestBed.configureTestingModule({});
    const fixture = TestBed.createComponent(DisabledOptionHost);
    fixture.autoDetectChanges();

    const btn = trigger(fixture.nativeElement);
    key(btn, 'ArrowDown'); // opens + pre-highlights the first (enabled) row
    await fixture.whenStable();

    const opts = qa('.sel-panel .sel-option');
    // The disabled row advertises aria-disabled; enabled rows do not.
    expect(opts[1].getAttribute('aria-disabled')).toBe('true');
    expect(opts[0].getAttribute('aria-disabled')).toBeNull();

    // ArrowDown from Alpha skips the disabled Bravo and lands on Charlie.
    key(btn, 'ArrowDown');
    await fixture.whenStable();
    expect(btn.getAttribute('aria-activedescendant')).toBe(opts[2].id);
    expect(btn.getAttribute('aria-activedescendant')).not.toBe(opts[1].id);

    // A mousedown on the disabled row commits nothing and keeps the panel open.
    opts[1].dispatchEvent(new MouseEvent('mousedown', { bubbles: true, cancelable: true }));
    await fixture.whenStable();
    expect(fixture.componentInstance.changes.length).toBe(0);
    expect(q('.sel-panel')).not.toBeNull();
  });

  // --- Object options without optionLabel: matched by identity ---
  it('marks only the held option when the options are objects without optionLabel', async () => {
    interface Pair {
      readonly from: string;
      readonly to: string;
    }
    const pairs: Pair[] = [
      { from: 'a', to: 'b' },
      { from: 'b', to: 'c' },
      { from: 'c', to: 'd' },
    ];
    @Component({
      imports: [Select],
      template: `<app-select [options]="options" [value]="value()">
        <ng-template #option let-opt>{{ opt.from }}-{{ opt.to }}</ng-template>
      </app-select>`,
    })
    class PairHost {
      readonly options = pairs;
      readonly value = signal<Pair | null>(pairs[1]);
    }
    TestBed.configureTestingModule({});
    const fixture = TestBed.createComponent(PairHost);
    fixture.autoDetectChanges();
    await fixture.whenStable();

    // The trigger shows the held option, not the first.
    expect(fixture.nativeElement.querySelector('.sel-value')?.textContent?.trim()).toBe('b-c');

    trigger(fixture.nativeElement).click();
    await fixture.whenStable();
    const selected = qa('.sel-panel .sel-option').filter(
      (el) => el.getAttribute('aria-selected') === 'true',
    );
    expect(selected.map((el) => el.textContent?.trim())).toEqual(['b-c']);
    // The panel opens on the held row.
    expect(q('.sel-panel .sel-option.sel-option-active')?.textContent?.trim()).toBe('b-c');
  });

  it('still matches primitive options to the value by their label', async () => {
    @Component({
      imports: [Select],
      template: `<app-select [options]="options" [value]="value()" />`,
    })
    class NumberHost {
      readonly options = [1, 2, 3];
      // A number option and a string value: matched by the label both give.
      readonly value = signal<unknown>('2');
    }
    TestBed.configureTestingModule({});
    const fixture = TestBed.createComponent(NumberHost);
    fixture.autoDetectChanges();
    await fixture.whenStable();

    expect(fixture.nativeElement.querySelector('.sel-value')?.textContent?.trim()).toBe('2');
    trigger(fixture.nativeElement).click();
    await fixture.whenStable();
    const selected = qa('.sel-panel .sel-option').filter(
      (el) => el.getAttribute('aria-selected') === 'true',
    );
    expect(selected.map((el) => el.textContent?.trim())).toEqual(['2']);
  });

  // --- Action mode: clearOnPick + (picked) ---
  describe('clearOnPick (an action trigger)', () => {
    @Component({
      imports: [Select],
      template: `<app-select
        [options]="options()"
        [clearOnPick]="true"
        placeholder="Do something"
        (picked)="picks.push($any($event))"
        (valueChange)="changes.push($event)"
      >
        <ng-template #option let-opt>{{ opt.name }}</ng-template>
      </app-select>`,
    })
    class ActionHost {
      readonly options = signal<Item[]>([A, B, C]);
      readonly picks: Item[] = [];
      readonly changes: unknown[] = [];
    }

    function actionSetup() {
      TestBed.configureTestingModule({});
      const fixture = TestBed.createComponent(ActionHost);
      fixture.autoDetectChanges();
      return fixture;
    }

    async function pick(fixture: ReturnType<typeof actionSetup>, index: number): Promise<void> {
      trigger(fixture.nativeElement).click();
      await fixture.whenStable();
      qa('.sel-panel .sel-option')[index].dispatchEvent(
        new MouseEvent('mousedown', { bubbles: true, cancelable: true }),
      );
      await fixture.whenStable();
    }

    it('emits picked on every pick, also of the same option twice', async () => {
      const fixture = actionSetup();
      await pick(fixture, 1);
      await pick(fixture, 1);

      expect(fixture.componentInstance.picks).toEqual([B, B]);
      expect(q('.sel-panel')).toBeNull();
    });

    it('keeps no value: the trigger shows the placeholder and no option is selected', async () => {
      const fixture = actionSetup();
      await pick(fixture, 2);

      expect(fixture.componentInstance.changes).toEqual([]);
      expect(fixture.nativeElement.querySelector('.sel-placeholder')?.textContent).toContain(
        'Do something',
      );
      trigger(fixture.nativeElement).click();
      await fixture.whenStable();
      expect(
        qa('.sel-panel .sel-option').filter((el) => el.getAttribute('aria-selected') === 'true'),
      ).toEqual([]);
    });

    it('a keyboard pick emits picked too', async () => {
      const fixture = actionSetup();
      const btn = trigger(fixture.nativeElement);
      key(btn, 'ArrowDown');
      await fixture.whenStable();
      key(btn, 'Enter');
      await fixture.whenStable();
      key(btn, 'ArrowDown');
      await fixture.whenStable();
      key(btn, 'Enter');
      await fixture.whenStable();

      expect(fixture.componentInstance.picks).toEqual([A, A]);
    });
  });

  it('re-renders the options when the options signal changes while the panel is open', async () => {
    const fixture = scalarSetup();
    trigger(fixture.nativeElement).click();
    await fixture.whenStable();
    expect(qa('.sel-panel .sel-option').length).toBe(3);

    fixture.componentInstance.options.set([A]);
    await fixture.whenStable();
    expect(qa('.sel-panel .sel-option').length).toBe(1);
    expect(q('.sel-panel .sel-option')?.textContent).toContain('Alpha');
  });
});
