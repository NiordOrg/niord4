import { EnvironmentProviders, makeEnvironmentProviders } from '@angular/core';

/**
 * The country's behaviour hooks and component slots. A country provides its hook tokens (a
 * promulgation renderer or editor for its own service, say) and its `provideSlot` registrations
 * here; the application adds them at its root injector, beside the core registrations. The base
 * default adds nothing.
 */
export function provideCountry(): EnvironmentProviders {
  return makeEnvironmentProviders([]);
}
