import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { afterEach, describe, expect, it } from 'vitest';

import { provideNiordIcons } from '../../../../shared/icons/niord-icons';
import { provideTestTransloco } from '../../../../shared/testing/transloco-testing';
import { provideTestSessionZone } from '../../../../shared/testing/zone-testing';
import type { ParamType, TemplateParam } from '../../../messages/models/template.model';
import { TemplatesApiService } from '../../services/api/templates-api.service';
import type { ParamChange } from './template-param-field';
import { TemplateParamField } from './template-param-field';

@Component({
  imports: [TemplateParamField],
  template: `
    <app-template-param-field
      [param]="param()"
      [paramType]="paramType()"
      [paramTypes]="paramTypes()"
      [values]="values()"
      lang="en"
      (paramChange)="changes.push($event)"
    />
  `,
})
class Host {
  readonly param = signal<TemplateParam>({ paramId: 'p', type: 'text' });
  readonly paramType = signal<ParamType | undefined>({ type: 'STANDARD', name: 'text' });
  readonly paramTypes = signal<ReadonlyMap<string, ParamType>>(new Map());
  readonly values = signal<Record<string, unknown>>({});
  readonly changes: ParamChange[] = [];
}

function setup() {
  TestBed.configureTestingModule({
    providers: [
      provideNiordIcons(),
      provideTestTransloco(),
      // UTC+5:30, no DST: an emitted epoch landing on a half hour can only have come from
      // resolving the picked day in the domain's zone.
      provideTestSessionZone('Asia/Kolkata'),
      {
        provide: TemplatesApiService,
        useValue: { validate: () => of({ value: '', valid: true }) },
      },
    ],
  });
  const fixture = TestBed.createComponent(Host);
  fixture.autoDetectChanges();
  return { fixture, host: fixture.componentInstance, el: fixture.nativeElement as HTMLElement };
}

afterEach(() => {
  document.querySelectorAll('.cdk-overlay-container').forEach((n) => n.replaceChildren());
});

describe('TemplateParamField', () => {
  it('renders a text input and commits its value on change', async () => {
    const { fixture, host, el } = setup();
    await fixture.whenStable();
    const input = el.querySelector<HTMLInputElement>('input[type="text"]')!;
    input.value = 'wreck';
    input.dispatchEvent(new Event('change'));
    expect(host.changes.at(-1)).toEqual({ paramId: 'p', value: 'wreck' });
  });

  it('commits a numeric value as a number', async () => {
    const { fixture, host, el } = setup();
    host.param.set({ paramId: 'n', type: 'number' });
    host.paramType.set({ type: 'STANDARD', name: 'number' });
    await fixture.whenStable();
    const input = el.querySelector<HTMLInputElement>('input[type="number"]')!;
    input.value = '12.5';
    input.dispatchEvent(new Event('change'));
    expect(host.changes.at(-1)).toEqual({ paramId: 'n', value: 12.5 });
  });

  it('commits a boolean from the checkbox', async () => {
    const { fixture, host, el } = setup();
    host.param.set({ paramId: 'b', type: 'boolean' });
    host.paramType.set({ type: 'STANDARD', name: 'boolean' });
    await fixture.whenStable();
    const box = el.querySelector<HTMLInputElement>('input[type="checkbox"]')!;
    box.checked = true;
    box.dispatchEvent(new Event('change'));
    expect(host.changes.at(-1)).toEqual({ paramId: 'b', value: true });
  });

  it('commits a date param as the epoch of the day picked in the domain zone', async () => {
    const { fixture, host, el } = setup();
    host.param.set({ paramId: 'd', type: 'date' });
    host.paramType.set({ type: 'STANDARD', name: 'date' });
    // 11 June 00:00 in Kolkata — so the calendar opens on June, not on the runner's month.
    host.values.set({ d: Date.parse('2026-06-10T18:30:00.000Z') });
    await fixture.whenStable();

    el.querySelector<HTMLButtonElement>('app-date-picker button')!.click();
    await fixture.whenStable();
    const cell = Array.from(document.querySelectorAll<HTMLButtonElement>('.calendar-day')).find(
      (day) => day.textContent?.trim() === '5' && !day.classList.contains('is-outside'),
    );
    cell!.click();
    await fixture.whenStable();

    // Midnight on 5 June for the domain, committed as an epoch — the wall clock the field
    // held (00:00 in Kolkata) is preserved on the newly picked day.
    expect(host.changes.at(-1)).toEqual({
      paramId: 'd',
      value: Date.parse('2026-06-04T18:30:00.000Z'),
    });
  });

  it('renders a LIST param as a select with the type values', async () => {
    const { fixture, host, el } = setup();
    host.param.set({ paramId: 'l', type: 'colours' });
    host.paramType.set({
      type: 'LIST',
      name: 'colours',
      values: [
        { key: 'red', descs: [{ lang: 'en', value: 'Red' }] },
        { key: 'green', descs: [{ lang: 'en', value: 'Green' }] },
      ],
    });
    await fixture.whenStable();
    expect(el.querySelector('app-select')).not.toBeNull();
    expect(el.querySelector('input[type="text"]')).toBeNull();
  });

  it('recursively renders a COMPOSITE param’s nested fields', async () => {
    const { fixture, host, el } = setup();
    host.param.set({ paramId: 'c', type: 'vessel' });
    host.paramType.set({
      type: 'COMPOSITE',
      name: 'vessel',
      templateParams: [
        { paramId: 'name', type: 'text' },
        { paramId: 'speed', type: 'number' },
      ],
    });
    host.paramTypes.set(
      new Map<string, ParamType>([
        ['text', { type: 'STANDARD', name: 'text' }],
        ['number', { type: 'STANDARD', name: 'number' }],
      ]),
    );
    await fixture.whenStable();
    expect(el.querySelectorAll('fieldset app-template-param-field').length).toBe(2);
    expect(el.querySelector('input[type="text"]')).not.toBeNull();
    expect(el.querySelector('input[type="number"]')).not.toBeNull();
  });

  it('routes light_character / call_sign to the remote-validated input', async () => {
    const { fixture, host, el } = setup();
    host.param.set({ paramId: 'lc', type: 'light_character' });
    host.paramType.set({ type: 'STANDARD', name: 'light_character' });
    await fixture.whenStable();
    expect(el.querySelector('app-remote-validated-input')).not.toBeNull();
  });

  it('marks a mandatory param with an asterisk', async () => {
    const { fixture, host, el } = setup();
    host.param.set({ paramId: 'p', type: 'text', mandatory: true });
    await fixture.whenStable();
    expect(el.querySelector('.text-danger')?.textContent).toContain('*');
  });

  it('a mandatory field carries aria-required on its control, an optional one does not', async () => {
    const { fixture, host, el } = setup();
    await fixture.whenStable();
    expect(el.querySelector('input[type="text"]')!.hasAttribute('aria-required')).toBe(false);

    host.param.set({ paramId: 'p', type: 'text', mandatory: true });
    await fixture.whenStable();
    expect(el.querySelector('input[type="text"]')!.getAttribute('aria-required')).toBe('true');

    host.param.set({ paramId: 'n', type: 'number', mandatory: true });
    host.paramType.set({ type: 'STANDARD', name: 'number' });
    await fixture.whenStable();
    expect(el.querySelector('input[type="number"]')!.getAttribute('aria-required')).toBe('true');

    host.param.set({ paramId: 'lc', type: 'light_character', mandatory: true });
    host.paramType.set({ type: 'STANDARD', name: 'light_character' });
    await fixture.whenStable();
    expect(
      el.querySelector('app-remote-validated-input input')!.getAttribute('aria-required'),
    ).toBe('true');

    host.param.set({ paramId: 'l', type: 'colours', mandatory: true });
    host.paramType.set({
      type: 'LIST',
      name: 'colours',
      values: [{ key: 'red', descs: [{ lang: 'en', value: 'Red' }] }],
    });
    await fixture.whenStable();
    expect(el.querySelector('[role="combobox"]')!.getAttribute('aria-required')).toBe('true');
  });

  it('marks a mandatory COMPOSITE param with an asterisk on its frame', async () => {
    const { fixture, host, el } = setup();
    host.param.set({ paramId: 'c', type: 'pair', mandatory: true });
    host.paramType.set({
      type: 'COMPOSITE',
      name: 'pair',
      templateParams: [{ paramId: 'a', type: 'text' }],
    });
    host.paramTypes.set(new Map<string, ParamType>([['text', { type: 'STANDARD', name: 'text' }]]));
    await fixture.whenStable();
    expect(el.querySelector('legend .text-danger')?.textContent).toContain('*');
  });

  it('a mandatory tick box is not marked required: unticked is a value', async () => {
    const { fixture, host, el } = setup();
    host.param.set({ paramId: 'b', type: 'boolean', mandatory: true });
    host.paramType.set({ type: 'STANDARD', name: 'boolean' });
    await fixture.whenStable();
    expect(el.querySelector('input[type="checkbox"]')!.hasAttribute('aria-required')).toBe(false);
  });
});
