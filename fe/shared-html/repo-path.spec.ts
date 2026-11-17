import { encodeRepoPath } from './repo-path';

describe('encodeRepoPath', () => {
  it('keeps the slashes between the segments', () => {
    expect(encodeRepoPath('messages/7/24/uuid/33/file.pdf')).toBe('messages/7/24/uuid/33/file.pdf');
  });

  it('encodes a file name as it was uploaded', () => {
    expect(encodeRepoPath('messages/7/NM-270-22 Femern_DK.pdf')).toBe(
      'messages/7/NM-270-22%20Femern_DK.pdf',
    );
  });

  it('leaves a path the backend already encoded as it is', () => {
    expect(encodeRepoPath('messages/7/NM-270-22%20Femern_DK.pdf')).toBe(
      'messages/7/NM-270-22%20Femern_DK.pdf',
    );
    expect(encodeRepoPath('messages/6/direkte%20i%20SIA%20PC.png')).toBe(
      'messages/6/direkte%20i%20SIA%20PC.png',
    );
  });

  it('keeps a # in a file name out of the fragment', () => {
    expect(encodeRepoPath('charts/Søkort #12.pdf')).toBe('charts/S%C3%B8kort%20%2312.pdf');
  });

  it('keeps a ? in a file name out of the query', () => {
    expect(encodeRepoPath('charts/Hvad nu?.pdf')).toBe('charts/Hvad%20nu%3F.pdf');
  });

  it('reads a % that starts no escape as a percent sign', () => {
    expect(encodeRepoPath('reports/100% dækning.pdf')).toBe('reports/100%25%20d%C3%A6kning.pdf');
  });

  it('gives the same address for a name in either spelling', () => {
    const name = 'charts/Søkort #12 (ny).pdf';
    expect(encodeRepoPath(encodeURI(name))).toBe(encodeRepoPath(name));
  });

  it('leaves an empty path empty', () => {
    expect(encodeRepoPath('')).toBe('');
  });
});
