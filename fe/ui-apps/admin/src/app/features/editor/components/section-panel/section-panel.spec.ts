import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';

import { provideTestTransloco } from '../../../../shared/testing/transloco-testing';
import { SectionPanel } from './section-panel';

@Component({
  imports: [SectionPanel],
  // Signal-backed host: plain fields don't trigger zoneless change detection.
  template: `<app-section-panel [heading]="title()" [sunken]="sunken()">
    <p class="body">Rows</p>
  </app-section-panel>`,
})
class Host {
  readonly title = signal('editor.parts.title');
  readonly sunken = signal(false);
}

function setup() {
  TestBed.configureTestingModule({ providers: [provideTestTransloco()] });
  const fixture = TestBed.createComponent(Host);
  fixture.detectChanges();
  return { fixture, host: fixture.componentInstance, el: fixture.nativeElement as HTMLElement };
}

describe('SectionPanel', () => {
  it('renders the translated title in the header band', () => {
    const { el } = setup();
    // The test transloco echoes the key when no translation is registered, proving the
    // pipe ran on the header (translation-ownership: the panel owns `| transloco`).
    expect(el.querySelector('header')?.textContent).toContain('editor.parts.title');
  });

  it('projects the body content', () => {
    const { el } = setup();
    expect(el.querySelector('.body')?.textContent).toBe('Rows');
  });

  it('applies the sunken body treatment only when sunken is set', () => {
    const { fixture, host, el } = setup();
    const body = () => el.querySelector('.body')?.parentElement as HTMLElement;
    expect(body().className).toContain('py-0.5');
    expect(body().className).not.toContain('bg-surface-100');

    host.sunken.set(true);
    fixture.detectChanges();
    expect(body().className).toContain('bg-surface-100');
    expect(body().className).not.toContain('py-0.5');
  });

  it('marks the flat (non-sunken) body as a field group so its last row divider drops', () => {
    const { el } = setup();
    const body = el.querySelector('.body')?.parentElement as HTMLElement;
    expect(body.classList).toContain('ed-field-group');
  });

  it('collapses and expands the body from the header toggle (default expanded)', () => {
    const { fixture, el } = setup();
    const toggle = el.querySelector<HTMLButtonElement>('header button')!;
    const body = () => el.querySelector('.body')?.parentElement as HTMLElement;
    // Header + toggle stay visible; the body starts open.
    expect(toggle.getAttribute('aria-expanded')).toBe('true');
    expect(body().hidden).toBe(false);
    expect(toggle.getAttribute('aria-controls')).toBe(body().id);

    toggle.click();
    fixture.detectChanges();
    expect(toggle.getAttribute('aria-expanded')).toBe('false');
    expect(body().hidden).toBe(true);
    // The header (with its toggle) remains in the DOM while collapsed.
    expect(el.querySelector('header button')).not.toBeNull();

    toggle.click();
    fixture.detectChanges();
    expect(toggle.getAttribute('aria-expanded')).toBe('true');
    expect(body().hidden).toBe(false);
  });
});
