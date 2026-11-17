import { hashUrlTarget } from './hash-url';

const at = (pathname: string, search = '', hash = '') => ({ pathname, search, hash });

describe('hashUrlTarget', () => {
  it('leaves an address without a fragment alone', () => {
    expect(hashUrlTarget(at('/messages/map', '?lang=da'))).toBeNull();
  });

  it('leaves an ordinary fragment alone', () => {
    expect(hashUrlTarget(at('/messages/details', '', '#top'))).toBeNull();
  });

  it('moves a route out of the fragment', () => {
    expect(hashUrlTarget(at('/', '', '#/messages/map'))).toBe('/messages/map');
  });

  it('keeps the query the address already had', () => {
    expect(hashUrlTarget(at('/', '?lang=da', '#/messages/map'))).toBe('/messages/map?lang=da');
  });

  it('reads a query written inside the moved route', () => {
    expect(hashUrlTarget(at('/', '', '#/teaser?lang=da&area=x'))).toBe('/teaser?lang=da&area=x');
  });

  it('joins the query the address already had to one inside the moved route', () => {
    expect(hashUrlTarget(at('/', '?lang=da', '#/messages/map?area=7'))).toBe(
      '/messages/map?area=7&lang=da',
    );
  });

  it('keeps a fragment of the moved route after the query the address already had', () => {
    expect(hashUrlTarget(at('/', '?lang=da', '#/messages/map#top'))).toBe(
      '/messages/map?lang=da#top',
    );
    expect(hashUrlTarget(at('/', '?lang=da', '#/teaser?area=x#?p=1'))).toBe(
      '/teaser?area=x&lang=da&p=1',
    );
  });

  it('moves a query written after the fragment mark', () => {
    expect(hashUrlTarget(at('/teaser', '', '#?lang=da&area=x'))).toBe('/teaser?lang=da&area=x');
  });

  it('joins it to the query the address already had', () => {
    expect(hashUrlTarget(at('/teaser', '?lang=da', '#?area=x'))).toBe('/teaser?lang=da&area=x');
  });

  it('does both when the moved route ends in a query after a fragment mark', () => {
    expect(hashUrlTarget(at('/', '', '#/teaser#?lang=da'))).toBe('/teaser?lang=da');
  });

  // A fragment is anybody's to write; the rewritten address must stay on this site.
  it('never leaves the site, whatever the fragment names', () => {
    expect(hashUrlTarget(at('/', '', '#//elsewhere.example/messages'))).toBe('/messages');
  });

  // A dot segment that resolves away leaves two slashes, which would name another host.
  it('keeps a path that resolves to two leading slashes on the site', () => {
    expect(hashUrlTarget(at('/', '', '#/.//elsewhere.example/x'))).toBe('/elsewhere.example/x');
    expect(hashUrlTarget(at('/', '', '#/..//x'))).toBe('/x');
    expect(hashUrlTarget(at('/', '', '#/%2e//x'))).toBe('/x');
  });

  // The teaser is the one page another site may frame; a frame of it must not become a frame of
  // the rest of the site through the fragment it names.
  it('never moves an address on the teaser to another page', () => {
    expect(hashUrlTarget(at('/teaser', '', '#/messages/map'))).toBeNull();
    expect(hashUrlTarget(at('/teaser/', '?lang=da', '#/publications'))).toBeNull();
    expect(hashUrlTarget(at('/teaser', '', '#/teaser/../messages/details'))).toBeNull();
  });

  it('still reads the fragment of an address on the teaser that stays on the teaser', () => {
    expect(hashUrlTarget(at('/teaser', '', '#/teaser?lang=da'))).toBe('/teaser?lang=da');
    expect(hashUrlTarget(at('/teaser/', '', '#?lang=da'))).toBe('/teaser/?lang=da');
  });

  // The rewrite runs before the application starts, so it must not be able to stop it.
  it('leaves a fragment that is no address alone instead of failing', () => {
    expect(hashUrlTarget(at('/', '', '#//a b/x'))).toBeNull();
    expect(hashUrlTarget(at('/', '', '#//['))).toBeNull();
  });
});
