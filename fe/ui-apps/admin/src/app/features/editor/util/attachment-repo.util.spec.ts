import { describe, expect, it } from 'vitest';

import type { Message, MessageAttachment } from '../../messages/models/message.model';
import { attachmentEditRepoPath, isCurrentRevisionPath } from './attachment-repo.util';

const message = (over: Partial<Message> = {}): Message => ({
  id: 'm-1',
  editRepoPath: 'messages/uid-1',
  revision: 3,
  ...over,
});

describe('isCurrentRevisionPath', () => {
  it('is true for a path under the current editRepoPath/revision prefix', () => {
    expect(isCurrentRevisionPath(message(), 'messages/uid-1/3/photo.png')).toBe(true);
  });

  it('is false for a path from an earlier saved revision (history is preserved)', () => {
    expect(isCurrentRevisionPath(message({ revision: 3 }), 'messages/uid-1/2/photo.png')).toBe(
      false,
    );
  });

  it('is false when the message carries no editRepoPath', () => {
    expect(isCurrentRevisionPath(message({ editRepoPath: undefined }), 'messages/uid-1/3/x')).toBe(
      false,
    );
  });

  it('is false when the revision is undefined', () => {
    expect(isCurrentRevisionPath(message({ revision: undefined }), 'messages/uid-1/3/x')).toBe(
      false,
    );
  });

  it('is false for an undefined path', () => {
    expect(isCurrentRevisionPath(message(), undefined)).toBe(false);
  });
});

describe('attachmentEditRepoPath', () => {
  it('builds editRepoPath/revision/<encoded fileName> — the physical DELETE target', () => {
    const attachment: MessageAttachment = { fileName: 'photo.png' };
    expect(attachmentEditRepoPath(message(), attachment)).toBe('messages/uid-1/3/photo.png');
  });

  it('URL-encodes a fileName with spaces / reserved characters', () => {
    const attachment: MessageAttachment = { fileName: 'my file (1).png' };
    expect(attachmentEditRepoPath(message(), attachment)).toBe(
      'messages/uid-1/3/my%20file%20(1).png',
    );
  });

  it('encodes an empty string for a missing fileName (no crash)', () => {
    expect(attachmentEditRepoPath(message(), {})).toBe('messages/uid-1/3/');
  });
});
