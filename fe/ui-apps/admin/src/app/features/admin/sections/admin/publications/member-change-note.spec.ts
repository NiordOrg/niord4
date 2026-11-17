import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { TranslocoService } from '@jsverse/transloco';
import { describe, expect, it } from 'vitest';

import { provideNiordIcons } from '../../../../../shared/icons/niord-icons';
import { provideTestTransloco } from '../../../../../shared/testing/transloco-testing';
import type { IssueMember } from '../../../services/api/publication-series-api.service';
import { MemberChangeNote } from './member-change-note';

const member = signal<IssueMember | undefined>(undefined);

@Component({
  imports: [MemberChangeNote],
  template: `<app-member-change-note [member]="member()" />`,
})
class Host {
  protected readonly member = member;
}

function setup(value: IssueMember | undefined) {
  member.set(value);
  TestBed.configureTestingModule({ providers: [provideTestTransloco(), provideNiordIcons()] });
  TestBed.inject(TranslocoService).setTranslation(
    {
      'admin.publications.change.statusNotPublic':
        'Now {{value}} and no longer public — the message stays in this issue.',
      'admin.publications.change.gone': 'Deleted after this issue went out.',
      'admin.publications.change.type': 'Type changed to {{value}}.',
      'admin.publications.change.why': 'It was published at the cut-off; the change came after.',
      'admin.publications.memberStatus.CANCELLED': 'Cancelled',
      'msg.type.PERMANENT_NOTICE': 'Permanent notice',
    },
    'en',
  );
  const fixture = TestBed.createComponent(Host);
  fixture.detectChanges();
  return { element: fixture.nativeElement as HTMLElement };
}

/**
 * The wrappers, not the glyphs inside them.
 *
 * The icon renders its own `role="img"` element, so an unqualified selector
 * counts every mark twice — and the second copy carries no label, because the
 * text deliberately lives on the wrapper.
 */
function marks(element: HTMLElement): HTMLElement[] {
  return [...element.querySelectorAll<HTMLElement>('[data-testid="member-change-note"]')];
}

describe('MemberChangeNote', () => {
  /**
   * The ordinary case is silence.
   *
   * A mark on every row would mean nothing, and a live list has no change at
   * all — an open issue resolves its members now, so there is nothing to have
   * changed from.
   */
  it('marks nothing on a row that still agrees with its message', () => {
    const { element } = setup({
      messageUid: 'uid-1',
      frozenShortId: 'NM-1',
      current: { exists: true, publiclyVisible: true, status: 'PUBLISHED' },
    });
    expect(marks(element)).toEqual([]);
  });

  /**
   * The glyph is decorative, so the wrapper carries the whole finding: what
   * changed, quoting the live value, and why the message is still listed. A
   * reader wondering about a cancelled row in a published issue is answered on
   * hover, and a screen-reader user hears the same sentence.
   */
  it('carries the finding and the reason as tooltip and accessible name', () => {
    const { element } = setup({
      messageUid: 'uid-1',
      drift: ['status'],
      current: { exists: true, publiclyVisible: false, status: 'CANCELLED' },
    });

    const [mark] = marks(element);
    const expected =
      'Now Cancelled and no longer public — the message stays in this issue. ' +
      'It was published at the cut-off; the change came after.';
    expect(mark.getAttribute('title')).toBe(expected);
    expect(mark.getAttribute('aria-label')).toBe(expected);
    // No text in the cell itself: the icon is the whole of what is drawn.
    expect(mark.textContent?.trim()).toBe('');
  });

  /** A sentence that quotes nothing still renders as a sentence, not as a key. */
  it('renders a finding that quotes no value', () => {
    const { element } = setup({
      messageUid: 'uid-1',
      drift: ['exists'],
      current: { exists: false, publiclyVisible: false },
    });
    expect(marks(element)[0].getAttribute('title')).toBe(
      'Deleted after this issue went out. It was published at the cut-off; the change came after.',
    );
  });

  it('draws one mark per field that moved', () => {
    const { element } = setup({
      messageUid: 'uid-1',
      drift: ['type', 'exists'],
      current: { exists: true, publiclyVisible: true, type: 'PERMANENT_NOTICE' },
    });
    expect(marks(element).map((m) => m.getAttribute('title')?.split('. ')[0])).toEqual([
      'Deleted after this issue went out',
      'Type changed to Permanent notice',
    ]);
  });
});
