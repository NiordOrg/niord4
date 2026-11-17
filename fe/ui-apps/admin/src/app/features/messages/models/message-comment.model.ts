/**
 * A comment on a message. Comments are a thread of editor/reviewer notes
 * with optional e-mail notification and an acknowledge step.
 *
 * `emailAddresses` drives the "Save and Send" flow — when non-empty the backend
 * mails each address. `ownComment` is computed server-side and gates whether the current
 * user may edit the comment (own comment or admin).
 */
export interface MessageComment {
  readonly id?: string;
  readonly comment: string;
  readonly user?: string;
  readonly created?: string;
  readonly acknowledgeDate?: string;
  readonly acknowledgedBy?: string;
  readonly ownComment?: boolean;
  readonly emailAddresses?: readonly string[];
}
