import { Component, inject } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';
import { MessageMapComponent } from '../message-map/message-map.component';
import { Icon } from '../../../../shared/icons/icon';
import { MessagesStore } from '../../messages.store';

@Component({
  selector: 'app-message-map-view',
  imports: [MessageMapComponent, Icon, TranslocoPipe],
  template: `
    <!-- In the flow, under the two bars, and as tall as the part of the viewport they leave:
         the viewport less what is pinned, which is nothing on a short screen where the bars
         scroll away. The footer follows below the map rather than under the bars. Busy while
         the list is being read: an empty chart reads as "no warnings in this area". -->
    <div
      class="relative h-[calc(100dvh-var(--niord-pinned-bars-height))]"
      [attr.aria-busy]="store.loading()"
    >
      <!-- Over the chart rather than beside it: the map fills the frame, and a reader who cannot
           see why it is empty has nowhere else to look. It starts clear of the zoom buttons in
           the same corner, which stay usable, and passes clicks through to the chart
           underneath. The live region is there from the start, so a read that fails is
           announced and not only drawn. -->
      <div role="alert" class="pointer-events-none absolute top-2 right-2 left-11 z-10">
        @if (store.failed()) {
          <p class="message message-error">
            <app-icon name="lucideTriangleAlert" class="message-icon" />
            {{ 'MSGS_LOAD_FAILED' | transloco }}
          </p>
        }
      </div>

      @if (store.loading()) {
        <p
          class="pointer-events-none absolute top-2 left-1/2 z-10 -translate-x-1/2 rounded-full bg-surface-0 p-1.5 text-fg-muted shadow-niord-md"
          aria-hidden="true"
        >
          <app-icon name="lucideLoaderCircle" size="1.5em" class="motion-safe:animate-spin" />
        </p>
      }

      <app-message-map
        class="absolute inset-0 bg-map-sea"
        [messages]="store.messages()"
        [showNoPosMessages]="true"
        [fitExtent]="true"
        [rootArea]="store.rootArea()"
        [maxZoom]="10"
      />
    </div>
  `,
})
export class MessageMapViewComponent {
  protected readonly store = inject(MessagesStore);
}
