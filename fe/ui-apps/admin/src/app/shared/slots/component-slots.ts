import {
  EnvironmentProviders,
  InjectionToken,
  makeEnvironmentProviders,
  Type,
} from '@angular/core';

/**
 * The named places in the base product's templates where a country may add a component of its own.
 * A base template names each slot with `<app-slot name="…" />`; a country registers its component
 * for one in its country.providers.ts. A slot component takes no inputs.
 */
export type SlotName = 'home.welcome';

/**
 * Loads a slot's component. It is a dynamic `import()`, so the component lands in a lazy chunk of
 * its own and never in the main one.
 */
export type SlotLoader = () => Promise<Type<unknown>>;

export interface SlotRegistration {
  readonly slot: SlotName;
  readonly load: SlotLoader;
}

export const COMPONENT_SLOTS = new InjectionToken<readonly SlotRegistration[]>('COMPONENT_SLOTS');

/** Registers a component for a slot; several for one slot render in registration order. */
export function provideSlot(slot: SlotName, load: SlotLoader): EnvironmentProviders {
  return makeEnvironmentProviders([
    { provide: COMPONENT_SLOTS, useValue: { slot, load } satisfies SlotRegistration, multi: true },
  ]);
}
