import { describe, expect, it } from 'vitest';

import { MessageCopyDialogService } from './message-copy-dialog.service';

describe('MessageCopyDialogService', () => {
  it('open() seeds the message id + main type and reveals the dialog', () => {
    const service = new MessageCopyDialogService();
    service.open('m-1', 'NM').subscribe();
    expect(service.messageId()).toBe('m-1');
    expect(service.mainType()).toBe('NM');
    expect(service.visible()).toBe(true);
  });

  it('confirm(true) resolves the copy route WITH the REFERENCE back-reference', () => {
    const service = new MessageCopyDialogService();
    let result: string[] | undefined;
    service.open('m-1', 'NM').subscribe((r) => (result = r));
    service.confirm(true);
    expect(result).toEqual(['/editor/copy', 'm-1', 'REFERENCE']);
    expect(service.visible()).toBe(false);
  });

  it('confirm(false) resolves the plain copy route (no reference)', () => {
    const service = new MessageCopyDialogService();
    let result: string[] | undefined;
    service.open('m-1', 'NW').subscribe((r) => (result = r));
    service.confirm(false);
    expect(result).toEqual(['/editor/copy', 'm-1']);
  });

  it('dismiss() resolves undefined (cancel is a no-op) and hides', () => {
    const service = new MessageCopyDialogService();
    let called = false;
    let result: string[] | undefined = ['sentinel'];
    service.open('m-1', 'NM').subscribe((r) => {
      called = true;
      result = r;
    });
    service.dismiss();
    expect(called).toBe(true);
    expect(result).toBeUndefined();
    expect(service.visible()).toBe(false);
  });
});
