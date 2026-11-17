import { Component, Directive, signal, viewChild } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Observable, Subject, of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';

import { EntityMultiselectField } from './entity-multiselect-field';
import { LanguageService } from '../../core/main-shell/services/language.service';

interface Item {
  readonly id: number;
  readonly name: string;
}

@Directive({ selector: '[appTestMultiselect]', standalone: true })
class TestMultiselect extends EntityMultiselectField<Item, number> {
  searchImpl: (q: string) => Observable<readonly Item[]> = () => of<readonly Item[]>([]);
  resolveImpl: (ids: readonly number[]) => Observable<readonly Item[]> = () =>
    of<readonly Item[]>([]);

  protected override searchEntities(query: string): Observable<readonly Item[]> {
    return this.searchImpl(query);
  }
  protected override resolveByIds(ids: readonly number[]): Observable<readonly Item[]> {
    return this.resolveImpl(ids);
  }
  protected override idOf(item: Item): number {
    return item.id;
  }
}

@Component({
  selector: 'app-test-host',
  standalone: true,
  imports: [TestMultiselect],
  template: `<div appTestMultiselect [initialIds]="ids()"></div>`,
})
class HostComponent {
  readonly ids = signal<readonly number[] | null>(null);
  readonly field = viewChild.required(TestMultiselect);
}

function createHost() {
  const language = signal('en');
  TestBed.configureTestingModule({
    imports: [HostComponent],
    providers: [{ provide: LanguageService, useValue: { selectedLanguage: language } }],
  });
  const fixture = TestBed.createComponent(HostComponent);
  fixture.detectChanges();
  return { fixture, host: fixture.componentInstance, language };
}

describe('EntityMultiselectField — pendingCount accounting', () => {
  it('returns to 0 when switchMap cancels an in-flight resolve', async () => {
    const { fixture, host } = createHost();
    const field = host.field();
    const pending = new Subject<readonly Item[]>();

    field.resolveImpl = () => pending;
    host.ids.set([1, 2]);
    fixture.detectChanges();
    await fixture.whenStable();
    expect(field.pendingCount()).toBe(1);

    field.resolveImpl = () => of<readonly Item[]>([{ id: 3, name: 'c' }]);
    host.ids.set([3]);
    fixture.detectChanges();
    await fixture.whenStable();

    expect(field.pendingCount()).toBe(0);
  });

  it('returns to 0 when the resolve API errors', async () => {
    const { fixture, host } = createHost();
    const field = host.field();
    field.resolveImpl = () => throwError(() => new Error('api down'));

    host.ids.set([1]);
    fixture.detectChanges();
    await fixture.whenStable();

    expect(field.pendingCount()).toBe(0);
  });

  it('returns to 0 on successful resolution', async () => {
    const { fixture, host } = createHost();
    const field = host.field();
    field.resolveImpl = () => of<readonly Item[]>([{ id: 1, name: 'a' }]);

    host.ids.set([1]);
    fixture.detectChanges();
    await fixture.whenStable();

    expect(field.pendingCount()).toBe(0);
    expect(field.value()).toEqual([{ id: 1, name: 'a' }]);
  });

  it('survives an error and continues handling subsequent resolves', async () => {
    const { fixture, host } = createHost();
    const field = host.field();
    let callCount = 0;
    field.resolveImpl = () => {
      callCount++;
      if (callCount === 1) return throwError(() => new Error('api down'));
      return of<readonly Item[]>([{ id: 2, name: 'b' }]);
    };

    host.ids.set([1]);
    fixture.detectChanges();
    await fixture.whenStable();
    expect(field.pendingCount()).toBe(0);
    expect(field.value()).toEqual([]);

    host.ids.set([2]);
    fixture.detectChanges();
    await fixture.whenStable();

    expect(field.pendingCount()).toBe(0);
    expect(field.value()).toEqual([{ id: 2, name: 'b' }]);
  });
});

describe('EntityMultiselectField — language reactivity', () => {
  it('re-resolves initial ids when the active language changes', async () => {
    const { fixture, host, language } = createHost();
    const field = host.field();
    // Label depends on the active language, mimicking a backend-localized name.
    field.resolveImpl = () => of<readonly Item[]>([{ id: 1, name: `name-${language()}` }]);

    host.ids.set([1]);
    fixture.detectChanges();
    await fixture.whenStable();
    expect(field.value()).toEqual([{ id: 1, name: 'name-en' }]);

    // Same ids, but a language switch must re-resolve so the localized label
    // refreshes instead of keeping the stale 'name-en'.
    language.set('da');
    fixture.detectChanges();
    await fixture.whenStable();
    expect(field.value()).toEqual([{ id: 1, name: 'name-da' }]);
  });
});

describe('EntityMultiselectField — searching', () => {
  /** The debounce is a real timer; drive it rather than waiting 300 ms per case. */
  function withFakeTimers(body: () => void): void {
    vi.useFakeTimers();
    try {
      body();
    } finally {
      vi.useRealTimers();
    }
  }

  it('is raised at the keystroke and cleared when results arrive', () => {
    withFakeTimers(() => {
      const { host } = createHost();
      const field = host.field();
      field.searchImpl = () => of<readonly Item[]>([{ id: 1, name: 'a' }]);

      expect(field.searching()).toBe(false);
      field.onSearch('a');
      // Before the debounce, not when the request leaves: the wait a user sees
      // starts at the keystroke.
      expect(field.searching()).toBe(true);

      vi.advanceTimersByTime(300);
      expect(field.searching()).toBe(false);
      expect(field.suggestions()).toEqual([{ id: 1, name: 'a' }]);
    });
  });

  it('is cleared when the search errors, so the panel stops spinning', () => {
    withFakeTimers(() => {
      const { host } = createHost();
      const field = host.field();
      field.searchImpl = () => throwError(() => new Error('api down'));

      field.onSearch('a');
      expect(field.searching()).toBe(true);

      vi.advanceTimersByTime(300);
      expect(field.searching()).toBe(false);
      expect(field.fetchError()).toBe(true);
    });
  });

  it('stays down for an empty query on a field that does not browse', () => {
    withFakeTimers(() => {
      const { host } = createHost();
      const field = host.field();

      // Nothing is fetched for this one, so there is no wait to report — and
      // `loading` is one of the things that opens the panel.
      field.onSearch('');
      expect(field.searching()).toBe(false);
    });
  });
});

describe('EntityMultiselectField — appendOne', () => {
  it('appends a new item and emits valueChange / touch', () => {
    const { host } = createHost();
    const field = host.field();
    field.value.set([{ id: 1, name: 'a' }]);
    // Subscribe AFTER seeding so only the append's emissions are counted.
    const changes: (readonly Item[])[] = [];
    let touched = 0;
    field.value.subscribe((v) => changes.push([...v]));
    field.touch.subscribe(() => touched++);

    field.appendOne({ id: 2, name: 'b' });

    expect(field.value()).toEqual([
      { id: 1, name: 'a' },
      { id: 2, name: 'b' },
    ]);
    expect(changes).toHaveLength(1);
    expect(changes[0]).toEqual([
      { id: 1, name: 'a' },
      { id: 2, name: 'b' },
    ]);
    expect(touched).toBe(1);
  });

  it('is a no-op when the id is already present', () => {
    const { host } = createHost();
    const field = host.field();
    field.value.set([{ id: 1, name: 'a' }]);
    const changes: (readonly Item[])[] = [];
    let touched = 0;
    field.value.subscribe((v) => changes.push([...v]));
    field.touch.subscribe(() => touched++);

    // Same id, different name — duplicate by id, so must NOT be appended and
    // must NOT replace the existing entry.
    field.appendOne({ id: 1, name: 'renamed' });

    expect(field.value()).toEqual([{ id: 1, name: 'a' }]);
    expect(changes).toHaveLength(0);
    expect(touched).toBe(0);
  });
});
