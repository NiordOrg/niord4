import { environment } from '../../../environments/environment';
import { installBootResourceHints } from './boot-resource-hints';

/**
 * Side-effecting module that warms the boot origins, kept separate from `boot-resource-hints.ts`
 * so those helpers stay pure and unit-testable.
 *
 * This MUST be the first import in `main.ts`. ES module bodies run depth-first in import order, so
 * importing it first runs this line before the Angular, rxjs and keycloak-js module bodies —
 * putting the handshakes in flight for the whole bundle-evaluation window. Imported last (or
 * inlined at the bottom of `main.ts`, which is the bundle ENTRY and therefore evaluates after
 * every other module) it would fire only a moment before the requests it is meant to precede,
 * and buy nothing.
 */
installBootResourceHints(document, environment);
