import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { describe, expect, it, vi } from 'vitest';

import { APP_CONFIG } from '../../../../core/config/app-config.token';
import { LanguageService } from '../../../../core/main-shell/services/language.service';
import { NotificationService } from '../../../../core/notifications/notification.service';
import { MessageGridCard } from './message-grid-card';
import { provideFakeMessageTagActions } from '../../testing/fake-message-tag-actions';
import { provideFakeMessageEditShortcut } from '../../testing/fake-message-edit-shortcut';
import type { Message } from '../../models/message.model';
import { provideTestTransloco } from '../../../../shared/testing/transloco-testing';
import {
  provideFakeAuthFacade,
  provideFakeDomainFacade,
} from '../../../../shared/testing/fake-auth';

function setup(message: Message, currentLanguage = 'en', editTryEdit: () => boolean = () => false) {
  TestBed.configureTestingModule({
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideTestTransloco(),
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest' } },
      {
        provide: LanguageService,
        useValue: {
          selectedLanguage: () => currentLanguage,
          defaultLanguage: 'en',
          modelLanguages: [{ value: 'en' }, { value: 'da' }],
        },
      },
      {
        provide: NotificationService,
        useValue: {
          success: vi.fn(),
          info: vi.fn(),
          warn: vi.fn(),
          error: vi.fn(),
          notify: vi.fn(),
        },
      },
      provideFakeAuthFacade(),
      provideFakeDomainFacade(),
      provideFakeMessageTagActions(),
      provideFakeMessageEditShortcut(editTryEdit),
    ],
  });

  const fixture = TestBed.createComponent(MessageGridCard);
  fixture.componentRef.setInput('message', message);
  fixture.detectChanges();
  return { fixture, component: fixture.componentInstance };
}

describe('MessageGridCard', () => {
  it('builds the thumbnail URL via MessageUrlBuilderService', () => {
    const { component } = setup({ id: 'uuid-7' });
    expect(component.thumbnailUrl()).toBe('/rest/message-map-image/uuid-7.png');
  });

  it('resolves title via MessageDescriptionService', () => {
    const { component } = setup({ id: 'a', descs: [{ lang: 'en', title: 'Hello' }] });
    expect(component.title()).toBe('Hello');
  });

  it('clicking the article emits openRequested with the message id', () => {
    const { fixture, component } = setup({ id: 'a' });
    const emitted: string[] = [];
    component.openRequested.subscribe((id) => emitted.push(id));

    const host = fixture.nativeElement.querySelector('.card-interactive') as HTMLElement;
    host.click();

    expect(emitted).toEqual(['a']);
  });

  it('hold-E shortcut claims the click — openRequested is NOT emitted (edit took over)', () => {
    const { fixture, component } = setup({ id: 'a' }, 'en', () => true);
    const emitted: string[] = [];
    component.openRequested.subscribe((id) => emitted.push(id));

    (fixture.nativeElement.querySelector('.card-interactive') as HTMLElement).click();

    expect(emitted).toEqual([]);
  });

  it('clicks on the menu slot do not bubble up to the card click handler', () => {
    const { fixture, component } = setup({ id: 'a' });
    const emitted: string[] = [];
    component.openRequested.subscribe((id) => emitted.push(id));

    const menuButton = fixture.nativeElement.querySelector(
      '[data-menu-slot] button',
    ) as HTMLElement;
    expect(menuButton).not.toBeNull();
    menuButton.click();

    expect(emitted).toEqual([]);
  });

  it('language mark is shown when descs[0].lang differs from currentLanguage', () => {
    const { fixture, component } = setup({ id: 'a', descs: [{ lang: 'da', title: 'X' }] }, 'en');
    expect(component.languageMismatch()).toBe(true);
    // The configured Danish option has no flag here, so the mark is the code, not an invented image.
    expect(component.mismatchLanguage()).toEqual({ lang: 'da', flagPath: undefined });
    fixture.detectChanges();
    const mark = (fixture.nativeElement as HTMLElement).querySelector('app-language-flag');
    expect(mark?.querySelector('img')).toBeNull();
    expect(mark?.textContent?.trim()).toBe('DA');
  });

  it('language flag is hidden when descs[0].lang matches currentLanguage', () => {
    const { component } = setup({ id: 'a', descs: [{ lang: 'en', title: 'X' }] }, 'en');
    expect(component.languageMismatch()).toBe(false);
  });

  it('selection toggle mounted as a centered overlay on the thumbnail', () => {
    const { fixture } = setup({ id: 'a' });
    const toggle = fixture.nativeElement.querySelector('app-message-selection-toggle');
    expect(toggle).not.toBeNull();
    const wrapper = toggle.parentElement as HTMLElement;
    expect(wrapper.className).toContain('top-1/2');
    expect(wrapper.className).toContain('left-1/2');
    expect(wrapper.className).toContain('-translate-x-1/2');
    expect(wrapper.className).toContain('-translate-y-1/2');
  });

  it('selection overlay is opacity-0 by default (revealed on hover/focus) and opacity-100 when selected', () => {
    const { fixture } = setup({ id: 'unselected-msg' });
    const wrapper = fixture.nativeElement.querySelector('app-message-selection-toggle')
      ?.parentElement as HTMLElement;
    expect(wrapper.className).toContain('opacity-0');
    expect(wrapper.className).toContain('group-hover:opacity-100');
    expect(wrapper.className).toContain('group-focus-within:opacity-100');
  });

  it('focus outline is keyboard-only (:focus-visible) so drawer focus-restore leaves no ring', () => {
    const { fixture } = setup({ id: 'a' });
    const card = fixture.nativeElement.querySelector('.card-interactive') as HTMLElement;
    expect(card.className).toContain('focus-visible:outline-2');
    expect(card.className).not.toContain(' focus:outline-2');
  });

  /**
   * The card opens an address, so its title is a real link and the card is not a
   * second tab stop beside it. A message with no title has no link text to give
   * one, so that card stays the button it was.
   */
  it('makes the title a link addressing the message the card opens', () => {
    const { fixture } = setup({ id: 'a', descs: [{ lang: 'en', title: 'Hello' }] });
    const link = fixture.nativeElement.querySelector('a.row-link') as HTMLAnchorElement;
    expect(link).not.toBeNull();
    expect(link.textContent?.trim()).toBe('Hello');
    expect(link.getAttribute('href')).toContain('messageId=a');

    const card = fixture.nativeElement.querySelector('.card-interactive') as HTMLElement;
    expect(card.getAttribute('tabindex')).toBe('-1');
    expect(card.getAttribute('role')).toBeNull();
  });

  it('opens the card once on a plain click of the title link', () => {
    const { fixture, component } = setup({ id: 'a', descs: [{ lang: 'en', title: 'Hello' }] });
    const emitted: string[] = [];
    component.openRequested.subscribe((id) => emitted.push(id));

    const link = fixture.nativeElement.querySelector('a.row-link') as HTMLAnchorElement;
    const event = new MouseEvent('click', { bubbles: true, cancelable: true });
    link.dispatchEvent(event);

    expect(event.defaultPrevented).toBe(true);
    expect(emitted).toEqual(['a']);
  });

  it('leaves an untitled card as the keyboard-reachable button it was', () => {
    const { fixture } = setup({ id: 'a' });
    const card = fixture.nativeElement.querySelector('.card-interactive') as HTMLElement;
    expect(card.getAttribute('role')).toBe('button');
    expect(card.getAttribute('tabindex')).toBe('0');
    expect(fixture.nativeElement.querySelector('a.row-link')).toBeNull();
  });

  it('the [data-menu-slot] mounts the row menu (MessageRowMenu)', () => {
    const { fixture } = setup({ id: 'a' });
    const slot = fixture.nativeElement.querySelector('[data-menu-slot]');
    expect(slot).not.toBeNull();
    expect(slot.querySelector('app-message-row-menu')).not.toBeNull();
  });
});
