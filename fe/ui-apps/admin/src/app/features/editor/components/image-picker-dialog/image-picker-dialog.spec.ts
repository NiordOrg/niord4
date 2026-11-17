import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { TranslocoService } from '@jsverse/transloco';
import { afterEach, describe, expect, it } from 'vitest';

import { APP_CONFIG } from '../../../../core/config/app-config.token';
import { provideNiordIcons } from '../../../../shared/icons/niord-icons';
import { provideTestTransloco } from '../../../../shared/testing/transloco-testing';
import { MessageDescriptionService } from '../../../messages/services/message-description.service';
import type { Message, MessageAttachment } from '../../../messages/models/message.model';
import { ImagePickerDialog } from './image-picker-dialog';

const image = (over: Partial<MessageAttachment> = {}): MessageAttachment => ({
  path: 'messages/uid/3/photo.png',
  fileName: 'photo.png',
  type: 'image/png',
  ...over,
});

@Component({
  imports: [ImagePickerDialog],
  template: `
    <app-image-picker-dialog
      [(visible)]="visible"
      [message]="message()"
      (picked)="picked.set($event)"
    />
  `,
})
class Host {
  readonly visible = signal(false);
  readonly message = signal<Message | undefined>(undefined);
  readonly picked = signal<string | undefined>(undefined);
}

async function setup(message: Message) {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({
    providers: [
      provideTestTransloco(),
      provideNiordIcons(),
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest' } },
      { provide: MessageDescriptionService, useValue: {} },
    ],
  });
  TestBed.inject(TranslocoService).load('en').subscribe();

  const fixture = TestBed.createComponent(Host);
  fixture.componentInstance.message.set(message);
  fixture.autoDetectChanges();
  await fixture.whenStable();
  return fixture;
}

afterEach(() => {
  document.querySelectorAll('.cdk-overlay-container').forEach((el) => el.replaceChildren());
});

describe('ImagePickerDialog', () => {
  it('renders the message attachments as a grid', async () => {
    const fixture = await setup({
      id: 'm',
      attachments: [image({ fileName: 'a.png' }), image({ fileName: 'b.png' })],
    });
    fixture.componentInstance.visible.set(true);
    await fixture.whenStable();

    expect(document.querySelectorAll('app-message-attachment')).toHaveLength(2);
  });

  it('emits the /rest/repo/file/<path> URL on pick and closes', async () => {
    const fixture = await setup({ id: 'm', attachments: [image()] });
    fixture.componentInstance.visible.set(true);
    await fixture.whenStable();

    (document.querySelector('app-message-attachment button') as HTMLButtonElement).click();
    await fixture.whenStable();

    expect(fixture.componentInstance.picked()).toBe('/rest/repo/file/messages/uid/3/photo.png');
    expect(fixture.componentInstance.visible()).toBe(false);
  });

  // The URL becomes an image source in the message HTML: a `#` left raw in it would cut the
  // file name off at the fragment, and a path the server already encoded must not be encoded twice.
  it('encodes the file name into the URL it emits, once', async () => {
    const fixture = await setup({
      id: 'm',
      attachments: [image({ path: 'messages/uid/3/Søkort #12%20ny.png' })],
    });
    fixture.componentInstance.visible.set(true);
    await fixture.whenStable();

    (document.querySelector('app-message-attachment button') as HTMLButtonElement).click();
    await fixture.whenStable();

    expect(fixture.componentInstance.picked()).toBe(
      '/rest/repo/file/messages/uid/3/S%C3%B8kort%20%2312%20ny.png',
    );
  });

  // REPO4: the backend reads a file link back out of a message's HTML up to the first quote,
  // angle bracket, white space, `?` or `#` (RepoPaths.FILE_LINK, mirrored here). An apostrophe or
  // a question mark left raw in a file name cut the link there (`it's.png` read back as `it`), so
  // the import's check and a deleted issue's file clean-up missed a file that was still linked.
  const BACKEND_FILE_LINK = /\/rest\/repo\/file\/([^"'<>\s?#]+)/;

  async function pick(path: string): Promise<string | undefined> {
    const fixture = await setup({ id: 'm', attachments: [image({ path })] });
    fixture.componentInstance.visible.set(true);
    await fixture.whenStable();

    (document.querySelector('app-message-attachment button') as HTMLButtonElement).click();
    await fixture.whenStable();
    return fixture.componentInstance.picked();
  }

  it.each([
    [
      'an apostrophe (as it was uploaded)',
      "messages/uid/3/it's here.png",
      'messages/uid/3/it%27s%20here.png',
    ],
    [
      'an apostrophe (as the server encoded it)',
      'messages/uid/3/it%27s%20here.png',
      'messages/uid/3/it%27s%20here.png',
    ],
    ['a question mark', 'messages/uid/3/what?.png', 'messages/uid/3/what%3F.png'],
  ])('encodes %s, so the backend reads the link back whole', async (_case, path, encoded) => {
    const url = await pick(path);

    expect(url).toBe(`/rest/repo/file/${encoded}`);
    const readBack = BACKEND_FILE_LINK.exec(`<img src="${url}" alt="">`)?.[1];
    expect(readBack).toBe(encoded);
    expect(decodeURIComponent(readBack!)).toBe(decodeURIComponent(path));
  });

  it('emits nothing on cancel and closes', async () => {
    const fixture = await setup({ id: 'm', attachments: [image()] });
    fixture.componentInstance.visible.set(true);
    await fixture.whenStable();

    (document.querySelector('.dialog-foot .btn-tertiary') as HTMLButtonElement).click();
    await fixture.whenStable();

    expect(fixture.componentInstance.picked()).toBeUndefined();
    expect(fixture.componentInstance.visible()).toBe(false);
  });
});
