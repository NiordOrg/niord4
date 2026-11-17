import { Component, effect, inject, signal } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';

import { followMapHover } from '@niord/map/map-hover';
import { MapShellService } from '@niord/map/map-shell.service';
import { MessageIdBadge } from '../message-id-badge/message-id-badge';
import type { Message } from '../../models/message.model';

interface TooltipPosition {
  readonly left: number;
  readonly top: number;
}

interface TooltipState {
  readonly messages: readonly Message[];
  readonly more: number;
  readonly position: TooltipPosition;
}

const MAX_TOOLTIP_MESSAGES = 3;
const HOVER_OFFSET = 16;

/**
 * The messages under a pointer resting on the map. The kit decides when a hover is resolved —
 * at a bounded rate, always including the place the pointer comes to rest — and when it ends:
 * the pointer leaving the map or dragging it.
 */
@Component({
  selector: 'app-message-map-tooltip',
  template: `
    @let s = state();
    @if (s) {
      <div
        class="pointer-events-none absolute z-20 max-w-xs rounded border bg-surface-0/95 p-2 text-xs shadow-lg dark:bg-surface-900/95 dark:text-surface-50"
        [style.left.px]="s.position.left"
        [style.top.px]="s.position.top"
      >
        <ul class="flex flex-col gap-2">
          @for (msg of s.messages; track msg.id) {
            <li class="flex flex-col gap-1">
              <app-message-id-badge [message]="msg" />
              @if (msg.descs?.[0]?.title; as title) {
                <span class="block break-words whitespace-normal">{{ title }}</span>
              }
            </li>
          }
        </ul>
        @if (s.more > 0) {
          <div class="mt-1 text-surface-500">
            {{ 'messages.map.tooltip.moreMessages' | transloco: { count: s.more } }}
          </div>
        }
      </div>
    }
  `,
  imports: [MessageIdBadge, TranslocoPipe],
})
export class MessageMapTooltip {
  private readonly shell = inject(MapShellService);

  readonly state = signal<TooltipState | undefined>(undefined);

  /** Caller-provided resolver: pixel → list of Message instances (deduped by caller). */
  private resolver: ((pixel: [number, number]) => readonly Message[]) | undefined;

  constructor() {
    effect((onCleanup) => {
      const map = this.shell.map();
      if (!map) return;
      onCleanup(
        followMapHover(map, {
          hover: (pixel) => this.showAt(pixel),
          leave: () => this.state.set(undefined),
        }),
      );
    });
  }

  setResolver(resolver: (pixel: [number, number]) => readonly Message[]): void {
    this.resolver = resolver;
  }

  private showAt(pixel: [number, number]): void {
    if (!this.resolver) return;
    const messages = this.resolver(pixel);
    if (messages.length === 0) {
      this.state.set(undefined);
      return;
    }
    const visible = messages.slice(0, MAX_TOOLTIP_MESSAGES);
    const more = Math.max(0, messages.length - MAX_TOOLTIP_MESSAGES);
    this.state.set({
      messages: visible,
      more,
      position: { left: pixel[0] + HOVER_OFFSET, top: pixel[1] + HOVER_OFFSET },
    });
  }
}
