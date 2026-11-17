import { Pipe, PipeTransform, inject } from '@angular/core';

import {
  FormatOptions,
  LocaleFormatService,
} from '../../core/main-shell/services/locale-format.service';

/** Which part(s) to render — all share the app's single date/time format config. */
export type LocaleDatePreset = 'date' | 'time' | 'dateTime';

/**
 * The one template pipe for dates/times. Delegates to {@link LocaleFormatService} so every
 * rendered date/time flows through the single format config (locale-native date, per-locale clock,
 * session-domain timezone). Impure so it re-formats on a language or domain switch.
 *
 * `{{ value | localeDate }}` → date + time · `:'date'` → date only · `:'time'` → time only.
 */
@Pipe({ name: 'localeDate', pure: false })
export class LocaleDatePipe implements PipeTransform {
  private readonly localeFormat = inject(LocaleFormatService);

  transform(
    value: Date | string | number | null | undefined,
    preset: LocaleDatePreset = 'dateTime',
    options?: FormatOptions,
  ): string {
    if (value == null || value === '') {
      return '';
    }
    switch (preset) {
      case 'date':
        return this.localeFormat.formatDate(value, options);
      case 'time':
        return this.localeFormat.formatTime(value, options);
      case 'dateTime':
        return this.localeFormat.formatDateTime(value, options);
    }
  }
}
