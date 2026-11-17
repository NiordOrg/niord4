/*
 * The slice of Node's child_process module the rig uses to read the copy's database through psql in
 * its container (backend.ts). The folder's tsconfig sets `types: []` and @types/node is not a
 * dependency of the workspace, so the function is declared here as Node documents it.
 */
declare module 'node:child_process' {
  export function execFileSync(
    file: string,
    args: readonly string[],
    options: {
      readonly encoding: 'utf8';
      readonly timeout?: number;
      readonly windowsHide?: boolean;
      readonly maxBuffer?: number;
    },
  ): string;
}
