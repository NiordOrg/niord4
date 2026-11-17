import { Injectable } from '@angular/core';

@Injectable({ providedIn: 'root' })
export class MessageAttachmentTypeService {
  isImage(type: string | undefined): boolean {
    return !!type && type.startsWith('image/');
  }

  isVideo(type: string | undefined): boolean {
    return !!type && type.startsWith('video/');
  }

  isPreviewable(type: string | undefined): boolean {
    return this.isImage(type) || this.isVideo(type);
  }
}
