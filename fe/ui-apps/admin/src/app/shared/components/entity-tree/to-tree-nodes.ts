import type { HierarchicalEntity } from './hierarchical-entity.model';

export interface TreeNodeMapOptions<T extends HierarchicalEntity> {
  /**
   * Fallback label when the first description has no name, or only whitespace;
   * stored names are trimmed.
   */
  readonly untitled: string;
  /**
   * Whether a node starts expanded. This is only a HINT: the CDK tree does not
   * read it directly (expansion lives in the tree's expansion model), the
   * wrappers seed the model imperatively from these flags. Defaults to collapsed.
   */
  readonly expanded?: (entity: T, isRoot: boolean) => boolean;
  /** Optional per-node style classes (selection/inactive flags, etc.). */
  readonly styleClass?: (entity: T) => string;
}

/**
 * niord view-model for a single tree row rendered by the `@angular/cdk/tree`
 * wrappers. The folder glyph is rendered by `<app-icon name="lucideFolder">` in
 * the row template rather than carried as an `icon` field.
 */
export interface EntityTreeNode<T extends HierarchicalEntity = HierarchicalEntity> {
  readonly key: string;
  readonly label: string;
  /** Stored text language; absent for the UI's untitled placeholder. */
  readonly labelLanguage?: string;
  readonly data: T;
  /** Expansion HINT for seeding the CDK expansion model (see `TreeNodeMapOptions`). */
  readonly expanded: boolean;
  /** Space-separated flag classes (e.g. `entity-tree-node-inactive`), never null. */
  readonly styleClass: string;
  readonly children: EntityTreeNode<T>[];
}

/**
 * Maps a `HierarchicalEntity[]` (areas, categories, Keycloak groups) into the
 * niord `EntityTreeNode[]` view-model. The single owner of this shaping so
 * `<app-entity-tree>` (single-select browse) and `<app-tree-picker>` (checkbox
 * multi-select) can't drift on key/label/children semantics — they differ only
 * in the injected `expanded`/`styleClass`.
 */
export function toTreeNodes<T extends HierarchicalEntity>(
  entities: readonly T[],
  options: TreeNodeMapOptions<T>,
): EntityTreeNode<T>[] {
  const visit = (entity: T, isRoot: boolean): EntityTreeNode<T> => {
    const desc = entity.descs?.[0];
    const name = desc?.name?.trim();
    return {
      key: String(entity.id),
      label: name || options.untitled,
      labelLanguage: name ? desc?.lang : undefined,
      data: entity,
      styleClass: options.styleClass?.(entity) ?? '',
      expanded: options.expanded?.(entity, isRoot) ?? false,
      children: entity.children?.map((child) => visit(child as T, false)) ?? [],
    };
  };
  return entities.map((entity) => visit(entity, true));
}
