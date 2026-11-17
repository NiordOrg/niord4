import { Pipe, PipeTransform } from '@angular/core';
import { MessageStatus } from '../models/message-status.model';

/**
 * Maps a `MessageStatus` to its Layer 3 state class (`.is-published`, etc.),
 * or `''` when there is no status. Shared by {@link StatusClassPipe} (template
 * use) and component-side computed signals (e.g. `MessageIdField.statusStateClass`)
 * so the status→class mapping lives in one place.
 */
export function messageStatusToStateClass(status: MessageStatus | null | undefined): string {
  return status ? `is-${status.toLowerCase()}` : '';
}

/**
 * Maps a `MessageStatus` enum value to its Layer 3 state class name.
 *
 * The state class (`.is-published`, `.is-draft`, etc., defined in
 * `fe/styles/components-core.css`) exposes the status's tokens as locally-
 * scoped custom properties (`--status-bg`, `--status-fg`, `--status-bd`).
 * Any Layer 3 widget class (`.status-badge`, `.status-chip`, `.status-dot`,
 * `.status-row`) reads these locals — so the same pipe drives every
 * status-aware widget without per-widget knowledge.
 *
 * Usage:
 * ```html
 * <span class="status-badge" [class]="message().status | statusClass">
 *   {{ message().id }}
 * </span>
 * ```
 */
@Pipe({
  name: 'statusClass',
  standalone: true,
  pure: true,
})
export class StatusClassPipe implements PipeTransform {
  transform(status: MessageStatus | undefined | null): string {
    return messageStatusToStateClass(status);
  }
}
