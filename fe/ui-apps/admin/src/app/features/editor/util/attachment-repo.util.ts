import type { Message, MessageAttachment } from '../../messages/models/message.model';

/**
 * Whether a repo path belongs to the message's CURRENT (unsaved) revision — i.e. it begins with
 * `editRepoPath + '/' + revision + '/'`. Physical repo-file deletes are allowed only then; files
 * from earlier saved revisions must survive to preserve history.
 */
export function isCurrentRevisionPath(message: Message, path: string | undefined): boolean {
  if (!path || !message.editRepoPath || message.revision === undefined) {
    return false;
  }
  return path.startsWith(`${message.editRepoPath}/${message.revision}/`);
}

/**
 * The repository path of an attachment file within the message's current edit revision — the DELETE
 * target for a just-uploaded attachment. Note the fileName is URL-encoded here while
 * `attachment.path` arrives pre-encoded from the server.
 */
export function attachmentEditRepoPath(message: Message, attachment: MessageAttachment): string {
  return `${message.editRepoPath}/${message.revision}/${encodeURIComponent(attachment.fileName ?? '')}`;
}
