import { Component, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute } from '@angular/router';
import { map } from 'rxjs';

import { ComingSoonPage } from '../../../shared/components/coming-soon/coming-soon';

/**
 * Placeholder route component for sections whose feature work hasn't shipped yet.
 * Registered at the section's final URL so deep links and the rail work from day
 * one; the owning batch later swaps this for the real section component.
 */
@Component({
  selector: 'app-admin-section-stub',
  imports: [ComingSoonPage],
  template: `<app-coming-soon [titleKey]="labelKey()" />`,
  host: { class: 'flex min-w-0 flex-1 flex-col' },
})
export class AdminSectionStub {
  private readonly route = inject(ActivatedRoute);

  protected readonly labelKey = toSignal(
    this.route.data.pipe(map((data) => data['sectionLabelKey'] as string)),
    { initialValue: this.route.snapshot.data['sectionLabelKey'] as string },
  );
}
