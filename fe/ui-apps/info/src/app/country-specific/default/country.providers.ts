import { type EnvironmentProviders, makeEnvironmentProviders } from '@angular/core';

/**
 * The country's behaviour hooks and slot registrations: a country adds a component to a named place
 * in the site's templates here with `provideSlot` (`shared/slots/component-slots.ts`). The base
 * product adds nothing.
 */
export function provideCountry(): EnvironmentProviders {
  return makeEnvironmentProviders([]);
}
