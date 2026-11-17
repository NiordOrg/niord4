import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable, map, of } from 'rxjs';

import { APP_CONFIG } from '../../config/app-config.token';
import { type DisplayUser, toDisplayUser } from '../models/display-user.model';
import type { UserSearchResult } from '../models/user-search-result.model';

@Injectable({ providedIn: 'root' })
export class UserDirectoryApiService {
  private readonly http = inject(HttpClient);
  private readonly apiBaseUrl = inject(APP_CONFIG).apiBaseUrl;

  search(name: string): Observable<UserSearchResult[]> {
    if (!name || name.length === 0) {
      return of([]);
    }
    const params = new HttpParams().set('name', name);
    return this.http.get<UserSearchResult[]>(`${this.apiBaseUrl}/users/search`, { params });
  }

  /** `search` adapted to the `DisplayUser` UI model — the user field consumes this. */
  searchDisplay(name: string): Observable<DisplayUser[]> {
    return this.search(name).pipe(map((users) => users.map(toDisplayUser)));
  }

  /** Returns email addresses matching `name` from the Niord user directory. */
  searchEmails(name: string): Observable<readonly string[]> {
    if (!name || name.length === 0) {
      return of([]);
    }
    const params = new HttpParams().set('name', name);
    return this.http.get<readonly string[]>(`${this.apiBaseUrl}/users/search-emails`, { params });
  }
}
