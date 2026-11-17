import { Component, signal, Type } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Observable, of } from 'rxjs';
import { afterEach, describe, expect, it } from 'vitest';

import { EntityMultiselectControl } from './entity-multiselect-control';
import {
  EntityMultiselectField,
  type EntityMultiselectMagnifier,
} from '../entity-multiselect-field';
import { LanguageService } from '../../../core/main-shell/services/language.service';
import { provideTestTransloco } from '../../testing/transloco-testing';

interface Item {
  readonly id: number;
  readonly name: string;
  readonly detail?: string;
}
const A: Item = { id: 1, name: 'A', detail: '(root)' };
const B: Item = { id: 2, name: 'B' };

abstract class TestFieldBase extends EntityMultiselectField<Item, number> {
  override readonly optionLabel = 'name';
  protected searchEntities(): Observable<readonly Item[]> {
    return of<readonly Item[]>([]);
  }
  protected resolveByIds(): Observable<readonly Item[]> {
    return of<readonly Item[]>([]);
  }
  protected idOf(item: Item): number {
    return item.id;
  }
}

// The bare shape every plain leaf uses (charts/publications/email/tags via the
// shared entity-multiselect-field.html one-liner).
@Component({
  selector: 'app-test-plain-field',
  imports: [EntityMultiselectControl],
  template: `<app-entity-multiselect-control [field]="this" />`,
})
class PlainField extends TestFieldBase {}

// A magnifier leaf (areas/categories/tags shape).
@Component({
  selector: 'app-test-magnifier-field',
  imports: [EntityMultiselectControl],
  template: `<app-entity-multiselect-control [field]="this" />`,
})
class MagnifierField extends TestFieldBase {
  lastClickTarget: EventTarget | null = null;
  override readonly magnifier: EntityMultiselectMagnifier = {
    icon: 'lucideSearch',
    tooltipKey: 'test.magnifier.tooltip',
    buttonClass: 'text-xs',
    onClick: (event) => {
      this.lastClickTarget = event.currentTarget;
    },
  };
}

// A muted-suffix leaf (areas/categories breadcrumb, charts name shape).
@Component({
  selector: 'app-test-suffix-field',
  imports: [EntityMultiselectControl],
  template: `<app-entity-multiselect-control [field]="this" />`,
})
class SuffixField extends TestFieldBase {
  override readonly optionSuffix = (item: Item): string => item.detail ?? '';
}

// A custom-row leaf (promulgation-type shape): the template wins over optionSuffix.
@Component({
  selector: 'app-test-custom-row-field',
  imports: [EntityMultiselectControl],
  template: `
    <app-entity-multiselect-control [field]="this" [optionTemplate]="optionRow" />
    <ng-template #optionRow let-item>
      <em class="custom-row">{{ labelOf(item) }}!</em>
    </ng-template>
  `,
})
class CustomRowField extends TestFieldBase {
  override readonly optionSuffix = (item: Item): string => item.detail ?? '';
}

// A leaf whose rows are wider than the box they hang off (publications shape).
@Component({
  selector: 'app-test-content-panel-field',
  imports: [EntityMultiselectControl],
  template: `<app-entity-multiselect-control [field]="this" />`,
})
class ContentPanelField extends TestFieldBase {
  override readonly panelWidth = 'content' as const;
}

describe('EntityMultiselectControl (shared leaf template)', () => {
  function setup<T>(type: Type<T>) {
    TestBed.configureTestingModule({
      providers: [
        provideTestTransloco(),
        { provide: LanguageService, useValue: { selectedLanguage: signal('en') } },
      ],
    });
    const fixture = TestBed.createComponent(type);
    fixture.autoDetectChanges();
    return fixture;
  }

  afterEach(() => {
    // Dispose any leaked overlay panels across the shared single-fork TestBed.
    document.querySelectorAll('.cdk-overlay-container').forEach((el) => el.replaceChildren());
  });

  /** Opens the suggestion panel without triggering the debounced search (see sync spec). */
  async function openPanel(fixture: { nativeElement: HTMLElement; whenStable(): Promise<void> }) {
    const input = fixture.nativeElement.querySelector('.ac-input') as HTMLInputElement;
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true }));
    await fixture.whenStable();
    return Array.from(document.querySelectorAll<HTMLElement>('.ac-panel .ac-option'));
  }

  it('renders the bare autocomplete without wrapper/button when no magnifier is configured', async () => {
    const fixture = setup(PlainField);
    await fixture.whenStable();
    const host = fixture.nativeElement as HTMLElement;

    expect(host.querySelector('app-autocomplete')).not.toBeNull();
    expect(host.querySelector('button.icon-button')).toBeNull();
    expect(host.querySelector('app-autocomplete')?.closest('div.flex')).toBeNull();
  });

  it('plain leaves keep the primitive default rows (bare label, no spans)', async () => {
    const fixture = setup(PlainField);
    fixture.componentInstance.suggestions.set([A]);
    const options = await openPanel(fixture);

    expect(options).toHaveLength(1);
    expect(options[0].textContent?.trim()).toBe('A');
    expect(options[0].querySelector('span')).toBeNull();
  });

  it('renders the magnifier button with tooltip key as aria-label/title and extra classes', async () => {
    const fixture = setup(MagnifierField);
    await fixture.whenStable();
    const host = fixture.nativeElement as HTMLElement;

    const button = host.querySelector('button.icon-button') as HTMLButtonElement;
    expect(button).not.toBeNull();
    expect(button.classList.contains('text-xs')).toBe(true);
    expect(button.getAttribute('aria-label')).toBe('test.magnifier.tooltip');
    expect(button.getAttribute('title')).toBe('test.magnifier.tooltip');
    // The wrapper row exists and the autocomplete flexes inside it.
    expect(button.closest('div.flex')).not.toBeNull();
    expect(host.querySelector('app-autocomplete')?.classList.contains('flex-1')).toBe(true);
  });

  it('clicking the magnifier invokes the field handler with the button as currentTarget', async () => {
    const fixture = setup(MagnifierField);
    await fixture.whenStable();
    const button = (fixture.nativeElement as HTMLElement).querySelector(
      'button.icon-button',
    ) as HTMLButtonElement;

    button.click();

    expect(fixture.componentInstance.lastClickTarget).toBe(button);
  });

  it('optionSuffix renders the shared label + muted-suffix row (suffix omitted when empty)', async () => {
    const fixture = setup(SuffixField);
    fixture.componentInstance.suggestions.set([A, B]);
    const options = await openPanel(fixture);

    expect(options).toHaveLength(2);
    const [withSuffix, withoutSuffix] = options.map((opt) =>
      Array.from(opt.querySelectorAll('span')),
    );
    expect(withSuffix.map((span) => span.textContent?.trim())).toEqual(['A', '(root)']);
    expect(withSuffix[1].className).toContain('text-fg-subtle');
    expect(withoutSuffix.map((span) => span.textContent?.trim())).toEqual(['B']);
  });

  it('a leaf [optionTemplate] wins over optionSuffix', async () => {
    const fixture = setup(CustomRowField);
    fixture.componentInstance.suggestions.set([A]);
    const options = await openPanel(fixture);

    expect(options).toHaveLength(1);
    expect(options[0].querySelector('em.custom-row')?.textContent).toBe('A!');
    expect(options[0].querySelector('span')).toBeNull();
  });

  it('shows the panel spinner while a search is in flight, not "no results"', async () => {
    const fixture = setup(PlainField);
    await fixture.whenStable();

    const input = fixture.nativeElement.querySelector('.ac-input') as HTMLInputElement;
    input.value = 'a';
    input.dispatchEvent(new Event('input'));
    await fixture.whenStable();

    // The answer has not been asked for yet (300 ms of debounce), so the empty
    // message would be a claim about a search nobody has run.
    expect(document.querySelector('.ac-panel .ac-status')).not.toBeNull();
    expect(document.querySelector('.ac-panel .ac-empty')).toBeNull();
  });

  it('pins the panel to the box for a leaf that does not ask otherwise', async () => {
    const fixture = setup(PlainField);
    fixture.componentInstance.suggestions.set([A]);
    await openPanel(fixture);

    expect(document.querySelector('.cdk-overlay-pane.ac-overlay')).not.toBeNull();
    expect(document.querySelector('.cdk-overlay-pane.ac-overlay-content')).toBeNull();
  });

  it('forwards a leaf that asks for a content-sized panel', async () => {
    const fixture = setup(ContentPanelField);
    fixture.componentInstance.suggestions.set([A]);
    await openPanel(fixture);

    expect(document.querySelector('.cdk-overlay-pane.ac-overlay-content')).not.toBeNull();
  });

  it('disables the magnifier button together with the field', async () => {
    const fixture = setup(MagnifierField);
    fixture.componentRef.setInput('disabled', true);
    await fixture.whenStable();

    const button = (fixture.nativeElement as HTMLElement).querySelector(
      'button.icon-button',
    ) as HTMLButtonElement;
    expect(button.disabled).toBe(true);
  });
});
