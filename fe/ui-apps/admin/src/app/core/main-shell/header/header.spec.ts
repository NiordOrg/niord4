import { TestBed, type ComponentFixture } from '@angular/core/testing';
import { signal, type Signal } from '@angular/core';
import { provideRouter } from '@angular/router';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { APP_CONFIG } from '../../config/app-config.token';
import { MenuService } from '../menu.service';
import { Header } from './header';
import { AccountMenuService } from './services/account-menu.service';
import { DomainMenuService } from './services/domain-menu.service';
import { HelpMenuService } from './services/help-menu.service';
import { LanguageMenuService } from './services/language-menu.service';
import { provideNiordIcons } from '../../../shared/icons/niord-icons';
import { provideTestTransloco } from '../../../shared/testing/transloco-testing';
import type { MenuItem } from '../../../shared/models/menu-item.model';

interface StubMenuService<T> {
  readonly menuItems?: Signal<readonly MenuItem[]>;
  readonly menuItem?: Signal<T>;
}

function setup(opts: {
  navItems?: MenuItem[];
  help?: MenuItem | null;
  domain?: MenuItem | null;
  account?: MenuItem;
  language?: MenuItem;
  mode?: string;
  production?: boolean;
}): { component: Header; fixture: ComponentFixture<Header> } {
  const menuService: StubMenuService<MenuItem> = {
    menuItems: signal(opts.navItems ?? []) as Signal<readonly MenuItem[]>,
  };
  const helpMenuService: StubMenuService<MenuItem | null> = {
    menuItem: signal(opts.help ?? null) as Signal<MenuItem | null>,
  };
  const domainMenuService: StubMenuService<MenuItem | null> = {
    menuItem: signal(opts.domain ?? null) as Signal<MenuItem | null>,
  };
  const accountMenuService: StubMenuService<MenuItem> = {
    menuItem: signal(opts.account ?? { label: 'Sign in', icon: 'lucideLogIn' }) as Signal<MenuItem>,
  };
  const languageMenuService: StubMenuService<MenuItem> = {
    menuItem: signal(opts.language ?? { label: 'EN', icon: 'flag', items: [] }) as Signal<MenuItem>,
  };

  TestBed.configureTestingModule({
    providers: [
      provideRouter([{ path: '**', children: [] }]),
      provideTestTransloco(),
      provideNiordIcons(),
      {
        provide: APP_CONFIG,
        useValue: { apiBaseUrl: '/rest', production: opts.production ?? false, mode: opts.mode },
      },
      { provide: MenuService, useValue: menuService },
      { provide: HelpMenuService, useValue: helpMenuService },
      { provide: DomainMenuService, useValue: domainMenuService },
      { provide: AccountMenuService, useValue: accountMenuService },
      { provide: LanguageMenuService, useValue: languageMenuService },
    ],
  });

  const fixture = TestBed.createComponent(Header);
  return { component: fixture.componentInstance, fixture };
}

afterEach(() => {
  // Dispose any leaked CDK overlay panels across the shared single-fork TestBed.
  document.querySelectorAll('.cdk-overlay-container').forEach((el) => el.replaceChildren());
});

describe('Header — mobileItems flattening', () => {
  it('returns just nav items when no other sections produce output', () => {
    const { component } = setup({
      navItems: [{ label: 'Messages', routerLink: 'messages' }],
      account: { label: 'Sign in', icon: 'lucideLogIn' },
      language: { label: 'EN', items: [] },
    });
    const items = component.mobileItems();
    // nav + separator (always added before account) + account login
    expect(items[0].label).toBe('Messages');
    expect(items.some((i) => i.label === 'Sign in')).toBe(true);
  });

  it('appends help section with a leading separator when helpItem is non-null', () => {
    const { component } = setup({
      navItems: [{ label: 'Messages' }],
      help: { label: 'Help', url: 'https://docs', icon: 'lucideCircleHelp' },
    });
    const items = component.mobileItems();
    const helpIndex = items.findIndex((i) => i.label === 'Help');
    expect(helpIndex).toBeGreaterThan(0);
    expect(items[helpIndex - 1].separator).toBe(true);
  });

  it('omits help entirely when helpItem is null', () => {
    const { component } = setup({
      navItems: [{ label: 'Messages' }],
      help: null,
    });
    const items = component.mobileItems();
    expect(items.find((i) => i.label === 'Help')).toBeUndefined();
  });

  it('omits the domain section entirely — the domain keeps its own trigger', () => {
    const { component } = setup({
      navItems: [{ label: 'Messages' }],
      domain: {
        label: 'Domain',
        items: [
          { label: 'Domain A', command: vi.fn() },
          { label: 'Domain B', command: vi.fn() },
        ],
      },
    });
    const items = component.mobileItems();
    // Below `lg` the domain has a standalone trigger beside the burger, so its
    // items — and the "Domain" trigger label itself — stay out of this list.
    expect(items.find((i) => i.label === 'Domain A')).toBeUndefined();
    expect(items.find((i) => i.label === 'Domain B')).toBeUndefined();
    expect(items.find((i) => i.label === 'Domain')).toBeUndefined();
  });

  it('flattens account.items when authenticated (has items)', () => {
    const editCmd = vi.fn();
    const logoutCmd = vi.fn();
    const { component } = setup({
      navItems: [{ label: 'Messages' }],
      account: {
        label: 'John Doe',
        items: [
          { label: 'Edit John Doe', command: editCmd },
          { label: 'Logout', command: logoutCmd },
        ],
      },
    });
    const items = component.mobileItems();
    expect(items.find((i) => i.label === 'Edit John Doe')).toBeDefined();
    expect(items.find((i) => i.label === 'Logout')).toBeDefined();
    expect(items.find((i) => i.label === 'John Doe')).toBeUndefined();
  });

  it('includes the bare login action when anonymous (no items)', () => {
    const loginCmd = vi.fn();
    const { component } = setup({
      navItems: [{ label: 'Messages' }],
      account: { label: 'Sign in', icon: 'lucideLogIn', command: loginCmd },
    });
    const items = component.mobileItems();
    const signIn = items.find((i) => i.label === 'Sign in');
    expect(signIn).toBeDefined();
    expect(signIn?.command).toBe(loginCmd);
  });

  it('flattens language.items with a leading separator', () => {
    const enCmd = vi.fn();
    const daCmd = vi.fn();
    const { component } = setup({
      navItems: [{ label: 'Messages' }],
      language: {
        label: 'Language',
        items: [
          { label: 'English', command: enCmd },
          { label: 'Dansk', command: daCmd },
        ],
      },
    });
    const items = component.mobileItems();
    const enIndex = items.findIndex((i) => i.label === 'English');
    expect(enIndex).toBeGreaterThan(0);
    expect(items[enIndex - 1].separator).toBe(true);
    expect(items.find((i) => i.label === 'Dansk')).toBeDefined();
  });
});

describe('Header — deployment badge (mode / production)', () => {
  it('shows the badge labelled with the uppercased mode for a non-production mode', () => {
    const { component } = setup({ mode: 'staging' });
    expect(component.showTestBadge).toBe(true);
    expect(component.testBadgeLabel).toBe('STAGING');
  });

  it("hides the badge when mode is 'production', even on a non-production build", () => {
    const { component } = setup({ mode: 'production', production: false });
    expect(component.showTestBadge).toBe(false);
  });

  it('falls back to the production flag when mode is absent (non-production build)', () => {
    // No mode: env `production` decides, label falls back to the translated default.
    const { component } = setup({});
    expect(component.showTestBadge).toBe(true);
    expect(component.testBadgeLabel).toBeUndefined();
  });

  it('falls back to the production flag when mode is absent (production build)', () => {
    const { component } = setup({ production: true });
    expect(component.showTestBadge).toBe(false);
  });

  it('treats a blank mode as absent — never a text-less badge', () => {
    const { component } = setup({ mode: '   ', production: false });
    expect(component.showTestBadge).toBe(true);
    expect(component.testBadgeLabel).toBeUndefined();
  });

  it('renders the badge element with the mode text', async () => {
    const { fixture } = setup({ mode: 'staging' });
    fixture.autoDetectChanges();
    await fixture.whenStable();
    const badge = fixture.nativeElement.querySelector('header span[aria-label]');
    expect(badge?.textContent?.trim()).toBe('STAGING');
  });
});

describe('Header — account trigger branching', () => {
  it('opens a menu of account actions when authenticated (has items)', async () => {
    const editCmd = vi.fn();
    const { fixture } = setup({
      account: {
        label: 'John Doe',
        items: [{ label: 'Edit account', command: editCmd }],
      },
    });
    fixture.autoDetectChanges();
    await fixture.whenStable();

    // The authenticated account button hosts appMenuTriggerFor; CDK opens the
    // popup on click (no bespoke toggle handler).
    const btn = fixture.nativeElement.querySelector(
      'button[aria-label="John Doe"]',
    ) as HTMLButtonElement;
    expect(btn.getAttribute('aria-haspopup')).toBe('menu');
    btn.click();
    await fixture.whenStable();

    const panel = document.querySelector('.menu-panel');
    expect(panel).not.toBeNull();
    expect(panel?.textContent).toContain('Edit account');
  });

  it('invokes accountItem.command directly when anonymous (no items)', () => {
    const loginCmd = vi.fn();
    const { component } = setup({
      account: { label: 'Sign in', icon: 'lucideLogIn', command: loginCmd },
    });
    component.onAccountLogin(new MouseEvent('click'));
    expect(loginCmd).toHaveBeenCalledTimes(1);
  });

  it('is a safe no-op when account has neither items nor command', () => {
    const { component } = setup({
      account: { label: 'Empty' },
    });
    expect(() => component.onAccountLogin(new MouseEvent('click'))).not.toThrow();
  });
});

describe('Header — language trigger', () => {
  const trigger = (fixture: ComponentFixture<Header>, label: string) =>
    (fixture.nativeElement as HTMLElement).querySelector(
      `button[aria-label="${label}"]`,
    ) as HTMLButtonElement;

  it('shows the flag of the active language', () => {
    const { fixture } = setup({
      language: {
        label: 'Dansk',
        icon: 'inline-block w-6 h-4',
        iconStyle: { 'background-image': "url('assets/img/flags/da.png')" },
        items: [],
      },
    });
    fixture.detectChanges();
    expect(trigger(fixture, 'Dansk').querySelector('i')).not.toBeNull();
  });

  it('names the active language where the build ships no flag for it', () => {
    const { fixture } = setup({ language: { label: 'Føroyskt', items: [] } });
    fixture.detectChanges();
    const button = trigger(fixture, 'Føroyskt');
    expect(button.querySelector('i')).toBeNull();
    expect(button.textContent).toContain('Føroyskt');
  });
});
