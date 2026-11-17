import { signal } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { TranslocoService } from '@jsverse/transloco';
import { afterEach, describe, expect, it } from 'vitest';

import { APP_CONFIG } from '../../../../core/config/app-config.token';
import { provideNiordIcons } from '../../../../shared/icons/niord-icons';
import { provideTestTransloco } from '../../../../shared/testing/transloco-testing';
import { DomainService } from '../../../../core/domain/services/domain.service';
import { LanguageService } from '../../../../core/main-shell/services/language.service';
import type { MessageComment } from '../../../messages/models/message-comment.model';
import { CommentCard } from './comment-card';

async function setup(comment: MessageComment, canEdit = false) {
  // Some cases build two cards in one test; reset so a second configure isn't blocked by the first.
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({
    providers: [
      provideTestTransloco(),
      provideNiordIcons(),
      // Expanding the inline editor mounts CommentEditor → EmailAddressField → user-directory API.
      provideHttpClient(),
      provideHttpClientTesting(),
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
      { provide: DomainService, useValue: { sessionDomain: () => undefined } },
      {
        provide: LanguageService,
        useValue: { selectedLanguage: signal('en'), activeLocale: signal('en-GB') },
      },
    ],
  });
  TestBed.inject(TranslocoService).load('en').subscribe();

  const fixture = TestBed.createComponent(CommentCard);
  fixture.componentRef.setInput('comment', comment);
  fixture.componentRef.setInput('canEdit', canEdit);
  fixture.autoDetectChanges();
  await fixture.whenStable();
  return fixture;
}

const text = (fixture: { nativeElement: HTMLElement }) => fixture.nativeElement.textContent ?? '';
const buttons = (fixture: { nativeElement: HTMLElement }) =>
  Array.from(fixture.nativeElement.querySelectorAll('button')) as HTMLButtonElement[];
const hasButton = (fixture: { nativeElement: HTMLElement }, fragment: string) =>
  buttons(fixture).some((b) => (b.textContent ?? '').includes(fragment));

afterEach(() => {
  document.querySelectorAll('.cdk-overlay-container').forEach((el) => el.replaceChildren());
});

describe('CommentCard — header', () => {
  it('shows the author initials (up to two) and name', async () => {
    const fixture = await setup({ id: 'c1', comment: 'hi', user: 'Karsten M.', created: 'x' });
    expect(text(fixture)).toContain('KM');
    expect(text(fixture)).toContain('Karsten M.');
  });

  it('renders the unacknowledged chip for a not-yet-acked comment', async () => {
    const fixture = await setup({ id: 'c1', comment: 'hi', user: 'A' });
    expect(text(fixture)).toContain('editor.comments.unacknowledged');
    expect(fixture.nativeElement.querySelector('.status-chip.is-draft')).not.toBeNull();
  });

  it('renders the acknowledged chip + who/when for an acked comment', async () => {
    const fixture = await setup({
      id: 'c1',
      comment: 'hi',
      user: 'A',
      acknowledgeDate: '2026-02-03T09:14:00Z',
      acknowledgedBy: 'Michael N.',
    });
    expect(text(fixture)).toContain('editor.comments.acknowledgedLabel');
    expect(fixture.nativeElement.querySelector('.status-chip.is-published')).not.toBeNull();
    // The acknowledged-by line carries the actor's initials.
    expect(text(fixture)).toContain('MN');
  });
});

describe('CommentCard — body sanitization', () => {
  it('keeps safe markup and drops scripts', async () => {
    const fixture = await setup({
      id: 'c1',
      comment: '<b>bold</b><script>alert(1)</script>',
      user: 'A',
    });
    const html = fixture.nativeElement.innerHTML as string;
    expect(html).toContain('<b>bold</b>');
    expect(html).not.toContain('<script');
  });
});

describe('CommentCard — actions', () => {
  it('offers Acknowledge only for a saved, unacked comment', async () => {
    const acked = await setup({ id: 'c1', comment: 'hi', user: 'A', acknowledgeDate: 'x' });
    expect(hasButton(acked, 'editor.comments.acknowledge')).toBe(false);

    const unacked = await setup({ id: 'c2', comment: 'hi', user: 'A' });
    expect(hasButton(unacked, 'editor.comments.acknowledge')).toBe(true);
  });

  it('shows the Edit affordance only when canEdit is set', async () => {
    const readOnly = await setup({ id: 'c1', comment: 'hi', user: 'A' }, false);
    expect(hasButton(readOnly, 'editor.comments.edit')).toBe(false);

    const editable = await setup({ id: 'c2', comment: 'hi', user: 'A' }, true);
    expect(hasButton(editable, 'editor.comments.edit')).toBe(true);
  });

  it('always offers Reply', async () => {
    const fixture = await setup({ id: 'c1', comment: 'hi', user: 'A' });
    expect(hasButton(fixture, 'editor.comments.reply')).toBe(true);
  });

  it('an open Edit editor is NOT repopulated when a thread reload replaces the comment input', async () => {
    const fixture = await setup({ id: 'c1', comment: 'original', user: 'A' }, true);
    const editButton = buttons(fixture).find((b) =>
      (b.textContent ?? '').includes('editor.comments.edit'),
    )!;
    editButton.click();
    await fixture.whenStable();

    const card = fixture.componentInstance as unknown as {
      editorComment(): { comment: string };
    };
    const seedBefore = card.editorComment();
    expect(seedBefore.comment).toBe('original');

    // A reload hands the reused card (tracked by id) a FRESH object of the same id — the open
    // editor must keep its latched snapshot (same reference), not repopulate and revert the edit.
    fixture.componentRef.setInput('comment', { id: 'c1', comment: 'original', user: 'A' });
    await fixture.whenStable();
    expect(card.editorComment()).toBe(seedBefore);
  });
});
