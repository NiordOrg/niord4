import {
  type EnvironmentProviders,
  InjectionToken,
  makeEnvironmentProviders,
  type Type,
} from '@angular/core';

/**
 * The named places in the site's templates where a country may add a component of its own. A base
 * template names each one with `<app-slot name="…" />`, and a slot is a promise the site keeps:
 * it stays where it is, and without a registration it renders nothing.
 */
export type SlotName = 'layout.beforeFooter';

/**
 * Fetches the component a slot shows. It is a dynamic `import()`, so the component lands in a lazy
 * chunk of its own and never in what every first visit transfers.
 */
export type SlotLoader = () => Promise<Type<unknown>>;

export interface SlotRegistration {
  readonly slot: SlotName;
  readonly load: SlotLoader;
}

/** Every registration, in the order the country's providers name them. */
export const COMPONENT_SLOTS = new InjectionToken<readonly SlotRegistration[]>('COMPONENT_SLOTS');

/**
 * Adds a component to a slot. The component takes no inputs: what it shows it reads itself, from
 * the services and the translations every page has.
 */
export function provideSlot(slot: SlotName, load: SlotLoader): EnvironmentProviders {
  return makeEnvironmentProviders([
    { provide: COMPONENT_SLOTS, useValue: { slot, load } satisfies SlotRegistration, multi: true },
  ]);
}
