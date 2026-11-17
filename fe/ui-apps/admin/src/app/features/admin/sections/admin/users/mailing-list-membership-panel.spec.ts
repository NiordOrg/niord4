import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';

import { provideTestTransloco } from '../../../../../shared/testing/transloco-testing';
import type { MailingList } from '../../../services/api/mailing-lists-api.service';
import { MailingListMembershipPanel, type MembershipToggle } from './mailing-list-membership-panel';

const list = (id: string, name = id): MailingList => ({
  mailingListId: id,
  name,
  description: `${name} desc`,
});

function setup(lists: readonly MailingList[], memberIds: ReadonlySet<string> | undefined) {
  TestBed.configureTestingModule({ providers: [provideTestTransloco()] });
  const fixture = TestBed.createComponent(MailingListMembershipPanel);
  fixture.componentRef.setInput('lists', lists);
  fixture.componentRef.setInput('memberIds', memberIds);
  const emitted: MembershipToggle[] = [];
  fixture.componentInstance.toggled.subscribe((t) => emitted.push(t));
  fixture.detectChanges();
  return { fixture, el: fixture.nativeElement as HTMLElement, emitted };
}

describe('MailingListMembershipPanel', () => {
  it('shows the progress bar and no table while the member lookup is still in flight', () => {
    const { el } = setup([list('a')], undefined);
    // rows() stays undefined until BOTH sources resolve — the two-source race guard.
    expect(el.querySelector('app-progress-bar [role="progressbar"]')).not.toBeNull();
    expect(el.querySelector('table')).toBeNull();
  });

  it('a resolved-but-empty catalog shows the empty message, not a table', () => {
    const { el } = setup([], new Set());
    expect(el.querySelector('table')).toBeNull();
    expect(el.textContent).toContain('admin.users.membership.empty');
  });

  it('derives one row per list with a member/not-member status dot', () => {
    const { el } = setup([list('a', 'Alpha'), list('b', 'Bravo')], new Set(['a']));

    const rows = el.querySelectorAll('tbody tr');
    expect(rows.length).toBe(2);
    expect(el.querySelectorAll('.status-dot.is-active').length).toBe(1);
    expect(el.querySelectorAll('.status-dot.is-inactive').length).toBe(1);
    // Row order follows the catalog; the member row carries the active dot.
    expect(rows[0].querySelector('.status-dot.is-active')).not.toBeNull();
    expect(rows[1].querySelector('.status-dot.is-inactive')).not.toBeNull();
    expect(rows[0].textContent).toContain('Alpha');
    expect(rows[0].textContent).toContain('Alpha desc');
  });

  it('the toggle emits the INTENDED next state: leave a member, join a non-member', () => {
    const { el, emitted } = setup([list('a', 'Alpha'), list('b', 'Bravo')], new Set(['a']));

    const buttons = el.querySelectorAll('tbody button');
    // Member row → "leave" → recipient:false; non-member row → "join" → recipient:true.
    expect(buttons[0].textContent).toContain('admin.users.membership.leave');
    expect(buttons[1].textContent).toContain('admin.users.membership.join');

    (buttons[0] as HTMLButtonElement).click();
    (buttons[1] as HTMLButtonElement).click();

    expect(emitted).toHaveLength(2);
    expect(emitted[0]).toEqual({
      list: expect.objectContaining({ mailingListId: 'a' }),
      recipient: false,
    });
    expect(emitted[1]).toEqual({
      list: expect.objectContaining({ mailingListId: 'b' }),
      recipient: true,
    });
  });

  it('re-derives the rows in place when the member set changes (no re-mount race)', () => {
    const { fixture, el } = setup([list('a', 'Alpha')], new Set());
    expect(el.querySelector('.status-dot.is-inactive')).not.toBeNull();

    fixture.componentRef.setInput('memberIds', new Set(['a']));
    fixture.detectChanges();
    expect(el.querySelector('.status-dot.is-active')).not.toBeNull();
    expect(el.querySelector('.status-dot.is-inactive')).toBeNull();
  });

  it('does not emit on its own — the host owns the API calls', () => {
    const { el, emitted } = setup([list('a')], new Set(['a']));
    // Rendering alone produces no intent; only a click does.
    expect(emitted).toHaveLength(0);
    expect(el.querySelectorAll('tbody button').length).toBe(1);
  });
});
