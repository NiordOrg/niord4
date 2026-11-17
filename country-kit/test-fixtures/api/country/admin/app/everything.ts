import * as api from '@niord/admin/api';

/** A country file of the fixture that takes the whole entry point, the deprecated export with it. */
export const hello = (): string => api.greet('quay');
