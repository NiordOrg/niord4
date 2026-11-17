import { describe, expect, it } from 'vitest';

import { HistoryStore } from './history-store';

describe('HistoryStore', () => {
  it('starts with nothing to undo or redo', () => {
    const history = new HistoryStore<string>();
    history.reset('a');
    expect(history.canUndo()).toBe(false);
    expect(history.canRedo()).toBe(false);
    expect(history.undo()).toBeUndefined();
    expect(history.redo()).toBeUndefined();
  });

  it('undo returns the previous snapshot and enables redo', () => {
    const history = new HistoryStore<string>();
    history.reset('a');
    history.push('b');
    history.push('c');

    expect(history.canUndo()).toBe(true);
    expect(history.undo()).toBe('b');
    expect(history.undo()).toBe('a');
    expect(history.canUndo()).toBe(false);
    expect(history.canRedo()).toBe(true);
  });

  it('redo walks back forward through the undone snapshots', () => {
    const history = new HistoryStore<string>();
    history.reset('a');
    history.push('b');
    history.push('c');
    history.undo();
    history.undo();

    expect(history.redo()).toBe('b');
    expect(history.redo()).toBe('c');
    expect(history.canRedo()).toBe(false);
  });

  it('a push after an undo clears the redo branch', () => {
    const history = new HistoryStore<string>();
    history.reset('a');
    history.push('b');
    history.undo();
    expect(history.canRedo()).toBe(true);

    history.push('x');
    expect(history.canRedo()).toBe(false);
    expect(history.undo()).toBe('a');
  });

  it('reset discards all history', () => {
    const history = new HistoryStore<string>();
    history.reset('a');
    history.push('b');
    history.push('c');
    history.reset('fresh');

    expect(history.canUndo()).toBe(false);
    expect(history.canRedo()).toBe(false);
  });

  it('caps retained undo depth at the limit, dropping the oldest', () => {
    const history = new HistoryStore<number>(2);
    history.reset(0);
    history.push(1);
    history.push(2);
    history.push(3); // baseline=3, undo holds [1, 2] (0 dropped past the limit of 2)

    expect(history.undo()).toBe(2);
    expect(history.undo()).toBe(1);
    expect(history.canUndo()).toBe(false); // 0 was dropped
  });
});
