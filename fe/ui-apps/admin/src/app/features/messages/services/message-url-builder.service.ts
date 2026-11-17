import { inject, Injectable } from '@angular/core';
import { encodeRepoPath } from '@niord/html/repo-path';

import { APP_CONFIG } from '../../../core/config/app-config.token';

@Injectable({ providedIn: 'root' })
export class MessageUrlBuilderService {
  private readonly apiBaseUrl = inject(APP_CONFIG).apiBaseUrl;

  // A repository path is encoded segment by segment on its way into an address. The backend
  // hands paths out both encoded ("messages/.../my%20file.png") and as uploaded, and
  // encodeRepoPath gives the same address for both, so an encoded path is not encoded twice and
  // a `#` or `?` in a file name stays part of the name.
  getAttachmentThumbnailUrl(path: string, size = 40): string {
    if (!path) {
      return '';
    }
    return `${this.apiBaseUrl}/repo/thumb/${encodeRepoPath(path)}?size=${size}`;
  }

  getAttachmentFileUrl(path: string): string {
    if (!path) {
      return '';
    }
    return `${this.apiBaseUrl}/repo/file/${encodeRepoPath(path)}`;
  }

  thumbnailUrl(messageId: string): string {
    return `${this.apiBaseUrl}/message-map-image/${encodeURIComponent(messageId)}.png`;
  }
}
