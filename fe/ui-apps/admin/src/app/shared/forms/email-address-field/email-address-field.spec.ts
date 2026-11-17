import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { APP_CONFIG } from '../../../core/config/app-config.token';
import { provideTestTransloco } from '../../testing/transloco-testing';
import { EmailAddressField } from './email-address-field';

function setup() {
  TestBed.configureTestingModule({
    providers: [
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
      provideHttpClient(),
      provideHttpClientTesting(),
      provideTestTransloco(),
    ],
  });
  const fixture = TestBed.createComponent(EmailAddressField);
  fixture.detectChanges();
  return {
    fixture,
    comp: fixture.componentInstance,
    httpTesting: TestBed.inject(HttpTestingController),
  };
}

describe('EmailAddressField', () => {
  let s: ReturnType<typeof setup>;
  beforeEach(() => {
    s = setup();
  });
  afterEach(() => {
    s.httpTesting.verify();
  });

  it('idOf returns the email itself', () => {
    expect((s.comp as unknown as { idOf: (e: string) => string }).idOf('a@b.c')).toBe('a@b.c');
  });

  it('value.set + onClear round-trip through the value model', () => {
    s.comp.value.set(['x@example.com', 'y@example.com']);
    expect(s.comp.value()).toEqual(['x@example.com', 'y@example.com']);

    s.comp.onClear();
    expect(s.comp.value()).toEqual([]);
  });

  it('onSelect updates the value model', () => {
    s.comp.onSelect(['z@example.com']);
    expect(s.comp.value()).toEqual(['z@example.com']);
  });
});
