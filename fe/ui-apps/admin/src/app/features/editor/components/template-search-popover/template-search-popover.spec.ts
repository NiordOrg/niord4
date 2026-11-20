import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { Observable, of, throwError } from 'rxjs';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { APP_CONFIG } from '../../../../core/config/app-config.token';
import { DomainFacade } from '../../../../core/domain/facades/domain.facade';
import { LanguageService } from '../../../../core/main-shell/services/language.service';
import { provideTestTransloco } from '../../../../shared/testing/transloco-testing';
import type { SystemCategory } from '../../../messages/models/template.model';
import { CategoriesApiService } from '../../../messages/services/api/categories-api.service';
import { TemplateSearchPopover } from './template-search-popover';

const TEMPLATES: SystemCategory[] = [
  { id: 1, type: 'TEMPLATE', descs: [{ lang: 'en', name: 'Buoy out of position' }] },
  { id: 2, type: 'TEMPLATE', descs: [{ lang: 'en', name: 'Cable operations' }] },
  { id: 3, type: 'TEMPLATE', descs: [{ lang: 'en', name: 'Diving operations' }] },
] as SystemCategory[];

@Component({
  imports: [TemplateSearchPopover],
  template: `
    <button #anchor type="button">Select NW template…</button>
    <app-template-search-popover (picked)="picked.set($event)" />
  `,
})
class Host {
  readonly picked = signal<SystemCategory | undefined>(undefined);
}

async function setup(
  opts: { narrow?: boolean; searchTemplates?: () => Observable<SystemCategory[]> } = {},
) {
  if (opts.narrow) {
    // Only the popover's narrow query exists here, so every query answers as a phone would.
    vi.stubGlobal('matchMedia', () => ({
      matches: true,
      addEventListener: () => undefined,
      removeEventListener: () => undefined,
    }));
  }
  TestBed.configureTestingModule({
    providers: [
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
      provideHttpClient(),
      provideHttpClientTesting(),
      provideTestTransloco(),
      { provide: DomainFacade, useValue: { sessionDomain: () => ({ domainId: 'dma-nw' }) } },
      { provide: LanguageService, useValue: { selectedLanguage: () => 'en' } },
      {
        provide: CategoriesApiService,
        useValue: {
          searchTemplates: opts.searchTemplates ?? (() => of(TEMPLATES)),
          getTemplateDetails: () => of(TEMPLATES),
        },
      },
    ],
  });
  const fixture = TestBed.createComponent(Host);
  fixture.autoDetectChanges();
  await fixture.whenStable();

  const anchor = fixture.nativeElement.querySelector('button') as HTMLElement;
  const popover = fixture.debugElement.query(By.directive(TemplateSearchPopover))
    .componentInstance as TemplateSearchPopover;

  return { fixture, host: fixture.componentInstance, anchor, popover };
}

const combobox = () => document.querySelector<HTMLInputElement>('[role="combobox"]');
const options = () => Array.from(document.querySelectorAll<HTMLElement>('[role="option"]'));

function tap(el: HTMLElement): void {
  el.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, cancelable: true }));
}

const buttonNamed = (name: string) =>
  Array.from(document.querySelectorAll<HTMLButtonElement>('.cdk-overlay-container button')).find(
    (b) => b.textContent?.trim() === name,
  );

function pressKey(key: string): void {
  combobox()!.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true }));
}

describe('TemplateSearchPopover', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    TestBed.inject(HttpTestingController).verify();
    document.querySelectorAll('.cdk-overlay-container').forEach((el) => el.replaceChildren());
  });

  it('opens with the caret already in the search box, so the trigger click is the only click', async () => {
    const ctx = await setup();
    ctx.popover.toggle(ctx.anchor);
    await ctx.fixture.whenStable();

    expect(combobox()).not.toBeNull();
    expect(document.activeElement).toBe(combobox());
  });

  it('a failed read of the list says so, and Retry reads it again', async () => {
    let calls = 0;
    const ctx = await setup({
      searchTemplates: () =>
        calls++ === 0 ? throwError(() => new Error('offline')) : of(TEMPLATES),
    });
    ctx.popover.toggle(ctx.anchor);
    await ctx.fixture.whenStable();

    const alert = document.querySelector('.cdk-overlay-container [role="alert"]');
    expect(alert?.textContent).toContain('common.fetchError');
    expect(document.body.textContent).not.toContain('editor.template.drawer.noResults');
    buttonNamed('common.retry')?.click();
    await ctx.fixture.whenStable();
    expect(options()).toHaveLength(3);
  });

  it('opens showing the full domain list, so picking needs no typing at all', async () => {
    const ctx = await setup();
    ctx.popover.toggle(ctx.anchor);
    await ctx.fixture.whenStable();

    // First span per row is the template name; the second is the per-position / template-level tag.
    expect(options().map((el) => el.querySelector('span')?.textContent?.trim())).toEqual([
      'Buoy out of position',
      'Cable operations',
      'Diving operations',
    ]);
  });

  it('ArrowDown walks the list via aria-activedescendant and drives the example pane', async () => {
    const ctx = await setup();
    ctx.popover.toggle(ctx.anchor);
    await ctx.fixture.whenStable();

    expect(combobox()!.getAttribute('aria-activedescendant')).toBeNull();

    pressKey('ArrowDown');
    await ctx.fixture.whenStable();
    expect(combobox()!.getAttribute('aria-activedescendant')).toBe(options()[0].id);

    pressKey('ArrowDown');
    await ctx.fixture.whenStable();
    expect(combobox()!.getAttribute('aria-activedescendant')).toBe(options()[1].id);

    // The highlight owns the example pane the same way a hover does.
    expect(ctx.popover['hovered']()).toBe(TEMPLATES[1]);
  });

  it('ArrowUp wraps to the last template', async () => {
    const ctx = await setup();
    ctx.popover.toggle(ctx.anchor);
    await ctx.fixture.whenStable();

    pressKey('ArrowUp');
    await ctx.fixture.whenStable();
    expect(combobox()!.getAttribute('aria-activedescendant')).toBe(options()[2].id);
  });

  it('Enter picks the highlighted template and closes — the flow never needs the mouse', async () => {
    const ctx = await setup();
    ctx.popover.toggle(ctx.anchor);
    await ctx.fixture.whenStable();

    pressKey('ArrowDown');
    await ctx.fixture.whenStable();
    pressKey('Enter');
    await ctx.fixture.whenStable();

    expect(ctx.host.picked()).toBe(TEMPLATES[0]);
    expect(combobox()).toBeNull();
  });

  it('Enter with nothing highlighted picks nothing', async () => {
    const ctx = await setup();
    ctx.popover.toggle(ctx.anchor);
    await ctx.fixture.whenStable();

    pressKey('Enter');
    await ctx.fixture.whenStable();

    expect(ctx.host.picked()).toBeUndefined();
    expect(combobox()).not.toBeNull();
  });

  it('closing hands focus back to the trigger instead of dropping it on <body>', async () => {
    const ctx = await setup();
    ctx.popover.toggle(ctx.anchor);
    await ctx.fixture.whenStable();
    expect(document.activeElement).toBe(combobox());

    ctx.popover.toggle(ctx.anchor); // re-click the trigger closes it
    await ctx.fixture.whenStable();

    expect(combobox()).toBeNull();
    expect(document.activeElement).toBe(ctx.anchor);
  });

  it('a reopen starts from a clean search box with no stale highlight', async () => {
    const ctx = await setup();
    ctx.popover.toggle(ctx.anchor);
    await ctx.fixture.whenStable();
    pressKey('ArrowDown');
    await ctx.fixture.whenStable();
    expect(combobox()!.getAttribute('aria-activedescendant')).not.toBeNull();

    ctx.popover.toggle(ctx.anchor);
    await ctx.fixture.whenStable();
    ctx.popover.toggle(ctx.anchor);
    await ctx.fixture.whenStable();

    expect(combobox()!.value).toBe('');
    expect(combobox()!.getAttribute('aria-activedescendant')).toBeNull();
  });

  describe('on a narrow screen', () => {
    it("a tap opens the template's example in place of the list instead of picking it", async () => {
      const ctx = await setup({ narrow: true });
      ctx.popover.toggle(ctx.anchor);
      await ctx.fixture.whenStable();

      tap(options()[1]);
      await ctx.fixture.whenStable();

      expect(ctx.host.picked()).toBeUndefined();
      expect(combobox()!.closest('.hidden')).not.toBeNull();
      expect(ctx.popover['hovered']()).toBe(TEMPLATES[1]);
      expect(document.activeElement).toBe(buttonNamed('editor.template.backToList'));
    });

    it("the example's own button picks that template and closes", async () => {
      const ctx = await setup({ narrow: true });
      ctx.popover.toggle(ctx.anchor);
      await ctx.fixture.whenStable();
      tap(options()[2]);
      await ctx.fixture.whenStable();

      buttonNamed('editor.template.useThis')!.click();
      await ctx.fixture.whenStable();

      expect(ctx.host.picked()).toBe(TEMPLATES[2]);
      expect(combobox()).toBeNull();
    });

    it('Back returns to the list with the caret in the search box', async () => {
      const ctx = await setup({ narrow: true });
      ctx.popover.toggle(ctx.anchor);
      await ctx.fixture.whenStable();
      tap(options()[0]);
      await ctx.fixture.whenStable();

      buttonNamed('editor.template.backToList')!.click();
      await ctx.fixture.whenStable();

      expect(combobox()!.closest('.hidden')).toBeNull();
      expect(buttonNamed('editor.template.useThis')).toBeUndefined();
      expect(document.activeElement).toBe(combobox());
    });
  });

  it('a click on a wide screen picks the row directly', async () => {
    const ctx = await setup();
    ctx.popover.toggle(ctx.anchor);
    await ctx.fixture.whenStable();

    tap(options()[0]);
    await ctx.fixture.whenStable();

    expect(ctx.host.picked()).toBe(TEMPLATES[0]);
  });
});
