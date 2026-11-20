import { TestBed } from '@angular/core/testing';
import { Subject, of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';

import { fixedListRead, listRead } from './list-read';

describe('listRead', () => {
  it('fills the value from a read that answers', () => {
    const read = TestBed.runInInjectionContext(() => fixedListRead(() => of([1, 2])));
    expect(read.value()).toEqual([1, 2]);
    expect(read.failed()).toBe(false);
  });

  it('a failed read reads empty and says it failed; a retry that answers clears it', () => {
    let calls = 0;
    const source = vi.fn(() => (calls++ === 0 ? throwError(() => new Error('offline')) : of([3])));
    const read = TestBed.runInInjectionContext(() => fixedListRead(source));
    expect(read.value()).toEqual([]);
    expect(read.failed()).toBe(true);

    read.retry();
    expect(source).toHaveBeenCalledTimes(2);
    expect(read.value()).toEqual([3]);
    expect(read.failed()).toBe(false);
  });

  it('reads again for every key, and a retry reads the newest key', () => {
    const keys = new Subject<string>();
    const source = vi.fn((key: string) => of([key]));
    const read = TestBed.runInInjectionContext(() => listRead(keys, source));
    keys.next('en');
    keys.next('da');
    expect(read.value()).toEqual(['da']);
    read.retry();
    expect(source).toHaveBeenLastCalledWith('da');
  });
});
