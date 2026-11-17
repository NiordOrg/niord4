/**
 * Joins path parts with `/` and resolves `.` and `..`, without Node's path module, which the rig's
 * TypeScript does not type (the folder's tsconfig sets `types: []`, as the end-to-end suite's
 * does). Node and Playwright accept `/` on every platform, a Windows drive letter included.
 */
export function joinPath(...parts: readonly string[]): string {
  const segments: string[] = [];
  parts
    .join('/')
    .split(/[\\/]+/)
    .forEach((segment, index) => {
      if (segment === '.' || (segment === '' && index > 0)) return;
      if (segment === '..' && segments.length > 1 && segments[segments.length - 1] !== '..') {
        segments.pop();
        return;
      }
      segments.push(segment);
    });
  return segments.join('/');
}
