import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';

import { provideNiordIcons } from '../../shared/icons/niord-icons';
import { provideTestTransloco } from '../../shared/testing/transloco-testing';
import { BuildVersionService } from './build-version.service';
import { UpdateBanner } from './update-banner';

function setup() {
  const webUpdateAvailable = signal(false);
  TestBed.configureTestingModule({
    providers: [
      provideTestTransloco(),
      provideNiordIcons(),
      {
        provide: BuildVersionService,
        useValue: {
          webUpdateAvailable,
          serverBuildVersion: signal('04-07-2026 11:45 UTC'),
          webBuildVersion: 'main-ABC123.js',
        },
      },
    ],
  });
  const fixture = TestBed.createComponent(UpdateBanner);
  fixture.detectChanges();
  const alert = () => fixture.nativeElement.querySelector('[role="alert"]') as HTMLElement | null;
  return { fixture, webUpdateAvailable, alert };
}

describe('UpdateBanner', () => {
  it('renders nothing while the running build is the deployed one', () => {
    const { alert } = setup();
    expect(alert()).toBeNull();
  });

  it('appears as soon as an update is known, with both build stamps', () => {
    const { fixture, webUpdateAvailable, alert } = setup();

    webUpdateAvailable.set(true);
    fixture.detectChanges();

    const text = alert()?.textContent ?? '';
    expect(text).toContain('front_page.version_error');
    expect(text).toContain('04-07-2026 11:45 UTC');
    expect(text).toContain('main-ABC123.js');
  });

  it('offers the reload and leaves it to the user', () => {
    const { fixture, webUpdateAvailable, alert } = setup();
    const reload = vi
      .spyOn(fixture.componentInstance as unknown as { reload(): void }, 'reload')
      .mockImplementation(() => undefined);

    webUpdateAvailable.set(true);
    fixture.detectChanges();
    // Showing the banner reloads nothing.
    expect(reload).not.toHaveBeenCalled();

    const button = alert()?.querySelector('button') as HTMLButtonElement;
    expect(button.textContent).toContain('common.retry');
    button.click();
    expect(reload).toHaveBeenCalledTimes(1);
  });
});
