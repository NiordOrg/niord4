import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { provideRouter } from '@angular/router';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { of } from 'rxjs';

import { APP_CONFIG } from '../../../core/config/app-config.token';
import { TAG_SELECTION_STORE } from '../../../core/domain/services/tag-selection-store.token';
import { MessageCopyDialogService } from '../dialogs/message-copy-dialog/message-copy-dialog.service';
import { MessagePrintService } from '../services/message-print.service';
import { MessageTagActionsService } from '../services/message-tag-actions.service';
import type { Message } from '../models/message.model';
import { provideNiordIcons } from '../../../shared/icons/niord-icons';
import { provideTestTransloco } from '../../../shared/testing/transloco-testing';
import { provideFakeAuthFacade, provideFakeDomainFacade } from '../../../shared/testing/fake-auth';
import { MessageRowMenu } from './message-row-menu';

function setup(size?: 'xs' | 'md') {
  TestBed.configureTestingModule({
    providers: [
      provideFakeAuthFacade({ isAuthenticated: true, editor: true }),
      provideFakeDomainFacade({ canEditSeries: true }),
      { provide: MessageTagActionsService, useValue: {} },
      { provide: MessagePrintService, useValue: { printMessage: vi.fn(() => of(undefined)) } },
      { provide: MessageCopyDialogService, useValue: { open: vi.fn(() => of(undefined)) } },
      {
        provide: TAG_SELECTION_STORE,
        useValue: { getLastSelection: () => signal(undefined), saveLastSelection: vi.fn() },
      },
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
      provideHttpClient(),
      provideHttpClientTesting(),
      provideTestTransloco(),
      provideNiordIcons(),
      provideRouter([]),
    ],
  });
  const fixture = TestBed.createComponent(MessageRowMenu);
  fixture.componentRef.setInput('messageId', 'NM-1-26');
  fixture.componentRef.setInput('message', {
    id: 'NM-1-26',
    mainType: 'NM',
    messageSeries: { seriesId: 'series-1' },
  } as Message);
  if (size) fixture.componentRef.setInput('size', size);
  fixture.detectChanges();
  const host = fixture.nativeElement as HTMLElement;
  return {
    fixture,
    host,
    httpMock: TestBed.inject(HttpTestingController),
    button: () => host.querySelector('button') as HTMLButtonElement,
  };
}

/** The items of the open menu, in the overlay container CDK renders it into. */
function menuItems(): HTMLElement[] {
  return [...document.querySelectorAll<HTMLElement>('.cdk-overlay-container [role="menuitem"]')];
}

afterEach(() => {
  document.querySelector('.cdk-overlay-container')?.replaceChildren();
});

describe('MessageRowMenu', () => {
  it('shows the stand-in button, drawn as the menu trigger draws it, and builds no menu', () => {
    const { host, button } = setup('xs');
    expect(host.querySelector('app-message-details-menu')).toBeNull();
    expect([...button().classList].sort()).toEqual(['h-8', 'icon-button', 'px-2']);
    expect(button().getAttribute('aria-haspopup')).toBe('menu');
    expect(button().getAttribute('aria-expanded')).toBe('false');
    expect(button().getAttribute('aria-label')).toBeTruthy();
    expect(button().querySelectorAll('app-icon')).toHaveLength(2);
  });

  it('a md menu gets the plain icon button', () => {
    const { button } = setup('md');
    expect([...button().classList]).toEqual(['icon-button']);
  });

  it('a click builds the real menu in its place and opens it on its first item', () => {
    const { host, button } = setup('xs');
    const rowClick = vi.fn();
    host.parentElement?.addEventListener('click', rowClick);

    button().click();
    TestBed.tick();

    expect(rowClick).not.toHaveBeenCalled();
    expect(host.querySelector('app-message-details-menu')).not.toBeNull();
    const trigger = host.querySelector('app-message-details-menu button') as HTMLElement;
    expect(trigger.getAttribute('aria-expanded')).toBe('true');
    expect(menuItems().length).toBeGreaterThan(0);
    expect(document.activeElement).toBe(menuItems()[0]);
    host.parentElement?.removeEventListener('click', rowClick);
  });

  it('the Down arrow opens it on its first item, the Up arrow on its last', () => {
    for (const [key, expected] of [
      ['ArrowDown', 'first'],
      ['ArrowUp', 'last'],
    ] as const) {
      TestBed.resetTestingModule();
      const { button } = setup('xs');
      const event = new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true });
      button().dispatchEvent(event);
      TestBed.tick();

      expect(event.defaultPrevented).toBe(true);
      const items = menuItems();
      const index = items.indexOf(document.activeElement as HTMLElement);
      if (expected === 'first') expect(index).toBe(0);
      else expect(index).toBeGreaterThan(0);
      document.querySelector('.cdk-overlay-container')?.replaceChildren();
    }
  });

  it('an arrow with a modifier key does nothing', () => {
    const { host, button } = setup('xs');
    button().dispatchEvent(
      new KeyboardEvent('keydown', { key: 'ArrowDown', shiftKey: true, bubbles: true }),
    );
    TestBed.tick();
    expect(host.querySelector('app-message-details-menu')).toBeNull();
  });

  it('keeps the real menu once it has been built', () => {
    const { host, button, httpMock } = setup('xs');
    button().click();
    TestBed.tick();
    httpMock.match(() => true).forEach((request) => request.flush([]));
    document.querySelector('.cdk-overlay-container')?.replaceChildren();
    TestBed.tick();
    expect(host.querySelector('app-message-details-menu')).not.toBeNull();
  });
});
