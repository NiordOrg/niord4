import {
  normalizeAttachmentPath,
  normalizeRepoUrl,
  normalizeRepoUrlsInHtml,
  repoHosts,
} from './repo-url';

/** The host the site is served from in these specs. */
const HOST = 'nautiskinformation.example';
/** The public host of the backend behind it, as a country configuration lists it. */
const BACKEND = 'niord.example.org';
const HOSTS: ReadonlySet<string> = new Set([HOST, BACKEND]);

describe('repoHosts', () => {
  it("names the page's own host when the country lists no backend host", () => {
    expect([...repoHosts()]).toEqual([location.host]);
  });

  it('adds the hosts the country lists for its backend', () => {
    expect([...repoHosts([BACKEND])]).toEqual([location.host, BACKEND]);
  });
});

describe('normalizeRepoUrl', () => {
  it('leaves the root-relative form alone', () => {
    expect(normalizeRepoUrl('/rest/repo/file/a.png', HOSTS)).toBe('/rest/repo/file/a.png');
  });

  it("reduces an absolute repository URL on the site's own host to its path", () => {
    expect(normalizeRepoUrl(`https://${HOST}/rest/repo/file/a.png`, HOSTS)).toBe(
      '/rest/repo/file/a.png',
    );
  });

  it('reduces it whatever scheme it was written with', () => {
    expect(normalizeRepoUrl(`http://${HOST}/rest/repo/file/a.png`, HOSTS)).toBe(
      '/rest/repo/file/a.png',
    );
  });

  it('keeps the query and encodes the path', () => {
    expect(normalizeRepoUrl(`https://${HOST}/rest/repo/file/a b.png?x=1`, HOSTS)).toBe(
      '/rest/repo/file/a%20b.png?x=1',
    );
  });

  // The page a notice points at is in the fragment, and the reader lands on page one without it.
  it('keeps the fragment', () => {
    expect(normalizeRepoUrl(`https://${HOST}/rest/repo/file/chart.pdf#page=7`, HOSTS)).toBe(
      '/rest/repo/file/chart.pdf#page=7',
    );
  });

  it('keeps the query and the fragment together', () => {
    expect(normalizeRepoUrl(`https://${HOST}/rest/repo/file/chart.pdf?v=2#page=7`, HOSTS)).toBe(
      '/rest/repo/file/chart.pdf?v=2#page=7',
    );
  });

  // Another Niord deployment's repository — the other country's backend — holds files this
  // one's does not, so its links go where they were written to.
  it('leaves a repository URL on another host untouched', () => {
    expect(normalizeRepoUrl('https://niord.example.net/rest/repo/file/chart.pdf', HOSTS)).toBe(
      'https://niord.example.net/rest/repo/file/chart.pdf',
    );
  });

  // Stored HTML saved while the backend wrote absolute links carries the backend's host, which
  // is another origin than the page's and one a ship may not reach.
  it("reduces an absolute repository URL on the backend's public host to its path", () => {
    expect(normalizeRepoUrl(`https://${BACKEND}/rest/repo/file/a.png#page=2`, HOSTS)).toBe(
      '/rest/repo/file/a.png#page=2',
    );
  });

  it('tells a host apart from the same name on another port', () => {
    expect(normalizeRepoUrl(`https://${HOST}:8443/rest/repo/file/a.png`, HOSTS)).toBe(
      `https://${HOST}:8443/rest/repo/file/a.png`,
    );
  });

  it('leaves a link to another site untouched', () => {
    expect(normalizeRepoUrl('https://example.com/elsewhere.pdf', HOSTS)).toBe(
      'https://example.com/elsewhere.pdf',
    );
  });

  it('leaves an absolute URL that is not a repository file untouched', () => {
    expect(normalizeRepoUrl(`https://${HOST}/rest/messages/1`, HOSTS)).toBe(
      `https://${HOST}/rest/messages/1`,
    );
  });

  it('returns anything that is not a URL unchanged', () => {
    expect(normalizeRepoUrl(undefined, HOSTS)).toBeUndefined();
    expect(normalizeRepoUrl('', HOSTS)).toBe('');
    expect(normalizeRepoUrl('not a url', HOSTS)).toBe('not a url');
  });

  it('rewrites no host it is not given', () => {
    expect(normalizeRepoUrl(`https://${BACKEND}/rest/repo/file/a.png`, new Set([HOST]))).toBe(
      `https://${BACKEND}/rest/repo/file/a.png`,
    );
  });
});

describe('normalizeAttachmentPath', () => {
  it('prefixes the bare repository path the backend returns', () => {
    // The shape `externalize=false` actually answers with.
    expect(
      normalizeAttachmentPath('messages/7/24/7242e03c/33/NM-270-22%20Femern_DK.pdf', HOSTS),
    ).toBe('/rest/repo/file/messages/7/24/7242e03c/33/NM-270-22%20Femern_DK.pdf');
  });

  it('encodes a path that carries the file name as it was uploaded', () => {
    expect(
      normalizeAttachmentPath('messages/7/24/7242e03c/33/NM-270-22 Femern_DK.pdf', HOSTS),
    ).toBe('/rest/repo/file/messages/7/24/7242e03c/33/NM-270-22%20Femern_DK.pdf');
  });

  it('keeps a #, a ? and a % in a file name part of the name', () => {
    expect(normalizeAttachmentPath('messages/7/Søkort #12.pdf', HOSTS)).toBe(
      '/rest/repo/file/messages/7/S%C3%B8kort%20%2312.pdf',
    );
    expect(normalizeAttachmentPath('messages/7/Hvad nu?.pdf', HOSTS)).toBe(
      '/rest/repo/file/messages/7/Hvad%20nu%3F.pdf',
    );
    expect(normalizeAttachmentPath('messages/7/100% dækning.pdf', HOSTS)).toBe(
      '/rest/repo/file/messages/7/100%25%20d%C3%A6kning.pdf',
    );
  });

  it('leaves a path that already carries the prefix alone', () => {
    expect(normalizeAttachmentPath('/rest/repo/file/a.png', HOSTS)).toBe('/rest/repo/file/a.png');
  });

  it("reduces an absolute repository URL on the site's own host like any other", () => {
    expect(normalizeAttachmentPath(`https://${HOST}/rest/repo/file/a.png`, HOSTS)).toBe(
      '/rest/repo/file/a.png',
    );
  });

  it("reduces an absolute repository URL on the backend's public host", () => {
    expect(normalizeAttachmentPath(`https://${BACKEND}/rest/repo/file/a.png`, HOSTS)).toBe(
      '/rest/repo/file/a.png',
    );
  });

  it('leaves an absolute repository URL on another host untouched', () => {
    expect(normalizeAttachmentPath('https://niord.example.net/rest/repo/file/a.png', HOSTS)).toBe(
      'https://niord.example.net/rest/repo/file/a.png',
    );
  });

  it('returns an empty value unchanged', () => {
    expect(normalizeAttachmentPath(undefined, HOSTS)).toBeUndefined();
    expect(normalizeAttachmentPath('', HOSTS)).toBe('');
  });
});

describe('normalizeRepoUrlsInHtml', () => {
  it("rewrites an absolute image source on the site's own host to this origin", () => {
    expect(
      normalizeRepoUrlsInHtml(`<img src="https://${HOST}/rest/repo/file/a.png" alt="x">`, HOSTS),
    ).toBe('<img src="/rest/repo/file/a.png" alt="x">');
  });

  it("rewrites an absolute image source on the backend's public host to this origin", () => {
    expect(
      normalizeRepoUrlsInHtml(`<img src="http://${BACKEND}/rest/repo/file/a.png" alt="x">`, HOSTS),
    ).toBe('<img src="/rest/repo/file/a.png" alt="x">');
  });

  it('keeps the fragment of a link inside a message', () => {
    expect(
      normalizeRepoUrlsInHtml(
        `<a href="https://${HOST}/rest/repo/file/chart.pdf#page=7">side 7</a>`,
        HOSTS,
      ),
    ).toBe('<a href="/rest/repo/file/chart.pdf#page=7">side 7</a>');
  });

  it('handles single quotes', () => {
    expect(
      normalizeRepoUrlsInHtml(`<a href='https://${HOST}/rest/repo/file/a.pdf'>x</a>`, HOSTS),
    ).toBe("<a href='/rest/repo/file/a.pdf'>x</a>");
  });

  it("leaves a link into another deployment's repository untouched", () => {
    const html = '<a href="https://niord.example.net/rest/repo/file/chart.pdf">x</a>';
    expect(normalizeRepoUrlsInHtml(html, HOSTS)).toBe(html);
  });

  it('leaves an href to another site untouched', () => {
    const html = '<a href="https://example.com/x.pdf">x</a>';
    expect(normalizeRepoUrlsInHtml(html, HOSTS)).toBe(html);
  });

  it('returns a fragment with no attributes identical', () => {
    const html = '<p>Ingen links her</p>';
    expect(normalizeRepoUrlsInHtml(html, HOSTS)).toBe(html);
  });

  it('returns an empty value unchanged', () => {
    expect(normalizeRepoUrlsInHtml(undefined, HOSTS)).toBeUndefined();
    expect(normalizeRepoUrlsInHtml('', HOSTS)).toBe('');
  });
});
