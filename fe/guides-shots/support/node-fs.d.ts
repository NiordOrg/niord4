/*
 * The slice of Node's fs module the rig uses to keep its reports beside the pictures and its tile
 * cache. The folder's tsconfig sets `types: []` and @types/node is not a dependency of the
 * workspace, so the functions are declared here as Node documents them; a Buffer is a Uint8Array.
 */
declare module 'node:fs' {
  export function appendFileSync(path: string, data: string, encoding: 'utf8'): void;
  export function existsSync(path: string): boolean;
  export function mkdirSync(
    path: string,
    options: { readonly recursive: true },
  ): string | undefined;
  export function readFileSync(path: string): Uint8Array;
  export function readFileSync(path: string, encoding: 'utf8'): string;
  export function writeFileSync(path: string, data: Uint8Array): void;
  export function writeFileSync(path: string, data: string, encoding: 'utf8'): void;
}
