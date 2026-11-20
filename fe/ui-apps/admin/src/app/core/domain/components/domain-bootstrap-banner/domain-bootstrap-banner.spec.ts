import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';

import { provideTestTransloco } from '../../../../shared/testing/transloco-testing';
import { DomainFacade } from '../../facades/domain.facade';
import { DomainBootstrapBanner } from './domain-bootstrap-banner';

function setup(failed: boolean) {
  const bootstrapFailed = signal(failed);
  TestBed.configureTestingModule({
    providers: [provideTestTransloco(), { provide: DomainFacade, useValue: { bootstrapFailed } }],
  });
  const fixture = TestBed.createComponent(DomainBootstrapBanner);
  const reload = vi
    .spyOn(fixture.componentInstance as unknown as { reload(): void }, 'reload')
    .mockImplementation(() => undefined);
  fixture.detectChanges();
  return { fixture, bootstrapFailed, reload };
}

describe('DomainBootstrapBanner', () => {
  it('says that the domains could not be loaded and offers the reload', () => {
    const { fixture, reload } = setup(true);
    const el = fixture.nativeElement as HTMLElement;
    const notice = el.querySelector('[data-testid="domain-bootstrap-error"]');
    expect(notice?.getAttribute('role')).toBe('alert');
    expect(notice?.textContent).toContain('core.domain.bootstrap.errorTitle');
    // Showing the notice reloads nothing.
    expect(reload).not.toHaveBeenCalled();

    const retry = el.querySelector<HTMLButtonElement>('[data-testid="domain-bootstrap-retry"]');
    expect(retry?.textContent?.trim()).toBe('common.retry');
    retry?.click();
    expect(reload).toHaveBeenCalledTimes(1);
  });

  it('shows nothing when the domains were loaded', () => {
    const { fixture } = setup(false);
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('[data-testid="domain-bootstrap-error"]')).toBeNull();
  });
});
