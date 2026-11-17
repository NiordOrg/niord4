import { Resource, ResourceStatus, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { settledValue } from './settled-value';

/** A resource the spec moves through its states by hand; only what the helper reads is real. */
function fakeResource(initial: string[]) {
  const status = signal<ResourceStatus>('idle');
  const value = signal(initial);
  const resource = {
    status,
    value: () => {
      if (status() === 'error') throw new Error('value() read in the error state');
      return value();
    },
  } as unknown as Resource<string[]>;
  return { resource, status, value };
}

describe('settledValue', () => {
  function setUp() {
    const fake = fakeResource([]);
    const settled = TestBed.runInInjectionContext(() => settledValue(fake.resource, []));
    return { ...fake, settled };
  }

  it('passes the default of a resource that has not read anything through', () => {
    const { settled } = setUp();
    expect(settled()).toEqual([]);
  });

  it('keeps what is on screen while the next read is on its way, then shows its answer', () => {
    const { settled, status, value } = setUp();
    status.set('resolved');
    value.set(['2024']);
    expect(settled()).toEqual(['2024']);

    // A resource falls back to its default the moment its request changes.
    status.set('loading');
    value.set([]);
    expect(settled()).toEqual(['2024']);

    status.set('resolved');
    value.set(['2023']);
    expect(settled()).toEqual(['2023']);
  });

  // What was on screen answered another request; left up, it reads as the answer to this one.
  it('empties when the read that replaces it fails', () => {
    const { settled, status, value } = setUp();
    status.set('resolved');
    value.set(['2024']);
    expect(settled()).toEqual(['2024']);

    status.set('loading');
    value.set([]);
    status.set('error');
    expect(settled()).toEqual([]);
  });
});
