import { signal, type Provider, type Signal } from '@angular/core';

import { AuthFacade, type AuthRole } from '../../core/auth/facades/auth.facade';
import { DomainFacade } from '../../core/domain/facades/domain.facade';

export interface FakeAuthOptions {
  readonly isAuthenticated?: boolean;
  readonly editor?: boolean;
  readonly admin?: boolean;
  readonly sysadmin?: boolean;
  /**
   * `hasDomainUserRole`: the backend's `user` role in the session domain. Defaults to
   * `isAuthenticated`, so a spec that sets only that sees a signed-in user with a role here.
   */
  readonly domainUserRole?: boolean;
}

type FakeAuthFacade = Pick<
  AuthFacade,
  'isAuthenticated' | 'hasRole' | 'hasAnyRole' | 'hasDomainUserRole'
>;

/**
 * Returns a `Provider` that stubs `AuthFacade` with signal-backed role flags.
 * Tests can set the initial state via `opts`; mutation after construction is
 * out of scope (re-configure TestBed if a scenario flips roles mid-flow).
 *
 * Replaces the ~25-line FakeAuth boilerplate that was previously copy-pasted
 * across every messages-feature spec that touches role gating.
 */
export function provideFakeAuthFacade(opts: FakeAuthOptions = {}): Provider {
  const isAuthenticated = signal(opts.isAuthenticated ?? false);
  const editor = signal(opts.editor ?? false);
  const admin = signal(opts.admin ?? false);
  const sysadmin = signal(opts.sysadmin ?? false);
  const hasDomainUserRole = signal(opts.domainUserRole ?? opts.isAuthenticated ?? false);
  const auth: FakeAuthFacade = {
    isAuthenticated,
    hasDomainUserRole,
    hasRole: (role: AuthRole): Signal<boolean> => {
      switch (role) {
        case 'editor':
          return editor;
        case 'admin':
          return admin;
        case 'sysadmin':
          return sysadmin;
        case 'user':
          return isAuthenticated;
      }
    },
    hasAnyRole(roles: readonly AuthRole[]): boolean {
      return roles.some((role) => this.hasRole(role)());
    },
  };
  return { provide: AuthFacade, useValue: auth };
}

export interface FakeDomainOptions {
  /** Static result for `canEditMessageSeries(_)` regardless of the series id. */
  readonly canEditSeries?: boolean;
}

/**
 * Returns a `Provider` that stubs `DomainFacade.canEditMessageSeries(_)` with
 * a fixed boolean (signal-wrapped to match the real signature). Specs that
 * need more granular per-series control can still build their own stub.
 */
export function provideFakeDomainFacade(opts: FakeDomainOptions = {}): Provider {
  const result = signal(opts.canEditSeries ?? false);
  const domain: Pick<DomainFacade, 'canEditMessageSeries'> = {
    canEditMessageSeries: (): Signal<boolean> => result,
  };
  return { provide: DomainFacade, useValue: domain };
}
