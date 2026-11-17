import { TestBed } from '@angular/core/testing';
import { Subject, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';

import { SaveNamedFilterDialog } from './save-named-filter-dialog';
import { NamedFiltersFacade } from '../../services/named-filters.facade';
import { provideTestTransloco } from '../../../../shared/testing/transloco-testing';

function setup(saveImpl: (name: string) => ReturnType<NamedFiltersFacade['save']>) {
  const save = vi.fn(saveImpl);

  TestBed.configureTestingModule({
    providers: [{ provide: NamedFiltersFacade, useValue: { save } }, provideTestTransloco()],
  });

  const fixture = TestBed.createComponent(SaveNamedFilterDialog);
  fixture.detectChanges();
  return { fixture, comp: fixture.componentInstance, save };
}

describe('SaveNamedFilterDialog', () => {
  it('canSave is false for empty name', () => {
    const { comp } = setup(() => new Subject<void>());
    expect(comp.canSave()).toBe(false);
  });

  it('canSave is false for whitespace-only name', () => {
    const { comp } = setup(() => new Subject<void>());
    comp.name.set('   ');
    expect(comp.canSave()).toBe(false);
  });

  it('canSave is true once a non-blank name is set', () => {
    const { comp } = setup(() => new Subject<void>());
    comp.name.set('Daily NW');
    expect(comp.canSave()).toBe(true);
  });

  it('onSave forwards the typed name to the facade', () => {
    const subject = new Subject<void>();
    const { comp, save } = setup(() => subject);

    comp.name.set('Daily NW');
    comp.onSave();

    expect(save).toHaveBeenCalledWith('Daily NW');
  });

  it('onSave is a no-op when canSave is false', () => {
    const { comp, save } = setup(() => new Subject<void>());

    comp.onSave();

    expect(save).not.toHaveBeenCalled();
  });

  it('on success: clears name, resets saving, hides dialog', () => {
    const subject = new Subject<void>();
    const { comp } = setup(() => subject);

    comp.visible.set(true);
    comp.name.set('Daily NW');
    comp.onSave();

    expect(comp.saving()).toBe(true);

    subject.next();
    subject.complete();

    expect(comp.saving()).toBe(false);
    expect(comp.name()).toBe('');
    expect(comp.visible()).toBe(false);
    expect(comp.saveError()).toBeUndefined();
  });

  it('on error: keeps dialog open and stores the error', () => {
    const err = new Error('boom');
    const { comp } = setup(() => throwError(() => err));

    comp.visible.set(true);
    comp.name.set('Daily NW');
    comp.onSave();

    expect(comp.saving()).toBe(false);
    expect(comp.saveError()).toBe(err);
    expect(comp.visible()).toBe(true);
    expect(comp.name()).toBe('Daily NW');
  });

  it('onCancel clears name and closes the dialog', () => {
    const { comp } = setup(() => new Subject<void>());
    comp.visible.set(true);
    comp.name.set('half typed');

    comp.onCancel();

    expect(comp.name()).toBe('');
    expect(comp.visible()).toBe(false);
    expect(comp.saveError()).toBeUndefined();
  });

  it('onHide clears name and resets error but does not toggle visible', () => {
    const { comp } = setup(() => new Subject<void>());
    comp.visible.set(true);
    comp.name.set('half typed');
    comp.saveError.set(new Error('previous'));

    comp.onHide();

    expect(comp.name()).toBe('');
    expect(comp.saveError()).toBeUndefined();
  });
});
