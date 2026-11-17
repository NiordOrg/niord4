import { linkedSignal, Resource, ResourceStatus, Signal } from '@angular/core';

/** What one read left behind: the state it ended in, and the value it produced. */
interface Read<T> {
  readonly status: ResourceStatus;
  readonly value: T;
}

/**
 * A resource's value, held on screen through the read that replaces it.
 *
 * A resource falls back to its default value the moment its request changes, so a view bound
 * straight to one empties on every filter change and fills again when the answer arrives. This
 * keeps what the reader is looking at until the read settles.
 *
 * A failed read leaves the empty value, never the previous one: what was on screen belongs to
 * another request — another year, another area, another language — and left up it would be read
 * as the answer to this one. Every surface that renders a settled value says in words of its own
 * that its read failed. An idle resource — one whose request function returned `undefined` —
 * issued no read at all, so its default value passes straight through.
 */
export function settledValue<T>(resource: Resource<T>, emptyValue: T): Signal<T> {
  return linkedSignal<Read<T>, T>({
    // `value()` throws in the error state, which is the one state it is not read in.
    source: () => {
      const status = resource.status();
      return { status, value: status === 'error' ? emptyValue : resource.value() };
    },
    computation: (read, previous) => {
      switch (read.status) {
        case 'loading':
        case 'reloading':
          return previous?.value ?? read.value;
        default:
          return read.value;
      }
    },
  });
}
