import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { APP_CONFIG } from '../../../../../core/config/app-config.token';
import { NotificationService } from '../../../../../core/notifications/notification.service';
import { provideTestTransloco } from '../../../../../shared/testing/transloco-testing';
import type { NumberSequenceType } from '../../../models/admin-message-series.model';
import { NextNumberPopover } from './next-number-popover';

const YEAR = new Date().getFullYear();
const numberUrl = (year: number) => `/rest/message-series/series/dma-nw/number/${year}`;

@Component({
  imports: [NextNumberPopover],
  template: `
    <button #anchor type="button">anchor</button>
    <app-next-number-popover
      [seriesId]="seriesId()"
      [sequenceType]="sequenceType()"
      (updated)="updates = updates + 1"
    />
  `,
})
class Host {
  readonly seriesId = signal('dma-nw');
  readonly sequenceType = signal<NumberSequenceType>('YEARLY');
  updates = 0;
}

interface PopoverInternals {
  form: NextNumberPopover['form'];
  shown: () => boolean;
  saving: () => boolean;
  updateDisabled: () => boolean;
  compute: () => void;
  update: () => void;
  close: () => void;
  open: (anchor: HTMLElement) => void;
  toggle: (anchor: HTMLElement) => void;
}

async function setup(sequenceType: NumberSequenceType = 'YEARLY') {
  TestBed.configureTestingModule({
    providers: [
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
      provideHttpClient(),
      provideHttpClientTesting(),
      provideTestTransloco(),
      {
        provide: NotificationService,
        useValue: { success: vi.fn(), error: vi.fn() },
      },
    ],
  });
  const fixture = TestBed.createComponent(Host);
  fixture.componentInstance.sequenceType.set(sequenceType);
  fixture.autoDetectChanges();
  await fixture.whenStable();
  const anchor = fixture.nativeElement.querySelector('button') as HTMLElement;
  const popover = fixture.debugElement.query(By.directive(NextNumberPopover))
    .componentInstance as unknown as PopoverInternals;
  return {
    fixture,
    host: fixture.componentInstance,
    httpTesting: TestBed.inject(HttpTestingController),
    anchor,
    popover,
  };
}

/**
 * Opens the popover and settles the year-keyed fetch with the given stored
 * number. `TestBed.tick()` flushes the effects so the resource issues its GET;
 * `whenStable()` must only run AFTER the flush — it awaits pending requests.
 */
async function openAndFlush(
  ctx: Awaited<ReturnType<typeof setup>>,
  storedNumber: number | null,
): Promise<void> {
  ctx.popover.open(ctx.anchor);
  TestBed.tick();
  ctx.httpTesting.expectOne(numberUrl(YEAR)).flush(storedNumber);
  await ctx.fixture.whenStable();
}

describe('NextNumberPopover', () => {
  afterEach(() => {
    TestBed.inject(HttpTestingController).verify();
    document.querySelectorAll('.cdk-overlay-container').forEach((el) => el.replaceChildren());
  });

  it('opens at the current year and seeds the input from the stored-number fetch', async () => {
    const ctx = await setup();
    await openAndFlush(ctx, 42);

    expect(ctx.popover.shown()).toBe(true);
    expect(ctx.popover.form.year().value()).toBe(YEAR);
    expect(ctx.popover.form.nextNumber().value()).toBe(42);
  });

  it('refetches on every year change (the fetch is keyed {seriesId, year})', async () => {
    const ctx = await setup();
    await openAndFlush(ctx, 42);

    ctx.popover.form.year().value.set(2025);
    TestBed.tick();
    ctx.httpTesting.expectOne(numberUrl(2025)).flush(7);
    await ctx.fixture.whenStable();
    expect(ctx.popover.form.nextNumber().value()).toBe(7);
  });

  it('skips the fetch while the year is outside 1900–2099', async () => {
    const ctx = await setup();
    await openAndFlush(ctx, 42);

    ctx.popover.form.year().value.set(1800);
    TestBed.tick();
    ctx.httpTesting.expectNone((req) => req.url.includes('/number/1800'));
    expect(ctx.popover.updateDisabled()).toBe(true); // out-of-range year blocks Update too
  });

  it('shows the year input for YEARLY series only', async () => {
    const ctx = await setup();
    await openAndFlush(ctx, 42);
    expect(document.querySelector('#next-number-year')).not.toBeNull();
  });

  it('CONTINUOUS hides the year input but still fetches at the current year', async () => {
    const ctx = await setup('CONTINUOUS');
    await openAndFlush(ctx, 27);

    expect(document.querySelector('#next-number-year')).toBeNull();
    expect(document.querySelector('#next-number-value')).not.toBeNull();
    expect(ctx.popover.form.nextNumber().value()).toBe(27);
  });

  it('Compute overwrites the input with the computed number', async () => {
    const ctx = await setup();
    await openAndFlush(ctx, 42);

    ctx.popover.compute();
    ctx.httpTesting
      .expectOne(`/rest/message-series/series/dma-nw/compute-number/${YEAR}`)
      .flush(55);
    expect(ctx.popover.form.nextNumber().value()).toBe(55);
  });

  it('gates Update on a valid integer ≥ 0 (empty or non-integer never PUTs)', async () => {
    const ctx = await setup();
    await openAndFlush(ctx, null); // no stored number yet — input stays empty

    const nextNumber = ctx.popover.form.nextNumber();
    expect(ctx.popover.updateDisabled()).toBe(true); // empty
    nextNumber.value.set(-1);
    expect(ctx.popover.updateDisabled()).toBe(true); // negative
    nextNumber.value.set(1.5);
    expect(ctx.popover.updateDisabled()).toBe(true); // not an integer
    nextNumber.value.set(0);
    expect(ctx.popover.updateDisabled()).toBe(false); // 0 is a valid next number
  });

  it('Update PUTs the number, toasts, closes the POPOVER only and emits updated', async () => {
    const ctx = await setup();
    await openAndFlush(ctx, 42);

    ctx.popover.form.nextNumber().value.set(43);
    ctx.popover.update();
    const req = ctx.httpTesting.expectOne(numberUrl(YEAR));
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toBe(43);
    req.flush(43);
    await ctx.fixture.whenStable();

    expect(ctx.popover.shown()).toBe(false);
    expect(ctx.host.updates).toBe(1);
    expect(TestBed.inject(NotificationService).success).toHaveBeenCalledWith(
      'admin.series.nextNumber.toast.updated',
    );
  });

  it('a failed Update keeps the popover open and toasts the error', async () => {
    const ctx = await setup();
    await openAndFlush(ctx, 42);

    ctx.popover.form.nextNumber().value.set(43);
    ctx.popover.update();
    ctx.httpTesting
      .expectOne(numberUrl(YEAR))
      .flush('boom', { status: 500, statusText: 'Server Error' });
    await ctx.fixture.whenStable();

    expect(ctx.popover.shown()).toBe(true);
    expect(ctx.popover.saving()).toBe(false);
    expect(ctx.host.updates).toBe(0);
    expect(TestBed.inject(NotificationService).error).toHaveBeenCalledWith(
      'admin.series.nextNumber.toast.updateError',
    );
  });

  it('a number refused as below one already used (400) keeps the popover open and says why', async () => {
    const ctx = await setup();
    await openAndFlush(ctx, 42);

    ctx.popover.form.nextNumber().value.set(12);
    ctx.popover.update();
    ctx.httpTesting
      .expectOne(numberUrl(YEAR))
      .flush(
        { status: 400, detail: 'The next number 12 is below a number already used' },
        { status: 400, statusText: 'Bad Request' },
      );
    await ctx.fixture.whenStable();

    expect(ctx.popover.shown()).toBe(true);
    expect(ctx.popover.saving()).toBe(false);
    expect(ctx.host.updates).toBe(0);
    const error = TestBed.inject(NotificationService).error;
    expect(error).toHaveBeenCalledWith('admin.series.nextNumber.toast.belowUsed');
    expect(error).not.toHaveBeenCalledWith('admin.series.nextNumber.toast.updateError');
  });

  it('dismissing without Update persists nothing, and a reopen re-seeds a fresh session', async () => {
    const ctx = await setup();
    await openAndFlush(ctx, 42);

    // Abandon an edit: no PUT goes out on close.
    ctx.popover.form.nextNumber().value.set(99);
    ctx.popover.close();
    await ctx.fixture.whenStable();
    expect(ctx.popover.shown()).toBe(false);

    // Reopen refetches — and re-seeds even when the server returns the SAME
    // number as last time (the seed is keyed on fetch completion, not value).
    await openAndFlush(ctx, 42);
    expect(ctx.popover.form.nextNumber().value()).toBe(42);
  });

  it('toggle on the pencil anchor closes an open popover', async () => {
    const ctx = await setup();
    await openAndFlush(ctx, 42);
    expect(ctx.popover.shown()).toBe(true);

    ctx.popover.toggle(ctx.anchor);
    await ctx.fixture.whenStable();
    expect(ctx.popover.shown()).toBe(false);
  });
});
