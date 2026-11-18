import { Injectable, signal } from '@angular/core';

import type { Message } from '../../messages/models/message.model';

/** What a launcher hands to the execution view: the working-copy seed + where Cancel returns. */
export interface TemplateExecutionSeed {
  /**
   * The initial working copy — a placeholder `{ id:'', mainType, categories }` (the Start screen's
   * template pick) or a clone of the editor's working message, saved or not (re-run).
   */
  readonly message: Message;
  /** The URL Cancel returns to: `/editor` (create) or the launching edit URL (re-run). */
  readonly returnUrl: string;
  /**
   * The message is a placeholder that only names the main type and the chosen templates; the
   * execution view starts from a fresh server blank of that type. False for a working copy, which
   * is used as it is, unsaved changes included.
   */
  readonly blank: boolean;
}

/**
 * Transient hand-off INTO the template-execution view. The two launchers — the Start screen's
 * "from a template" pick (a placeholder seed, `blank`) and the editor's in-place "Run template…"
 * button (a clone of the working message) — set the seed here, then navigate to the
 * `execute-template` route, which consumes it once on entry.
 *
 * Root-provided (NOT route-scoped) on purpose: the setters live outside the execution route, so the
 * producers and the view must share a single instance that survives the navigation. Distinct from
 * {@link TemplateMessageStore}, which carries the FINISHED execution result the other way (execution
 * view → the edit route) — keeping the two directions on separate stores avoids any consume ordering
 * coupling on the shared execution route.
 */
@Injectable({ providedIn: 'root' })
export class TemplateExecutionSeedStore {
  private readonly _seed = signal<TemplateExecutionSeed | undefined>(undefined);

  set(message: Message, returnUrl: string, blank = false): void {
    this._seed.set({ message, returnUrl, blank });
  }

  /** Read and clear in one step — the seed is single-use per execution-view entry. */
  consume(): TemplateExecutionSeed | undefined {
    const seed = this._seed();
    this._seed.set(undefined);
    return seed;
  }

  clear(): void {
    this._seed.set(undefined);
  }
}
