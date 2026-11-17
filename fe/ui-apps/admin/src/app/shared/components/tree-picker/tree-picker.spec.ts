import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { TranslocoService } from '@jsverse/transloco';
import { afterEach, describe, expect, it } from 'vitest';

import type { HierarchicalEntity } from '../entity-tree/hierarchical-entity.model';
import type { EntityTreeNode } from '../entity-tree/to-tree-nodes';

/** The picker works over numeric-id entities (areas/categories). */
type NumericEntity = HierarchicalEntity & { readonly id: number };
import { provideNiordIcons } from '../../icons/niord-icons';
import { provideTestTransloco } from '../../testing/transloco-testing';
import { TreePicker } from './tree-picker';

function area(id: number, name: string, children: NumericEntity[] = []): NumericEntity {
  return { id, descs: [{ lang: 'en', name }], children };
}

@Component({
  imports: [TreePicker],
  template: `
    <app-tree-picker
      [entities]="entities()"
      [selectedIds]="selectedIds()"
      (selectionChange)="emitted = $event"
    />
  `,
})
class Host {
  readonly entities = signal<readonly NumericEntity[]>([]);
  readonly selectedIds = signal<readonly number[]>([]);
  emitted: readonly NumericEntity[] | null = null;
}

interface PickerInternals {
  nodes(): EntityTreeNode<NumericEntity>[];
  selection(): EntityTreeNode<NumericEntity>[];
  selectedCount(): number;
  onSelectionChange(
    value: EntityTreeNode<NumericEntity> | EntityTreeNode<NumericEntity>[] | null | undefined,
  ): void;
  clear(): void;
}

function setup(entities: NumericEntity[], selectedIds: number[] = []) {
  TestBed.configureTestingModule({
    providers: [provideTestTransloco(), provideNiordIcons()],
  });
  const fixture = TestBed.createComponent(Host);
  fixture.componentInstance.entities.set(entities);
  fixture.componentInstance.selectedIds.set(selectedIds);
  fixture.detectChanges();
  const picker = fixture.debugElement.query(By.directive(TreePicker))
    .componentInstance as TreePicker;
  return { fixture, host: fixture.componentInstance, p: picker as unknown as PickerInternals };
}

describe('TreePicker', () => {
  afterEach(() => {
    document.querySelectorAll('.cdk-overlay-container').forEach((el) => el.replaceChildren());
  });

  it('marks stored names with their language and leaves blank-name placeholders unmarked', async () => {
    const { fixture } = setup([
      { id: 1, descs: [{ lang: 'kl', name: 'Kisarsivik Nunaat' }] },
      { id: 2, descs: [{ lang: 'da', name: 'Ankerland' }] },
      { id: 3, descs: [{ lang: 'kl', name: '   ' }] },
    ]);
    const picker = fixture.debugElement.query(By.directive(TreePicker))
      .componentInstance as TreePicker;
    picker.open(fixture.nativeElement);
    fixture.detectChanges();
    await fixture.whenStable();
    const labels = document.querySelectorAll<HTMLElement>('.picker-panel .tree-row-label');
    expect([...labels].map((label) => label.textContent)).toEqual([
      'Kisarsivik Nunaat',
      'Ankerland',
      'messages.common.untitled',
    ]);
    expect(labels[0].lang).toBe('kl');
    expect(labels[1].lang).toBe('da');
    expect(labels[2].hasAttribute('lang')).toBe(false);
  });

  // The picker can render before the bundle lands, and the reader can switch language: the
  // placeholder follows both rather than keeping the raw key it was first read as.
  it('takes the untitled label from the bundle when it lands and after a language switch', () => {
    const { fixture, p } = setup([{ id: 7, descs: [{ lang: 'en', name: ' ' }] }]);
    const transloco = TestBed.inject(TranslocoService);

    transloco.setTranslation({ 'messages.common.untitled': 'Untitled' }, 'en');
    fixture.detectChanges();
    expect(p.nodes()[0].label).toBe('Untitled');

    transloco.setTranslation({ 'messages.common.untitled': 'Uden titel' }, 'da');
    transloco.setActiveLang('da');
    fixture.detectChanges();
    expect(p.nodes()[0].label).toBe('Uden titel');
  });

  it('maps entities to a tree (roots expanded, children nested, keyed by id)', () => {
    const { p } = setup([area(1, 'Anchorland', [area(2, 'The Sound'), area(3, 'Kattegat')])]);
    const nodes = p.nodes();
    expect(nodes).toHaveLength(1);
    expect(nodes[0].key).toBe('1');
    expect(nodes[0].label).toBe('Anchorland');
    expect(nodes[0].expanded).toBe(true);
    expect(nodes[0].children?.map((c) => c.label)).toEqual(['The Sound', 'Kattegat']);
    expect(nodes[0].children?.[0].expanded).toBe(false);
  });

  it('selection mirrors selectedIds across nesting levels', () => {
    const { p } = setup(
      [area(1, 'Anchorland', [area(2, 'The Sound'), area(3, 'Kattegat')])],
      [1, 3],
    );
    expect(
      p
        .selection()
        .map((n) => (n.data as HierarchicalEntity).id)
        .sort(),
    ).toEqual([1, 3]);
    expect(p.selectedCount()).toBe(2);
  });

  it('onSelectionChange emits the selected entity data', () => {
    const { host, p } = setup([area(1, 'Anchorland', [area(2, 'The Sound')])]);
    const node: EntityTreeNode<NumericEntity> = {
      key: '2',
      label: 'The Sound',
      data: area(2, 'The Sound'),
      expanded: false,
      styleClass: '',
      children: [],
    };
    p.onSelectionChange([node]);
    expect(host.emitted?.map((e) => e.id)).toEqual([2]);
  });

  it('clear emits an empty selection', () => {
    const { host, p } = setup([area(1, 'Anchorland')], [1]);
    p.clear();
    expect(host.emitted).toEqual([]);
  });
});
