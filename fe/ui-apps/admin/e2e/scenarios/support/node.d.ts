/*
 * The slice of Node the scenarios' restart step uses to run the runner's shell command. The e2e tsconfig sets `types: []` and @types/node is not a
 * dependency of the workspace, so they are declared here as Node documents them.
 */
declare module 'node:child_process' {
  export function execSync(
    command: string,
    options: {
      readonly stdio?: 'inherit' | 'pipe' | 'ignore';
      readonly timeout?: number;
      readonly windowsHide?: boolean;
    },
  ): unknown;
}
