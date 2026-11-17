import { computed, signal } from '@angular/core';

/**
 * A generic, signal-backed undo/redo history of immutable `T` snapshots.
 *
 * The store is pure bookkeeping: the owner snapshots its state (cloning if the state is mutable)
 * before `push`, and applies the returned snapshot on `undo` / `redo`. It is framework-light —
 * usable by any editor surface that wants step-back / step-forward: the geometry editor
 * (`T = OlFeature[]`), the edit-as-text dialog (`T = string`), and so on.
 *
 * `canUndo` / `canRedo` are signals, so they can drive a button's `disabled` state directly.
 *
 * Snapshots are treated as immutable — never hand the store a reference you keep mutating, or an
 * undo will return an object that has already changed underneath it.
 */
export class HistoryStore<T> {
  private readonly undoStack = signal<readonly T[]>([]);
  private readonly redoStack = signal<readonly T[]>([]);
  /** The snapshot matching the current live state — the pivot undo/redo move around. */
  private currentState!: T;
  /** Whether `currentState` holds a real snapshot yet. Tracked explicitly (rather than overloading
   * `undefined` as "no baseline") so a `T` that legitimately includes `undefined` works correctly. */
  private hasState = false;

  readonly canUndo = computed(() => this.undoStack().length > 0);
  readonly canRedo = computed(() => this.redoStack().length > 0);

  /** @param limit caps retained undo depth — the oldest entries drop once it is exceeded. */
  constructor(private readonly limit = 100) {}

  /** Re-baselines to `state`, discarding all undo/redo history (a fresh document / import). */
  reset(state: T): void {
    this.currentState = state;
    this.hasState = true;
    this.undoStack.set([]);
    this.redoStack.set([]);
  }

  /** Records `state` as the new current step and clears the redo branch. */
  push(state: T): void {
    if (this.hasState) {
      const baseline = this.currentState;
      this.undoStack.update((stack) => {
        const next = [...stack, baseline];
        return next.length > this.limit ? next.slice(next.length - this.limit) : next;
      });
    }
    this.currentState = state;
    this.hasState = true;
    this.redoStack.set([]);
  }

  /** Steps back one step, returning the snapshot to restore (or `undefined` if there is none). */
  undo(): T | undefined {
    const stack = this.undoStack();
    if (stack.length === 0 || !this.hasState) return undefined;
    const previous = stack[stack.length - 1];
    this.redoStack.update((redo) => [...redo, this.currentState]);
    this.undoStack.set(stack.slice(0, -1));
    this.currentState = previous;
    return previous;
  }

  /** Steps forward one step, returning the snapshot to restore (or `undefined` if there is none). */
  redo(): T | undefined {
    const stack = this.redoStack();
    if (stack.length === 0 || !this.hasState) return undefined;
    const next = stack[stack.length - 1];
    this.undoStack.update((undo) => [...undo, this.currentState]);
    this.redoStack.set(stack.slice(0, -1));
    this.currentState = next;
    return next;
  }
}
