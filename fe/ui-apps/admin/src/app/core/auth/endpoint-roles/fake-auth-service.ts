import { signal, type Provider } from '@angular/core';
import type Keycloak from 'keycloak-js';

import type { Domain } from '../../domain/models/domain.model';
import { DomainService } from '../../domain/services/domain.service';
import { KEYCLOAK } from '../services/keycloak.token';
import offers from './role-offers.json';

/**
 * The identities of the backend's endpoint table, signed in as the frontend sees them.
 *
 * Each identity's token claims (`resource_access` per client) are written in `role-offers.json`
 * beside this file, and the endpoint-role guard (`fe/scripts/endpoint-roles.test.mjs`) holds them
 * to the roles the backend's test realm (`be/niord/src/test/resources/keycloak/niord-realm.json`)
 * gives the same user, composites and groups expanded: a frontend `editor` here is the backend's
 * `editor`.
 *
 * Nothing above Keycloak is faked: the spec runs the real `AuthService` (`init()` captures the
 * session from the token, as in production) and the real `AuthFacade` (its `holdsInSession` reads
 * the base client and the session domain's client). Only Keycloak (a stub that hands over the
 * claims) and the session domain (`DomainService`, which would otherwise load the domains over
 * HTTP) are replaced.
 */
export type IdentityName = keyof typeof offers.identities;

export const IDENTITIES = Object.keys(offers.identities) as IdentityName[];

/** The message series each domain of the spec offers: a message of `niord-nw` is editable there. */
export const SERIES_OF: Readonly<Record<string, string>> = {
  'niord-nw': 'sweep-frontend-nw-series',
  'niord-nm': 'sweep-frontend-nm-series',
};

function domain(domainId: string): Domain {
  return {
    domainId,
    name: domainId,
    active: true,
    messageSeries: [{ seriesId: SERIES_OF[domainId], mainType: 'NW', types: [] }],
    publish: true,
    atons: true,
    templates: true,
    firingSchedule: true,
  };
}

/** A Keycloak stub that hands the identity's claims to `AuthService.init()`. */
function fakeKeycloak(name: IdentityName): Keycloak {
  const identity = offers.identities[name];
  const signedIn = identity.username !== null;
  const resourceAccess = Object.fromEntries(
    Object.entries(identity.resourceAccess).map(([client, roles]) => [client, { roles }]),
  );
  const stub = {
    token: signedIn ? `fake-token-${name}` : undefined,
    tokenParsed: signedIn
      ? {
          sub: `sweep-frontend-${name}`,
          resource_access: resourceAccess,
          realm_access: { roles: [] },
        }
      : undefined,
    idTokenParsed: signedIn
      ? { sub: `sweep-frontend-${name}`, preferred_username: identity.username ?? undefined }
      : undefined,
    authenticated: signedIn,
    init: (): Promise<boolean> => Promise.resolve(signedIn),
    updateToken: (): Promise<boolean> => Promise.resolve(false),
    login: (): Promise<void> => Promise.reject(new Error('fake Keycloak: login() called')),
    logout: (): Promise<void> => Promise.resolve(),
    accountManagement: (): Promise<void> => Promise.resolve(),
  };
  return stub as unknown as Keycloak;
}

/**
 * The providers that sign the spec in as `name` in its session domain. Call
 * `TestBed.inject(AuthService).init()` after configuring the module, as the application's
 * initializer does.
 */
export function provideIdentity(name: IdentityName): Provider[] {
  const identity = offers.identities[name];
  const domains = Object.keys(SERIES_OF).map(domain);
  const session = domains.find((d) => d.domainId === identity.sessionDomain);
  const domainService: Partial<Record<keyof DomainService, unknown>> = {
    availableDomains: signal(domains),
    sessionDomain: signal(session),
    searchDomain: signal(undefined),
    resolveById: (id: string) => domains.find((d) => d.domainId === id),
    setSessionDomain: () => undefined,
    setSearchDomain: () => undefined,
  };
  return [
    { provide: KEYCLOAK, useValue: fakeKeycloak(name) },
    { provide: DomainService, useValue: domainService },
  ];
}
