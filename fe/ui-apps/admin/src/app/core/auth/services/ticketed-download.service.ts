import { DOCUMENT, Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { AuthTicketService, TicketRole } from './auth-ticket.service';

/**
 * One home for the export/download pattern used across the app: optionally
 * fetch a one-time ticket, append it to the download URL, then open the URL in
 * a new tab (exports render/download in their own tab, leaving the page state
 * untouched).
 *
 * `openPublic` is the explicit no-ticket mode for PermitAll endpoints — adding
 * a ticket there would be misleading about the endpoint's auth contract.
 */
@Injectable({ providedIn: 'root' })
export class TicketedDownloadService {
  private readonly tickets = inject(AuthTicketService);
  private readonly document = inject(DOCUMENT);

  /** Open a public (PermitAll) download URL directly — no ticket round-trip. */
  openPublic(url: string): void {
    this.open(url);
  }

  /** Fetch a one-time ticket (optionally role-scoped) and open the URL with `?ticket=`. */
  async openTicketed(url: string, role?: TicketRole): Promise<void> {
    const ticket = await firstValueFrom(this.tickets.fetch(role));
    const separator = url.includes('?') ? '&' : '?';
    this.open(`${url}${separator}ticket=${encodeURIComponent(ticket)}`);
  }

  private open(url: string): void {
    this.document.defaultView?.open(url, '_blank', 'noopener');
  }
}
