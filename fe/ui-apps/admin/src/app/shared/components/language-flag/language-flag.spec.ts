import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';

import { LanguageFlag } from './language-flag';

@Component({
  imports: [LanguageFlag],
  template: `<app-language-flag
    class="h-3 w-5"
    [lang]="lang()"
    [flagPath]="flagPath()"
    [alt]="alt()"
    [codeFallback]="codeFallback()"
  />`,
})
class Host {
  readonly lang = signal('da');
  readonly flagPath = signal<string | undefined>('assets/img/flags/da.png');
  readonly alt = signal('');
  readonly codeFallback = signal(true);
}

function setup(change: (host: Host) => void = () => undefined) {
  const fixture = TestBed.createComponent(Host);
  change(fixture.componentInstance);
  fixture.detectChanges();
  const flag = (fixture.nativeElement as HTMLElement).querySelector('app-language-flag')!;
  return { fixture, flag: flag as HTMLElement };
}

describe('LanguageFlag', () => {
  it('renders the flag the build ships, with the call site classes on the element', () => {
    const { flag } = setup();
    expect(flag.querySelector('img')?.getAttribute('src')).toBe('assets/img/flags/da.png');
    expect(flag.querySelector('img')?.getAttribute('alt')).toBe('');
    expect(flag.classList).toContain('h-3');
    expect(flag.textContent?.trim()).toBe('');
  });

  it('renders the code as text, and no image, for a language without a flag', () => {
    const { flag } = setup((host) => {
      host.lang.set('kl');
      host.flagPath.set(undefined);
    });
    expect(flag.querySelector('img')).toBeNull();
    expect(flag.textContent?.trim()).toBe('KL');
    expect(flag.style.display).toBe('');
  });

  it('keeps the accessible name when the code stands in for the flag', () => {
    const { flag } = setup((host) => {
      host.flagPath.set(undefined);
      host.alt.set('Dansk');
    });
    expect(flag.querySelector('.sr-only')?.textContent).toBe('Dansk');
    expect(flag.querySelector('[aria-hidden="true"]')?.textContent?.trim()).toBe('DA');
  });

  it('renders nothing for a flagless language where the code is already beside it', () => {
    const { flag } = setup((host) => {
      host.flagPath.set(undefined);
      host.codeFallback.set(false);
    });
    expect(flag.querySelector('img')).toBeNull();
    expect(flag.textContent?.trim()).toBe('');
    expect(flag.style.display).toBe('none');
  });
});
