/**
 * A country API in the form the kit reads (country-kit/api.test.mjs): one current export and one
 * deprecated, as a later version of `@niord/admin/api` would carry them.
 */

/** The current greeting, which takes the name to greet. */
export { greet } from './app/greet';

/**
 * The old greeting, without a name.
 *
 * Deprecated since 4.0.200: use greet, which takes the name.
 */
export { sayHello } from './app/greet';

/** The greeting's options. */
export type { GreetOptions } from './app/greet';
