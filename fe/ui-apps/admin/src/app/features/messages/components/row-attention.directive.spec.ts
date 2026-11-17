import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { TranslocoService } from '@jsverse/transloco';
import { describe, expect, it } from 'vitest';

import en from '../../../../../../../languages/en/i18n/admin/en.json';
import { provideTestTransloco } from '../../../shared/testing/transloco-testing';
import type { Message } from '../models/message.model';
import { RowAttentionDirective } from './row-attention.directive';

const PAST = '2000-01-01T00:00:00Z';
const FUTURE = '2999-01-01T00:00:00Z';

@Component({
  imports: [RowAttentionDirective],
  template: `<table>
    <tbody>
      <tr class="row-interactive" [appRowAttention]="message()"></tr>
    </tbody>
  </table>`,
})
class Host {
  readonly message = signal<Message>({ id: 'm' } as Message);
}

/** `english` seeds the real English bundle; without it the stub loader renders keys. */
function setup(message: Message, english = false) {
  TestBed.configureTestingModule({ providers: [provideTestTransloco()] });
  if (english) TestBed.inject(TranslocoService).setTranslation(en, 'en');
  const fixture = TestBed.createComponent(Host);
  fixture.componentInstance.message.set(message);
  fixture.detectChanges();
  const row = fixture.nativeElement.querySelector('tr') as HTMLElement;
  return { fixture, row };
}

describe('RowAttentionDirective', () => {
  it('washes the row and explains itself on hover when the follow-up date has passed', () => {
    const { row } = setup({ id: 'm', followUpDate: PAST } as Message);

    expect(row.classList.contains('row-attention')).toBe(true);
    // The hover title is the non-colour half of the cue — the wash must never be the only signal.
    expect(row.getAttribute('title')).toBeTruthy();
  });

  it('names the passed follow-up date as the cause in English, the only thing that sets it', () => {
    const { row } = setup({ id: 'm', followUpDate: PAST } as Message, true);

    expect(row.getAttribute('title')).toBe('Follow-up date has passed');
  });

  it('leaves a row with a future follow-up date alone, title included', () => {
    const { row } = setup({ id: 'm', followUpDate: FUTURE } as Message);

    expect(row.classList.contains('row-attention')).toBe(false);
    expect(row.getAttribute('title')).toBeNull();
  });

  it('leaves a row with no follow-up date alone', () => {
    const { row } = setup({ id: 'm' } as Message);

    expect(row.classList.contains('row-attention')).toBe(false);
    expect(row.getAttribute('title')).toBeNull();
  });

  it('reacts when the bound message changes (rows are recycled by @for)', () => {
    const { fixture, row } = setup({ id: 'm', followUpDate: PAST } as Message);
    expect(row.classList.contains('row-attention')).toBe(true);

    fixture.componentInstance.message.set({ id: 'other', followUpDate: FUTURE } as Message);
    fixture.detectChanges();

    expect(row.classList.contains('row-attention')).toBe(false);
  });
});
