import { encodeRepoPath } from '@niord/html/repo-path';

/** Every repository file the public site may load is served under this path. */
const REPO_PREFIX = '/rest/repo/file/';
const ABSOLUTE = /^[a-z][a-z0-9+.-]*:\/\//i;

/**
 * The hosts whose repository links this site loads from its own origin: the one it is served
 * from, and the public hosts the country configuration names for its backend (`backendHosts`).
 */
export function repoHosts(backendHosts: readonly string[] = []): ReadonlySet<string> {
  const own = globalThis.location?.host;
  return new Set(own ? [own, ...backendHosts] : backendHosts);
}

/**
 * The one place that touches a message or publication URL.
 *
 * A root-relative `/rest/repo/file/…` is what `externalize=false` returns and is left alone —
 * the container proxies it from the public host's own origin. Stored HTML can still hold an
 * ABSOLUTE link, written by a save made while the backend externalised its links; one to a
 * repository file on one of `hosts` is reduced to its path, so it too loads from this origin,
 * over the scheme the page was loaded with. A repository link to any other host is returned
 * untouched, like a link to any other site: it may be another Niord deployment's repository —
 * the other country's chart corrections — and its files are not in this one.
 *
 * Everything after the origin is carried over, the fragment included: an editor who linked a
 * notice to `…/chart.pdf#page=7` meant the seventh page, and dropping the fragment would open
 * the reader at the first one instead.
 */
export function normalizeRepoUrl(url: string, hosts: ReadonlySet<string>): string;
export function normalizeRepoUrl(
  url: string | undefined,
  hosts: ReadonlySet<string>,
): string | undefined;
export function normalizeRepoUrl(
  url: string | undefined,
  hosts: ReadonlySet<string>,
): string | undefined {
  if (!url || !ABSOLUTE.test(url)) return url;
  let parsed: URL;
  try {
    parsed = new URL(url);
  } catch {
    return url;
  }
  return hosts.has(parsed.host) && parsed.pathname.startsWith(REPO_PREFIX)
    ? parsed.pathname + parsed.search + parsed.hash
    : url;
}

/**
 * An attachment path arrives as a BARE repository path (`messages/7/24/…/file.pdf`) with no
 * prefix at all, because the backend's attachment rewriting matches the raw repository path
 * where the part rewriting matches the prefixed one. A relative value that is not already a
 * site path is therefore prefixed, and — being a path and not an address — encoded segment by
 * segment, so a `#`, `?` or `%` in a file name stays part of the name.
 */
export function normalizeAttachmentPath(
  path: string | undefined,
  hosts: ReadonlySet<string>,
): string | undefined {
  if (!path) return path;
  const normalized = normalizeRepoUrl(path, hosts);
  if (!normalized || ABSOLUTE.test(normalized) || normalized.startsWith('/')) return normalized;
  return REPO_PREFIX + encodeRepoPath(normalized);
}

/** Runs `normalizeRepoUrl` over every `src` and `href` of a rendered HTML fragment. */
export function normalizeRepoUrlsInHtml(
  html: string | undefined,
  hosts: ReadonlySet<string>,
): string | undefined {
  if (!html) return html;
  return html.replace(
    /\b(src|href)=(["'])(.*?)\2/gi,
    (match, attribute: string, quote: string, value: string) => {
      const normalized = normalizeRepoUrl(value, hosts);
      return normalized === value ? match : `${attribute}=${quote}${normalized}${quote}`;
    },
  );
}
