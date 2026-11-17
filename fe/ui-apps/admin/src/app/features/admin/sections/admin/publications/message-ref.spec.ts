import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { TranslocoService } from '@jsverse/transloco';
import { describe, expect, it } from 'vitest';

import { provideTestTransloco } from '../../../../../shared/testing/transloco-testing';
import { MessageRef } from './message-ref';

const UID = '3f9a2c18-0a4e-4b7d-9c2e-5f1b7a0d6e33';

const messageUid = signal<string | undefined>(undefined);
const messageId = signal<string | null | undefined>(undefined);
const title = signal<string | null | undefined>(undefined);
const struck = signal(false);

@Component({
  imports: [MessageRef],
  template: `
    <app-message-ref
      [messageUid]="messageUid()"
      [messageId]="messageId()"
      [title]="title()"
      [struck]="struck()"
    >
      <span class="suffix">(P)</span>
    </app-message-ref>
  `,
})
class Host {
  protected readonly messageUid = messageUid;
  protected readonly messageId = messageId;
  protected readonly title = title;
  protected readonly struck = struck;
}

function setup(row: {
  uid?: string;
  messageId?: string | null;
  title?: string | null;
  struck?: boolean;
}) {
  messageUid.set(row.uid);
  messageId.set(row.messageId);
  title.set(row.title);
  struck.set(row.struck ?? false);

  TestBed.configureTestingModule({
    providers: [provideTestTransloco(), provideRouter([])],
  });
  TestBed.inject(TranslocoService).setTranslation(
    {
      'admin.publications.messageRef.notNumbered': 'not numbered yet',
      'admin.publications.messageRef.untitled': 'Untitled message',
    },
    'en',
  );

  const fixture = TestBed.createComponent(Host);
  fixture.detectChanges();
  return fixture.nativeElement as HTMLElement;
}

describe('MessageRef', () => {
  /**
   * The numbered case: the short id IS the name, and it opens the message.
   *
   * The id is what is written on the notice and quoted in a mail about it; the
   * link itself is addressed by uid, the way the editor is addressed everywhere.
   */
  it('names a numbered message by its short id, linked to the editor', () => {
    const element = setup({ uid: UID, messageId: 'NM-815-26', title: 'Buoy off station' });

    const link = element.querySelector('a')!;
    expect(link.textContent?.trim()).toBe('NM-815-26');
    expect(link.getAttribute('href')).toBe(`/editor/edit/${UID}`);
    // Nothing says "not numbered" about a message that is.
    expect(element.textContent).not.toContain('not numbered yet');
    // The title is the row's second line, not part of the name.
    expect(link.textContent).not.toContain('Buoy off station');
    // And what the row projects follows the name.
    expect(element.querySelector('.suffix')).not.toBeNull();
  });

  /**
   * The unnumbered case: the TITLE names it, and the uid only opens it.
   *
   * A message nobody has numbered yet still has to be recognisable in a list.
   * Its uid is a uuid — it names nothing to the reader, so it travels as the
   * link target and appears nowhere in the text.
   */
  it('names an unnumbered message by its title and marks it as not numbered', () => {
    const element = setup({ uid: UID, messageId: null, title: 'Buoy off station' });

    const link = element.querySelector('a')!;
    expect(link.textContent?.trim()).toBe('Buoy off station');
    expect(element.textContent).toContain('not numbered yet');
    expect(element.textContent).not.toContain(UID);
    // The editor resolves its id by uid first, so the row still opens the
    // message it is about.
    expect(link.getAttribute('href')).toBe(`/editor/edit/${UID}`);
  });

  /** Neither an id nor a title: still a row, and still says which fact is missing. */
  it('falls back to a generic name for a message with neither an id nor a title', () => {
    const element = setup({ uid: UID });

    expect(element.querySelector('a')!.textContent?.trim()).toBe('Untitled message');
    expect(element.textContent).toContain('not numbered yet');
    expect(element.textContent).not.toContain(UID);
  });

  /**
   * A message that addresses nothing is said, not linked.
   *
   * A refusal from an older server can name a citing message without carrying
   * its uid; a link that goes nowhere is worse than a name that does not offer
   * one.
   */
  it('renders a reference with no uid and no id as plain text', () => {
    const element = setup({ title: 'Buoy off station' });

    expect(element.querySelector('a')).toBeNull();
    expect(element.textContent).toContain('Buoy off station');
  });

  /** Struck through where the reference is to something taken OUT of the list. */
  it('strikes the name through on request', () => {
    const element = setup({ uid: UID, messageId: 'NM-815-26', struck: true });

    expect(element.querySelector('a')!.className).toContain('line-through');
  });
});
