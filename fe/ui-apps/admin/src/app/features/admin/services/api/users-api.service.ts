import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';

import { APP_CONFIG } from '../../../../core/config/app-config.token';
import type { HierarchicalEntity } from '../../../../shared/components/entity-tree/hierarchical-entity.model';

export interface User {
  /** Keycloak's UUID — blank on add; the update/delete/group-endpoints path id. */
  readonly keycloakId: string;
  /** The human key — required, immutable on edit (the mailing-list membership key). */
  readonly username: string;
  readonly email: string;
  readonly firstName?: string;
  readonly lastName?: string;
  readonly language?: string;
  /** Write-only — blank means "unchanged". Never returned by the search. */
  readonly keycloakPassword?: string;
  /** Keycloak required-actions — sent on ADD only. */
  readonly keycloakActions?: readonly string[];
}

/** A Keycloak group node. Satisfies `HierarchicalEntity` so it renders in the shared tree. */
export interface Group extends HierarchicalEntity {
  /** Displayed in the membership list rows. */
  readonly path?: string;
}

interface UserVo {
  keycloakId?: string | null;
  username: string;
  email?: string | null;
  firstName?: string | null;
  lastName?: string | null;
  language?: string | null;
}

const fromVo = (vo: UserVo): User => ({
  keycloakId: vo.keycloakId ?? '',
  username: vo.username,
  email: vo.email ?? '',
  firstName: vo.firstName ?? undefined,
  lastName: vo.lastName ?? undefined,
  language: vo.language ?? undefined,
});

/**
 * Keycloak user administration via the backend proxy. Contract:
 * - the search returns NO total count — load-more paging only (`first`/`max`);
 * - update/delete path id is the `keycloakId` and the PUT path id must equal
 *   the body's (never the `username` — that is the membership key);
 * - the group tree + per-user memberships are domain-scoped SERVER-side
 *   (the backend reads the current domain).
 */
@Injectable({ providedIn: 'root' })
export class UsersApiService {
  private readonly http = inject(HttpClient);
  private readonly apiBaseUrl = inject(APP_CONFIG).apiBaseUrl;

  search(filter: string, first: number, max: number): Observable<User[]> {
    const params = new HttpParams().set('search', filter).set('first', first).set('max', max);
    return this.http
      .get<UserVo[]>(`${this.apiBaseUrl}/users/kc-users`, { params })
      .pipe(map((vos) => vos.map(fromVo)));
  }

  create(user: User): Observable<unknown> {
    return this.http.post(`${this.apiBaseUrl}/users/kc-user/`, user);
  }

  update(user: User): Observable<unknown> {
    return this.http.put(
      `${this.apiBaseUrl}/users/kc-user/${encodeURIComponent(user.keycloakId)}`,
      user,
    );
  }

  delete(keycloakId: string): Observable<unknown> {
    return this.http.delete(`${this.apiBaseUrl}/users/kc-user/${encodeURIComponent(keycloakId)}`);
  }

  /** The Keycloak group tree (domain-scoped server-side). */
  getGroups(): Observable<Group[]> {
    return this.http.get<Group[]>(`${this.apiBaseUrl}/users/kc-groups`);
  }

  /** The given user's group memberships (domain-scoped server-side). */
  getUserGroups(keycloakId: string): Observable<Group[]> {
    return this.http.get<Group[]>(
      `${this.apiBaseUrl}/users/kc-user/${encodeURIComponent(keycloakId)}/kc-groups`,
    );
  }

  joinGroup(keycloakId: string, groupId: string): Observable<unknown> {
    return this.http.put(
      `${this.apiBaseUrl}/users/kc-user/${encodeURIComponent(keycloakId)}/kc-groups/${encodeURIComponent(groupId)}`,
      null,
    );
  }

  leaveGroup(keycloakId: string, groupId: string): Observable<unknown> {
    return this.http.delete(
      `${this.apiBaseUrl}/users/kc-user/${encodeURIComponent(keycloakId)}/kc-groups/${encodeURIComponent(groupId)}`,
    );
  }
}
