import { HttpContextToken } from '@angular/common/http';

/**
 * Marks a read the application makes on its own, with no act of the user behind it (the notice of
 * the user's failed mails). Such a read must not touch the session: `authTokenInterceptor` sends it
 * with the current token only while that token is still fresh, never refreshes the token for it
 * (a refresh would keep an idle session alive past the realm's idle timeout), and never drops the
 * session to guest when it fails. A read the token is too old for is refused with
 * {@link BackgroundReadSkipped} before it leaves the browser.
 */
export const BACKGROUND_READ = new HttpContextToken<boolean>(() => false);

/** A background read was not sent because the session's token would have needed a refresh. */
export class BackgroundReadSkipped extends Error {
  constructor() {
    super('Background read skipped: the token would need a refresh');
    this.name = 'BackgroundReadSkipped';
  }
}
