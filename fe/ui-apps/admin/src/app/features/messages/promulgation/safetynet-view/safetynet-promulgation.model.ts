import type { BasePromulgation } from '../../models/message.model';

export type SafetynetPriority = 'SAFETY' | 'URGENCY' | 'DISTRESS';

/** A selectable SafetyNET broadcast area. */
export interface SafetyNetArea {
  readonly name: string;
}

export interface SafetynetPromulgation extends BasePromulgation {
  readonly priority?: SafetynetPriority;
  readonly areas?: SafetyNetArea[];
  readonly areaName?: string;
  readonly safetyNetId?: string;
  readonly text?: string;
}
