import { TestBed } from '@angular/core/testing';
import { TranslocoService } from '@jsverse/transloco';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { EntityTree } from './entity-tree';
import type { HierarchicalEntity } from './hierarchical-entity.model';
import type { EntityTreeNode } from './to-tree-nodes';
import { provideNiordIcons } from '../../icons/niord-icons';
import { provideTestTransloco } from '../../testing/transloco-testing';

function mk(
  id: number,
  name: string,
  active = true,
  children: HierarchicalEntity[] = [],
): HierarchicalEntity {
  return { id, descs: [{ lang: 'en', name }], active, children };
}

function setup(entities: HierarchicalEntity[]) {
  TestBed.configureTestingModule({
    providers: [provideTestTransloco(), provideNiordIcons()],
  });
  const fixture = TestBed.createComponent(EntityTree);
  fixture.componentRef.setInput('entities', entities);
  fixture.detectChanges();
  return { fixture, comp: fixture.componentInstance };
}

function nodes(component: EntityTree): readonly EntityTreeNode[] {
  return (component as unknown as { treeNodes: () => EntityTreeNode[] }).treeNodes();
}

describe('EntityTree', () => {
  beforeEach(() => undefined);

  it('marks model names with the stored language while leaving UI placeholders unmarked', () => {
    const { fixture } = setup([
      { id: 1, descs: [{ lang: 'kl', name: 'Kisarsivik Nunaat' }] },
      { id: 2, descs: [{ lang: 'da', name: 'Ankerland' }] },
      { id: 3, descs: [{ lang: 'kl' }] },
      { id: 4, descs: [{ lang: 'kl', name: '' }] },
      { id: 5, descs: [{ lang: 'kl', name: ' \t ' }] },
      { id: 6, descs: [{ lang: 'kl', name: '  Trimmed  ' }] },
    ]);
    const labels = fixture.nativeElement.querySelectorAll(
      '.tree-row-label',
    ) as NodeListOf<HTMLElement>;
    expect(labels[0].lang).toBe('kl');
    expect(labels[1].lang).toBe('da');
    for (const label of [...labels].slice(2, 5)) {
      expect(label.textContent).toBe('messages.common.untitled');
      expect(label.hasAttribute('lang')).toBe(false);
    }
    expect(labels[5].textContent).toBe('Trimmed');
    expect(labels[5].lang).toBe('kl');
  });

  it('maps a flat entity list to TreeNodes using descs[0].name', () => {
    const { comp } = setup([mk(1, 'Alpha'), mk(2, 'Beta')]);
    const result = nodes(comp);
    expect(result.map((n) => n.label)).toEqual(['Alpha', 'Beta']);
    expect(result.map((n) => n.key)).toEqual(['1', '2']);
  });

  it('recursively renders nested children', () => {
    const { comp } = setup([mk(1, 'Root', true, [mk(2, 'Child', true, [mk(3, 'Grandchild')])])]);
    const result = nodes(comp);
    expect(result[0].children?.[0].children?.[0].label).toBe('Grandchild');
  });

  it('applies inactive styleClass when active === false AND flagInactive', () => {
    const { comp } = setup([mk(1, 'Dead', false)]);
    expect(nodes(comp)[0].styleClass).toBe('entity-tree-node-inactive');
  });

  it('omits inactive styleClass when flagInactive is false', () => {
    const { fixture } = setup([]);
    fixture.componentRef.setInput('entities', [mk(1, 'Dead', false)]);
    fixture.componentRef.setInput('flagInactive', false);
    fixture.detectChanges();
    expect(nodes(fixture.componentInstance)[0].styleClass).toBe('');
  });

  it('falls back to the untitled label when descs is empty', () => {
    const { comp } = setup([{ id: 99, active: true } as HierarchicalEntity]);
    // Default stub Transloco returns the raw key on miss.
    expect(nodes(comp)[0].label).toBe('messages.common.untitled');
  });

  // The tree can render before the bundle lands, and the reader can switch language: the
  // placeholder follows both rather than keeping the raw key it was first read as.
  it('takes the untitled label from the bundle when it lands and after a language switch', () => {
    const { fixture, comp } = setup([{ id: 99, active: true } as HierarchicalEntity]);
    const transloco = TestBed.inject(TranslocoService);

    transloco.setTranslation({ 'messages.common.untitled': 'Untitled' }, 'en');
    fixture.detectChanges();
    expect(nodes(comp)[0].label).toBe('Untitled');

    transloco.setTranslation({ 'messages.common.untitled': 'Uden titel' }, 'da');
    transloco.setActiveLang('da');
    fixture.detectChanges();
    expect(nodes(comp)[0].label).toBe('Uden titel');
  });

  it('autoExpand=true sets expanded on every node', () => {
    const { fixture } = setup([]);
    fixture.componentRef.setInput('entities', [mk(1, 'A', true, [mk(2, 'B')])]);
    fixture.componentRef.setInput('autoExpand', true);
    fixture.detectChanges();
    const result = nodes(fixture.componentInstance);
    expect(result[0].expanded).toBe(true);
    expect(result[0].children?.[0].expanded).toBe(true);
  });

  it('onActivate emits the selected entity via entitySelected output', () => {
    const { comp } = setup([mk(42, 'Picked')]);
    const emitted = vi.fn();
    comp.entitySelected.subscribe(emitted);
    (comp as unknown as { onActivate: (node: EntityTreeNode) => void }).onActivate({
      key: '42',
      label: 'Picked',
      data: mk(42, 'Picked'),
      expanded: false,
      styleClass: '',
      children: [],
    });
    expect(emitted).toHaveBeenCalledTimes(1);
    expect(emitted.mock.calls[0][0].id).toBe(42);
  });

  it('expandFirstLevel=true expands roots but not descendants', () => {
    const { fixture } = setup([]);
    fixture.componentRef.setInput('entities', [mk(1, 'A', true, [mk(2, 'B', true, [mk(3, 'C')])])]);
    fixture.componentRef.setInput('expandFirstLevel', true);
    fixture.detectChanges();
    const result = nodes(fixture.componentInstance);
    expect(result[0].expanded).toBe(true);
    expect(result[0].children?.[0].expanded).toBe(false);
    expect(result[0].children?.[0].children?.[0].expanded).toBe(false);
  });

  it('autoExpand wins over expandFirstLevel (both true → fully expanded)', () => {
    const { fixture } = setup([]);
    fixture.componentRef.setInput('entities', [mk(1, 'A', true, [mk(2, 'B')])]);
    fixture.componentRef.setInput('autoExpand', true);
    fixture.componentRef.setInput('expandFirstLevel', true);
    fixture.detectChanges();
    const result = nodes(fixture.componentInstance);
    expect(result[0].expanded).toBe(true);
    expect(result[0].children?.[0].expanded).toBe(true);
  });

  it('reports selectedIds via isSelected (root + nested); styleClass carries no selection', () => {
    const { fixture, comp } = setup([]);
    fixture.componentRef.setInput('entities', [mk(1, 'A'), mk(2, 'B', true, [mk(3, 'C')])]);
    fixture.componentRef.setInput('selectedIds', [2, 3]);
    fixture.detectChanges();
    const result = nodes(comp);
    const isSelected = (
      comp as unknown as { isSelected: (n: EntityTreeNode) => boolean }
    ).isSelected.bind(comp);
    expect(result[0].styleClass).toBe('');
    expect(isSelected(result[0])).toBe(false);
    expect(isSelected(result[1])).toBe(true);
    const kids = result[1].children ?? [];
    expect(kids.length).toBe(1);
    expect(isSelected(kids[0])).toBe(true);
  });

  it('marks inactive via styleClass and selection via isSelected when both apply', () => {
    const { fixture, comp } = setup([]);
    fixture.componentRef.setInput('entities', [mk(1, 'Dead', false)]);
    fixture.componentRef.setInput('selectedIds', [1]);
    fixture.detectChanges();
    const node = nodes(comp)[0];
    const isSelected = (
      comp as unknown as { isSelected: (n: EntityTreeNode) => boolean }
    ).isSelected.bind(comp);
    expect(node.styleClass).toContain('entity-tree-node-inactive');
    expect(isSelected(node)).toBe(true);
  });

  it('flags template nodes (root + nested) via templatePredicate when flagTemplate is on', () => {
    const { fixture } = setup([]);
    const childTmpl = { ...mk(3, 'ChildTmpl'), type: 'TEMPLATE' } as HierarchicalEntity;
    const plain = { ...mk(1, 'Plain'), type: 'CATEGORY' } as HierarchicalEntity;
    const tmpl = { ...mk(2, 'Tmpl', true, [childTmpl]), type: 'TEMPLATE' } as HierarchicalEntity;
    fixture.componentRef.setInput('entities', [plain, tmpl]);
    fixture.componentRef.setInput('flagTemplate', true);
    fixture.componentRef.setInput(
      'templatePredicate',
      (e: HierarchicalEntity) => (e as { type?: string }).type === 'TEMPLATE',
    );
    fixture.detectChanges();
    const result = nodes(fixture.componentInstance);
    expect(result[0].styleClass).toBe('');
    expect(result[1].styleClass).toContain('entity-tree-node-template');
    expect(result[1].children?.[0].styleClass).toContain('entity-tree-node-template');
  });

  it('omits the template class when flagTemplate is off, even with a predicate', () => {
    const { fixture } = setup([]);
    fixture.componentRef.setInput('entities', [
      { ...mk(1, 'T'), type: 'TEMPLATE' } as HierarchicalEntity,
    ]);
    fixture.componentRef.setInput(
      'templatePredicate',
      (e: HierarchicalEntity) => (e as { type?: string }).type === 'TEMPLATE',
    );
    fixture.detectChanges();
    expect(nodes(fixture.componentInstance)[0].styleClass).toBe('');
  });
});

describe('EntityTree search over a rendered nested tree', () => {
  /** Anchorland › { Kattegat › Læsø, Skagerrak }, Sweden › Göteborg. */
  function sample(extraUnderKattegat: HierarchicalEntity[] = []): HierarchicalEntity[] {
    return [
      mk(1, 'Anchorland', true, [
        mk(2, 'Kattegat', true, [mk(3, 'Læsø'), ...extraUnderKattegat]),
        mk(4, 'Skagerrak'),
      ]),
      mk(5, 'Sweden', true, [mk(6, 'Göteborg')]),
    ];
  }

  async function render(entities: HierarchicalEntity[]) {
    TestBed.configureTestingModule({
      providers: [provideTestTransloco(), provideNiordIcons()],
    });
    const fixture = TestBed.createComponent(EntityTree);
    fixture.componentRef.setInput('entities', entities);
    fixture.componentRef.setInput('autoExpand', true);
    await settle(fixture);
    return fixture;
  }

  async function settle(fixture: { detectChanges(): void; whenStable(): Promise<unknown> }) {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  async function search(fixture: Awaited<ReturnType<typeof render>>, query: string) {
    const input = fixture.nativeElement.querySelector('.tree-filter-input') as HTMLInputElement;
    input.value = query;
    input.dispatchEvent(new Event('input'));
    await settle(fixture);
  }

  /** Every rendered label: collapsed rows stay in the DOM, so this is the honest count. */
  function labels(fixture: Awaited<ReturnType<typeof render>>): string[] {
    return [...(fixture.nativeElement as HTMLElement).querySelectorAll('.tree-row-label')].map(
      (label) => label.textContent ?? '',
    );
  }

  const ALL = ['Anchorland', 'Kattegat', 'Læsø', 'Skagerrak', 'Sweden', 'Göteborg'];

  it('renders every row without a search', async () => {
    const fixture = await render(sample());
    expect(labels(fixture)).toEqual(ALL);
  });

  it('leaves only the path to a nested match', async () => {
    const fixture = await render(sample());
    await search(fixture, 'Læsø');
    expect(labels(fixture)).toEqual(['Anchorland', 'Kattegat', 'Læsø']);
  });

  it('brings every row back when the search is cleared', async () => {
    const fixture = await render(sample());
    await search(fixture, 'Kattegat');
    await search(fixture, '');
    expect(labels(fixture)).toEqual(ALL);
  });

  it('brings every row back after a root left and returned pruned', async () => {
    const fixture = await render(sample());
    await search(fixture, 'Göteborg');
    expect(labels(fixture)).toEqual(['Sweden', 'Göteborg']);
    await search(fixture, 'Læsø');
    expect(labels(fixture)).toEqual(['Anchorland', 'Kattegat', 'Læsø']);
    await search(fixture, '');
    expect(labels(fixture)).toEqual(ALL);
  });

  it('emits the entity of a nested match clicked after a search', async () => {
    const fixture = await render(sample());
    const emitted = vi.fn();
    fixture.componentInstance.entitySelected.subscribe(emitted);
    await search(fixture, 'Læsø');
    const row = [...(fixture.nativeElement as HTMLElement).querySelectorAll('.tree-row-main')].find(
      (button) => button.textContent?.trim() === 'Læsø',
    ) as HTMLButtonElement;
    row.click();
    expect(emitted).toHaveBeenCalledTimes(1);
    expect(emitted.mock.calls[0][0].id).toBe(3);
  });

  it('renders a child added below the top level when the entities are replaced', async () => {
    const fixture = await render(sample());
    fixture.componentRef.setInput('entities', sample([mk(7, 'Anholt')]));
    await settle(fixture);
    expect(labels(fixture)).toEqual([
      'Anchorland',
      'Kattegat',
      'Læsø',
      'Anholt',
      'Skagerrak',
      'Sweden',
      'Göteborg',
    ]);
  });
});
