import { Component, input, output } from '@angular/core';

import { Icon } from '../../icons/icon';

@Component({
  selector: 'app-error-banner',
  imports: [Icon],
  templateUrl: './error-banner.html',
})
export class ErrorBanner {
  readonly title = input.required<string>();
  readonly message = input<string | undefined>(undefined);
  readonly retryLabel = input<string | undefined>(undefined);
  readonly retry = output<void>();

  onRetry(): void {
    this.retry.emit();
  }
}
