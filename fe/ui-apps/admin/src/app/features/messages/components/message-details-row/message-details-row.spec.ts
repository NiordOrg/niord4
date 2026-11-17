import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { describe, expect, it, vi } from 'vitest';

import { MessageDetailsRow } from './message-details-row';
import { LanguageService } from '../../../../core/main-shell/services/language.service';
import { APP_CONFIG } from '../../../../core/config/app-config.token';
import { NotificationService } from '../../../../core/notifications/notification.service';
import { provideFakeMessageTagActions } from '../../testing/fake-message-tag-actions';
import type { Message } from '../../models/message.model';
import { provideTestTransloco } from '../../../../shared/testing/transloco-testing';
import {
  provideFakeAuthFacade,
  provideFakeDomainFacade,
} from '../../../../shared/testing/fake-auth';

function setup(message: Message) {
  TestBed.configureTestingModule({
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideTestTransloco(),
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest' } },
      { provide: LanguageService, useValue: { selectedLanguage: () => 'en' } },
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
    ],
  });

  const fixture = TestBed.createComponent(MessageDetailsRow);
  fixture.componentRef.setInput('message', message);
  fixture.detectChanges();
  return { fixture, component: fixture.componentInstance };
}

const baseMessage: Message = {
  id: 'uuid-1',
  shortId: 'NW-001-25',
  descs: [{ lang: 'en', title: 'Test message' }],
};

describe('MessageDetailsRow', () => {
  /**
   * The thumb is the row's link, and the address comes from the row above it.
   * Mounted on its own — as here — there is no row to ask, so it stays a plain
   * element with no href rather than refusing to render.
   */
  it('wraps the thumb in the row’s link, named after the message', () => {
    const { fixture } = setup(baseMessage);
    const link = fixture.nativeElement.querySelector('a.row-link') as HTMLAnchorElement;
    expect(link).not.toBeNull();
    expect(link.getAttribute('aria-label')).toBe('Test message');
    expect(link.querySelector('img')).not.toBeNull();
    expect(link.getAttribute('href')).toBeNull();
  });

  it('builds the thumbnail URL via MessageUrlBuilderService', () => {
    const { component } = setup(baseMessage);
    expect(component.thumbnailUrl()).toBe('/rest/message-map-image/uuid-1.png');
  });

  it('mounts the selection toggle as a centered hover-revealed overlay on the thumbnail', () => {
    // The selection toggle floats absolutely centered over the thumb image,
    // invisible until the row is hovered or focused (or once the message is
    // in the selection set, in which case it stays visible). The wrapper
    // around <app-message-selection-toggle> carries the positioning and
    // opacity transition classes that drive that behavior.
    const { fixture } = setup(baseMessage);
    const toggle = fixture.nativeElement.querySelector('app-message-selection-toggle');
    expect(toggle).not.toBeNull();
    const wrapper = toggle.parentElement as HTMLElement;
    expect(wrapper.className).toContain('top-1/2');
    expect(wrapper.className).toContain('left-1/2');
    expect(wrapper.className).toContain('-translate-x-1/2');
    expect(wrapper.className).toContain('-translate-y-1/2');
    expect(wrapper.className).toContain('opacity-0');
    expect(wrapper.className).toContain('group-hover:opacity-100');
    expect(wrapper.className).toContain('group-focus-within:opacity-100');
  });

  it('renders the originalInformation star when message.originalInformation === true', () => {
    const { fixture, component } = setup({ ...baseMessage, originalInformation: true });
    expect(component.hasOriginalInformation()).toBe(true);
    expect(fixture.nativeElement.innerHTML).toContain('name="lucideStar"');
  });

  it('omits the originalInformation star when missing or false', () => {
    const { component } = setup({ ...baseMessage, originalInformation: false });
    expect(component.hasOriginalInformation()).toBe(false);
  });

  it('the [data-menu-slot] mounts the row menu (MessageRowMenu) in the third grid column', () => {
    // Row click → openRequested(message.id) is now handled by the consuming
    // view's `<tr appRowLink>`, not by this component. The action
    // dropdown still lives inside the row at the [data-menu-slot] anchor;
    // its own stopPropagation prevents bubbling into the row click.
    const { fixture } = setup(baseMessage);
    const slot = fixture.nativeElement.querySelector('[data-menu-slot]');
    expect(slot).not.toBeNull();
    expect(slot.querySelector('app-message-row-menu')).not.toBeNull();
  });
});
