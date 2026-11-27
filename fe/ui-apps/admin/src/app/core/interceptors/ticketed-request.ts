import { HttpContextToken } from '@angular/common/http';

/**
 * Marks a request whose one credential is the one-time ticket in its address, as the browser
 * sends it when it opens the address itself: a print the application fetches to save. The
 * backend answers such a request as the ticket's user, role and domain, so `authTokenInterceptor`
 * adds no bearer to it (nor refreshes the token, nor ends the session when a spent ticket is
 * refused with 401) and `niordDomainInterceptor` adds no domain header.
 */
export const TICKETED_REQUEST = new HttpContextToken<boolean>(() => false);
