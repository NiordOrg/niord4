import {
  Component,
  Injector,
  afterNextRender,
  computed,
  inject,
  input,
  signal,
  viewChild,
} from '@angular/core';
import type { FocusOrigin } from '@angular/cdk/a11y';
import { TranslocoPipe } from '@jsverse/transloco';

import { Icon } from '../../../shared/icons/icon';
import type { Message } from '../models/message.model';
import { MessageDetailsMenu } from './message-details-menu';

type MenuSize = 'xs' | 'sm' | 'md';

/**
 * A message row's action menu, built when the user reaches for it.
 *
 * {@link MessageDetailsMenu} is a CDK menu trigger, the menu's items, the message's tag lookup and a
 * complete tag picker. A list builds a row for each of up to a few hundred messages, and the user
 * opens the menu of one of them at most — so until a row's cog is used, the row has this component's
 * stand-in instead: the same button, drawn the same way, with nothing behind it. The first click,
 * Enter, Space or Down or Up arrow puts the real menu in its place and opens it as its own button
 * would have; from then on the row has the real menu.
 *
 * Building every row's menu took about a quarter of the time it took to build a table of 176
 * messages.
 */
@Component({
  selector: 'app-message-row-menu',
  imports: [Icon, MessageDetailsMenu, TranslocoPipe],
  templateUrl: './message-row-menu.html',
})
export class MessageRowMenu {
  private readonly injector = inject(Injector);

  readonly messageId = input.required<string>();
  readonly message = input.required<Message>();
  readonly size = input<MenuSize>('sm');

  protected readonly built = signal(false);
  private readonly menu = viewChild(MessageDetailsMenu);

  /** The classes `MenuTrigger` gives the icon-only button of the size `MessageDetailsMenu` asks for. */
  protected readonly buttonClass = computed(() =>
    this.size() === 'md' ? 'icon-button' : 'icon-button h-8 px-2',
  );

  /** A click — or Enter or Space, which a button turns into one. */
  protected onClick(event: MouseEvent): void {
    // As `MenuTrigger` does: the row under the button must not take the click as its own.
    event.stopPropagation();
    this.build('first', 'mouse');
  }

  /** The Down and Up arrows, which open CDK's menu on its first and last item. */
  protected onKeydown(event: KeyboardEvent): void {
    if (event.key !== 'ArrowDown' && event.key !== 'ArrowUp') return;
    if (event.altKey || event.ctrlKey || event.metaKey || event.shiftKey) return;
    // As CDK's trigger does: the arrow opens the menu instead of scrolling the page.
    event.preventDefault();
    this.build(event.key === 'ArrowDown' ? 'first' : 'last', 'keyboard');
  }

  private build(item: 'first' | 'last', origin: FocusOrigin): void {
    this.built.set(true);
    afterNextRender(() => this.menu()?.open(item, origin), { injector: this.injector });
  }
}
