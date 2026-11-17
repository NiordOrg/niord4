import { InjectionToken, Signal, Type } from '@angular/core';

import type { BasePromulgation, MessageStatus } from '../../messages/models/message.model';

/**
 * A registered per-service promulgation EDITOR — the editable counterpart of
 * `PROMULGATION_RENDERERS`. Core registers navtex/safetynet/twitter/audio; a country registers the
 * editor of a service of its own. The `PromulgationEditorOutlet` dispatches by
 * `promulgation.type.serviceId`.
 */
export interface PromulgationEditor {
  readonly serviceId: string;
  readonly component: Type<unknown>;
}

export const PROMULGATION_EDITORS = new InjectionToken<readonly PromulgationEditor[]>(
  'PROMULGATION_EDITORS',
);

/**
 * The callback/context bundle every channel editor receives AS AN INPUT (alongside its typed
 * `promulgation` entry) — `NgComponentOutlet` binds inputs only. It decouples each editor (the base
 * product's and a country's alike) from the facade and the field host: the editor calls these to
 * mutate its entry and reads the busy / status signals for its own UI state.
 */
export interface PromulgationEditorContext {
  /** Immutably patch the entry matched by `typeId` (no adjust). Carries channel-specific fields. */
  patch(typeId: string, partial: Partial<BasePromulgation> & Record<string, unknown>): void;
  /** Confirm + generate the channel text (deep-merged back into the entry by `typeId`). */
  generate(typeId: string): void;
  /** Reformat the entry's body text via the singular-root reformat endpoint. */
  reformat(serviceId: string, typeId: string): void;
  /** Whether a generate/reformat round-trip is in flight for the given type. */
  busy(typeId: string): boolean;
  /** The working message status (reactive) — drives the SafetyNET id gate (editable only PUBLISHED). */
  readonly messageStatus: Signal<MessageStatus | undefined>;
}
