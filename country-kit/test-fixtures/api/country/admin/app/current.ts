import { greet } from '@niord/admin/api';

/** A country file of the fixture that uses only the current greeting: no warning. */
export const current = (): string => greet('pier');
