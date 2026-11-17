import { inject, Injectable } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router } from '@angular/router';
import { filter } from 'rxjs';

/** Where Back lands when no messages view has been visited yet (the messages list). */
const MESSAGES_FALLBACK_URL = '/messages';

/** Whether a URL belongs to the messages area (exact, or a `/messages/…` / `/messages?…` sub-URL). */
function isMessagesUrl(url: string): boolean {
  return (
    url === MESSAGES_FALLBACK_URL ||
    url.startsWith(`${MESSAGES_FALLBACK_URL}/`) ||
    url.startsWith(`${MESSAGES_FALLBACK_URL}?`)
  );
}

/**
 * Remembers the last messages-area URL the user visited (full path incl. query params) so the
 * editor's Back affordance returns to exactly the table / grid / details / map view they came
 * from, with their filters intact. Instantiated at bootstrap (an app initializer touches it) so
 * its subscription is live before the navigation that opens the editor. Dumb + in-memory only —
 * no persistence.
 */
@Injectable({ providedIn: 'root' })
export class LastMessagesViewService {
  private lastMessagesUrl = MESSAGES_FALLBACK_URL;

  constructor() {
    inject(Router)
      .events.pipe(
        filter((event): event is NavigationEnd => event instanceof NavigationEnd),
        takeUntilDestroyed(),
      )
      .subscribe((event) => {
        if (isMessagesUrl(event.urlAfterRedirects)) {
          this.lastMessagesUrl = event.urlAfterRedirects;
        }
      });
  }

  /** The last visited messages URL, or `/messages` when none has been visited. */
  lastUrl(): string {
    return this.lastMessagesUrl;
  }
}
