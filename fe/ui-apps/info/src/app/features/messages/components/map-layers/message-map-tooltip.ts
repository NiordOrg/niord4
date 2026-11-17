import { Component, effect, inject, signal } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';

import { followMapHover } from '@niord/map/map-hover';
import { MapShellService } from '@niord/map/map-shell.service';
import { Message } from '../../../../core/models/message.model';
import { MessageIdBadgeComponent } from '../message-id-badge/message-id-badge.component';
import { ContentLang } from '../../../../shared/directives/content-lang';

interface TooltipState {
  readonly messages: readonly Message[];
  readonly more: number;
  readonly left: number;
  readonly top: number;
}

/** What a hover names before the reader has to click to see the rest. */
const MAX_TOOLTIP_MESSAGES = 3;
/** Clear of the cursor, so the pointer never covers the first badge. */
const HOVER_OFFSET = 15;

/**
 * What a pointer resting on the map says about the messages under it. The rows are a template
 * rather than a string of HTML: the badges are the same component the lists render, and
 * nothing here has to be trusted into the DOM.
 *
 * The kit decides when a hover is resolved — at a bounded rate, always including the place the
 * pointer comes to rest — and when it ends: the pointer leaving the map or dragging it.
 */
@Component({
  selector: 'app-message-map-tooltip',
  imports: [MessageIdBadgeComponent, TranslocoPipe, ContentLang],
  template: `
    @let s = state();
    @if (s) {
      <div
        class="pointer-events-none absolute z-20 max-w-xs -translate-x-1/2 -translate-y-full rounded border border-surface-200 bg-surface-0/95 p-2 text-xs shadow-niord-lg"
        [style.left.px]="s.left"
        [style.top.px]="s.top"
      >
        @for (msg of s.messages; track msg.id) {
          <div class="flex items-center gap-1 py-1 not-last:border-b not-last:border-surface-200">
            <app-message-id-badge [msg]="msg" />
            @if (msg.descs?.[0]; as desc) {
              @if (desc.title) {
                <strong [appContentLang]="desc.lang">{{ desc.title }}</strong>
              }
            }
          </div>
        }
        @if (s.more > 0) {
          <div
            class="flex items-center justify-center gap-1 py-1 not-last:border-b not-last:border-surface-200"
          >
            {{ 'MORE_MSGS' | transloco: { messageNo: s.more } }}
          </div>
        }
      </div>
    }
  `,
})
export class MessageMapTooltip {
  private readonly shell = inject(MapShellService);

  readonly state = signal<TooltipState | undefined>(undefined);

  /** Pixel → the messages drawn there, deduplicated by whoever owns the features. */
  private resolver: ((pixel: [number, number]) => readonly Message[]) | undefined;

  constructor() {
    effect((onCleanup) => {
      const map = this.shell.map();
      if (!map) return;
      onCleanup(
        followMapHover(map, { hover: (pixel) => this.showAt(pixel), leave: () => this.hide() }),
      );
    });
  }

  setResolver(resolver: (pixel: [number, number]) => readonly Message[]): void {
    this.resolver = resolver;
  }

  hide(): void {
    this.state.set(undefined);
  }

  private showAt(pixel: [number, number]): void {
    if (!this.resolver) return;
    const messages = this.resolver(pixel);
    if (messages.length === 0) {
      this.hide();
      return;
    }
    this.state.set({
      messages: messages.slice(0, MAX_TOOLTIP_MESSAGES),
      more: Math.max(0, messages.length - MAX_TOOLTIP_MESSAGES),
      left: pixel[0],
      top: pixel[1] - HOVER_OFFSET,
    });
  }
}
