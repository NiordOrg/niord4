import { type Signal, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import {
  BehaviorSubject,
  type Observable,
  catchError,
  combineLatest,
  of,
  switchMap,
  tap,
} from 'rxjs';

/**
 * A read whose rows fill a picker or a list, where a failure must not look like a list with
 * nothing in it: the rows read empty until they arrive and after a failure, `failed()` tells the
 * view to say so, and `retry()` reads again with the same key.
 */
export interface ListRead<T> {
  readonly value: Signal<readonly T[]>;
  readonly failed: Signal<boolean>;
  retry(): void;
}

/**
 * Reads `read(key)` for every key `keys$` emits (the newest wins) and again on `retry()`. Runs in
 * an injection context, like the `toSignal` it is built on, and is subscribed for the life of
 * that context. A source that answers synchronously fills the value synchronously.
 */
export function listRead<T, K>(
  keys$: Observable<K>,
  read: (key: K) => Observable<readonly T[]>,
): ListRead<T> {
  const failed = signal(false);
  const retry$ = new BehaviorSubject<void>(undefined);
  const value = toSignal(
    combineLatest([keys$, retry$]).pipe(
      switchMap(([key]) =>
        read(key).pipe(
          tap(() => failed.set(false)),
          catchError(() => {
            failed.set(true);
            return of<readonly T[]>([]);
          }),
        ),
      ),
    ),
    { initialValue: [] as readonly T[] },
  );
  return { value, failed: failed.asReadonly(), retry: () => retry$.next() };
}

/** {@link listRead} of a read that takes no key. */
export function fixedListRead<T>(read: () => Observable<readonly T[]>): ListRead<T> {
  return listRead(of(undefined), read);
}
