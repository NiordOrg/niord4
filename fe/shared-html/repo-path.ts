/**
 * A repository path — the path of an attachment or an image under `/rest/repo/file/` — as it is
 * written in an address: every segment percent-encoded, the slashes between them kept.
 *
 * The backend hands a path out in either of two spellings, the file name as it was uploaded
 * (`NM-270-22 Femern_DK.pdf`) or already encoded (`NM-270-22%20Femern_DK.pdf`), so a segment is
 * decoded first wherever it decodes, and both spellings come out the same. After that a `#` or a
 * `?` in a file name is part of the name rather than the start of a fragment or a query, and a
 * `%` that starts no escape is a percent sign. The one name this cannot tell apart is one whose
 * upload already contained an escape (`50%20off.pdf`), which reads as the encoded form.
 */
export function encodeRepoPath(path: string): string {
  return path.split('/').map(encodeSegment).join('/');
}

function encodeSegment(segment: string): string {
  let name = segment;
  try {
    name = decodeURIComponent(segment);
  } catch {
    // A `%` that starts no valid escape: the segment is a name as it was uploaded.
  }
  return encodeURIComponent(name);
}
