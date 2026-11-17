import { HttpErrorResponse } from '@angular/common/http';
import { describe, expect, it } from 'vitest';

import { problemDetail } from './problem-detail';

/** The shape the backend actually sends (captured from a live /secom response). */
const PROBLEM = {
  title: 'Bad Request',
  status: 400,
  detail: 'Unknown dataset 00000000-0000-0000-0000-000000000000',
  instance: '/rest/secom/datasets/00000000-0000-0000-0000-000000000000/gml',
};

const httpError = (error: unknown, status = 400): HttpErrorResponse =>
  new HttpErrorResponse({ error, status, statusText: 'Bad Request', url: '/rest/secom/x' });

describe('problemDetail', () => {
  it('reads the detail from an already-parsed body (a json-typed request)', () => {
    expect(problemDetail(httpError(PROBLEM))).toBe(PROBLEM.detail);
  });

  it('reads the detail from an UNPARSED body (a text-typed request, e.g. the GML fetch)', () => {
    // Angular parses per the REQUEST responseType, so a text request leaves the
    // problem document as a raw string — the case that used to lose the detail.
    expect(problemDetail(httpError(JSON.stringify(PROBLEM)))).toBe(PROBLEM.detail);
  });

  it('tolerates whitespace around an unparsed body', () => {
    expect(problemDetail(httpError(`\n  ${JSON.stringify(PROBLEM)}\n`))).toBe(PROBLEM.detail);
  });

  it('returns undefined for a plain-text body, so the caller can show it verbatim', () => {
    expect(problemDetail(httpError('gateway exploded'))).toBeUndefined();
  });

  it('returns undefined for a body that only looks like JSON', () => {
    expect(problemDetail(httpError('{not json at all'))).toBeUndefined();
  });

  it('returns undefined when the document carries no usable detail', () => {
    expect(problemDetail(httpError({ title: 'Bad Request', status: 400 }))).toBeUndefined();
    expect(problemDetail(httpError({ detail: '' }))).toBeUndefined();
    expect(problemDetail(httpError({ detail: 42 }))).toBeUndefined();
    expect(problemDetail(httpError(JSON.stringify({ title: 'x' })))).toBeUndefined();
  });

  it('returns undefined for null and non-HTTP errors', () => {
    expect(problemDetail(httpError(null))).toBeUndefined();
    expect(problemDetail(new Error('boom'))).toBeUndefined();
    expect(problemDetail('boom')).toBeUndefined();
    expect(problemDetail(undefined)).toBeUndefined();
  });
});
