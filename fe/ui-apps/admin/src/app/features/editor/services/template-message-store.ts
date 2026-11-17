import { Injectable, signal } from '@angular/core';

import type { Message } from '../../messages/models/message.model';

/**
 * Transient hand-off for the create-from-template path. The AtoN / category-template
 * "create message" action sets a template message here, then navigates into the editor's
 * template route, which consumes it once.
 *
 * Root-provided (NOT route-scoped) on purpose: the setter lives outside the editor route,
 * so the producer and the editor must share a single instance that survives the navigation
 * into the editor. The editor consumes it on entry, leaving the store empty afterwards.
 */
@Injectable({ providedIn: 'root' })
export class TemplateMessageStore {
  private readonly _template = signal<Message | undefined>(undefined);
  readonly template = this._template.asReadonly();

  set(message: Message): void {
    this._template.set(message);
  }

  /** Read and clear in one step — the template is single-use per editor entry. */
  consume(): Message | undefined {
    const message = this._template();
    this._template.set(undefined);
    return message;
  }

  clear(): void {
    this._template.set(undefined);
  }
}
