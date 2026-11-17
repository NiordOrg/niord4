import { NgComponentOutlet } from '@angular/common';
import { Component, ErrorHandler, inject, input, OnInit, signal, type Type } from '@angular/core';

import { COMPONENT_SLOTS, type SlotName } from './component-slots';

/**
 * Renders the components a country registered for one slot, in registration order, each once its
 * chunk has loaded. With none registered it renders nothing, and the host takes no box of its own,
 * so an empty slot leaves the surrounding layout as it was. A chunk that fails to load renders
 * nothing and goes to the ErrorHandler.
 */
@Component({
  selector: 'app-slot',
  imports: [NgComponentOutlet],
  template: `
    @for (component of components(); track $index) {
      @if (component) {
        <ng-container *ngComponentOutlet="component" />
      }
    }
  `,
  host: { class: 'contents' },
})
export class SlotOutlet implements OnInit {
  readonly name = input.required<SlotName>();

  private readonly registrations = inject(COMPONENT_SLOTS, { optional: true }) ?? [];
  private readonly errorHandler = inject(ErrorHandler);
  // One place per registration for this slot, filled as its loader resolves, so the order holds
  // whichever chunk arrives first.
  protected readonly components = signal<readonly (Type<unknown> | undefined)[]>([]);

  ngOnInit(): void {
    const loaders = this.registrations
      .filter((registration) => registration.slot === this.name())
      .map((registration) => registration.load);
    this.components.set(loaders.map(() => undefined));
    loaders.forEach((load, index) => {
      // Called inside the promise chain, so a loader that throws before it returns a promise is
      // reported like a rejected one and leaves the host template and the other slots standing.
      void Promise.resolve()
        .then(load)
        .then(
          (component) =>
            this.components.update((current) =>
              current.map((existing, i) => (i === index ? component : existing)),
            ),
          (error: unknown) => this.errorHandler.handleError(error),
        );
    });
  }
}
