import { InjectionToken, Type } from '@angular/core';

import type { BasePromulgation } from '../models/message.model';

export interface PromulgationRenderer {
  readonly serviceId: string;
  readonly component: Type<unknown>;
}

export const PROMULGATION_RENDERERS = new InjectionToken<readonly PromulgationRenderer[]>(
  'PROMULGATION_RENDERERS',
);

export type { BasePromulgation };
