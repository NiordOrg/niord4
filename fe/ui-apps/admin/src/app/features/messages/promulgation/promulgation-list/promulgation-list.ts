import { NgComponentOutlet } from '@angular/common';
import { Component, computed, effect, inject, input, isDevMode, type Type } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';

import { Icon } from '../../../../shared/icons/icon';
import type { BasePromulgation, Message } from '../../models/message.model';
import { PROMULGATION_RENDERERS, type PromulgationRenderer } from '../promulgation-renderer.token';

@Component({
  selector: 'app-promulgation-list',
  imports: [NgComponentOutlet, Icon, TranslocoPipe],
  templateUrl: './promulgation-list.html',
  exportAs: 'appPromulgationList',
  // `@container` makes the channel grid key off THIS list's own width (not the viewport): the
  // narrow live-preview pane stacks the channels, while the wide status page / details dialog keep
  // them 2-up. `block` so the host fills its `1fr` cell and the container measures that width.
  host: { class: 'block @container' },
})
export class PromulgationList {
  private readonly renderers =
    inject<readonly PromulgationRenderer[]>(PROMULGATION_RENDERERS, {
      optional: true,
    }) ?? [];
  private readonly warnedUnknown = new Set<string>();

  constructor() {
    // Dev-only missing-renderer warning, moved out of the render-path `rendererFor`
    // (signal-first guideline: side effects live in effect(), not in methods change
    // detection calls). Surfaces a deployment shipping a backend promulgation type
    // without a matching frontend renderer; production renders nothing for the entry.
    if (isDevMode()) {
      effect(() => {
        const registry = this.registry();
        for (const p of this.visible()) {
          const serviceId = p.type.serviceId;
          if (!registry.has(serviceId) && !this.warnedUnknown.has(serviceId)) {
            this.warnedUnknown.add(serviceId);
            console.warn(
              `[PromulgationList] No renderer registered for promulgation serviceId "${serviceId}"`,
            );
          }
        }
      });
    }
  }

  readonly msg = input.required<Message>();

  /**
   * When false (default) only promulgated entries render (the read-only viewer). When true every
   * entry renders — an unpromulgated one greyed, and a
   * MANDATORY-but-unpromulgated one marked with a warning glyph — for the editor's all-channels view.
   */
  readonly showAll = input(false);

  private readonly registry = computed(
    () => new Map(this.renderers.map((r) => [r.serviceId, r.component])),
  );

  readonly visible = computed<readonly BasePromulgation[]>(() => {
    const all = this.msg().promulgations ?? [];
    return this.showAll() ? all : all.filter((p) => p.promulgate);
  });

  readonly hasVisible = computed<boolean>(() => this.visible().length > 0);

  protected inputsFor(p: BasePromulgation): Record<string, unknown> {
    return { promulgation: p };
  }

  /** A MANDATORY channel not set to promulgate — publish-blocking, shown with a warning glyph. */
  protected isMandatoryUnset(p: BasePromulgation): boolean {
    return p.type.requirement === 'MANDATORY' && !p.promulgate;
  }

  protected rendererFor(serviceId: string): Type<unknown> | null {
    return this.registry().get(serviceId) ?? null;
  }
}
