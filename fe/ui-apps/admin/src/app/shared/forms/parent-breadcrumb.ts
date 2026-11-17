export interface BreadcrumbNode {
  readonly descs?: readonly { readonly name?: string }[];
  readonly parent?: BreadcrumbNode;
}

export function parentBreadcrumb(node: BreadcrumbNode): string {
  const names: string[] = [];
  let current = node.parent;
  while (current) {
    const name = current.descs?.[0]?.name;
    if (name) {
      names.unshift(name);
    }
    current = current.parent;
  }
  return names.length > 0 ? `(${names.join(' - ')})` : '';
}
