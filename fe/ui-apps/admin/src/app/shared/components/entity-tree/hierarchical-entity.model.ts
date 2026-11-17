export interface HierarchicalEntityDesc {
  readonly lang?: string;
  readonly name?: string;
}

/**
 * Minimal contract the <niord-entity-tree> primitive consumes. `AreaRef` and
 * `CategoryRef` conform structurally — no `implements` declaration needed.
 */
export interface HierarchicalEntity {
  /** Numeric for areas/categories; Keycloak groups carry string UUIDs. */
  readonly id: number | string;
  readonly descs?: readonly HierarchicalEntityDesc[];
  readonly children?: readonly HierarchicalEntity[];
  readonly active?: boolean;
}
