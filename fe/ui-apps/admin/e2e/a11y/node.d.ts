/*
 * The slice of Node the accessibility scan uses to read axe-core's source and the list of known
 * violations. The e2e tsconfig sets `types: []` and @types/node is not a dependency of the
 * workspace, so they are declared here as Node documents them.
 */
declare module 'node:fs' {
  export function readFileSync(path: string, encoding: 'utf8'): string;
}

declare module 'node:module' {
  export function createRequire(path: string): { resolve(id: string): string };
}
