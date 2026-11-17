/**
 * The unit suite runs in Node, and the specs that guard the shipped asset files read them
 * from disk rather than from a second copy in the source. The workspace installs no Node
 * type package, and installing one would put Node's globals into the type resolution of both
 * applications' own sources — where a browser timer must not become a Node timer. These
 * declarations therefore cover exactly what the specs call, and they are reachable only from
 * the spec compilation: `tsconfig.app.json` excludes this folder.
 */
declare module 'node:fs' {
  interface DirEntry {
    readonly name: string;
    isDirectory(): boolean;
  }

  export function readFileSync(path: string | URL, encoding: 'utf8'): string;
  export function readFileSync(path: string | URL): Uint8Array;
  export function existsSync(path: string | URL): boolean;
  export function readdirSync(
    path: string | URL,
    options: { withFileTypes: true },
  ): readonly DirEntry[];
}
