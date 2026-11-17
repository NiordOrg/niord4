import {
  Component,
  ErrorHandler,
  inject,
  input,
  type OnInit,
  signal,
  type Type,
} from '@angular/core';
import { NgComponentOutlet } from '@angular/common';
import { COMPONENT_SLOTS, type SlotName } from './component-slots';

/**
 * Renders the components a country registered for one named slot, in the order it registered
 * them, each once its chunk has arrived. With no registration it renders nothing, which is what
 * every build of the base product shows. A chunk that fails to arrive leaves its place empty and
 * is reported, so one country component that cannot load never takes the page with it.
 */
@Component({
  selector: 'app-slot',
  imports: [NgComponentOutlet],
  template: `
    @for (component of components(); track $index) {
      @if (component) {
        <ng-container [ngComponentOutlet]="component" />
      }
    }
  `,
})
export class SlotOutletComponent implements OnInit {
  readonly name = input.required<SlotName>();

  private readonly registrations = inject(COMPONENT_SLOTS, { optional: true }) ?? [];
  private readonly errorHandler = inject(ErrorHandler);

  /** One place per registration for this slot; empty until its component has arrived. */
  protected readonly components = signal<readonly (Type<unknown> | undefined)[]>([]);

  // The name is fixed in the template that places the outlet, so each chunk is fetched once.
  ngOnInit(): void {
    const loaders = this.registrations
      .filter((registration) => registration.slot === this.name())
      .map((registration) => registration.load);
    this.components.set(loaders.map(() => undefined));
    loaders.forEach((load, index) => {
      // Called through `then`, a loader that throws is caught as one that rejects is.
      void Promise.resolve()
        .then(load)
        .then(
          (component) => this.place(index, component),
          (error: unknown) => this.errorHandler.handleError(error),
        );
    });
  }

  private place(index: number, component: Type<unknown>): void {
    this.components.update((all) => all.map((each, i) => (i === index ? component : each)));
  }
}
