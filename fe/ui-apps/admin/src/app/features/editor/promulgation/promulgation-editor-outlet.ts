import { NgComponentOutlet } from '@angular/common';
import { Component, computed, inject, input, isDevMode, type Type } from '@angular/core';

import type { BasePromulgation } from '../../messages/models/message.model';
import {
  PROMULGATION_EDITORS,
  type PromulgationEditor,
  type PromulgationEditorContext,
} from './promulgation-editor.token';

/**
 * Dispatches the editable channel editor for one promulgation entry by `type.serviceId` via the
 * injected `PROMULGATION_EDITORS` list — the editable mirror of `PromulgationList`. Passes the entry
 * and the editor context AS INPUTS (`NgComponentOutlet` binds inputs only). An unknown serviceId
 * renders nothing and dev-warns once per id (parity with `PromulgationList.rendererFor`).
 */
@Component({
  selector: 'app-promulgation-editor-outlet',
  imports: [NgComponentOutlet],
  template: `@let component = editorFor(promulgation().type.serviceId);
    @if (component) {
      <ng-container *ngComponentOutlet="component; inputs: inputs()" />
    }`,
})
export class PromulgationEditorOutlet {
  private readonly editors =
    inject<readonly PromulgationEditor[]>(PROMULGATION_EDITORS, { optional: true }) ?? [];
  private readonly warnedUnknown = new Set<string>();

  readonly promulgation = input.required<BasePromulgation>();
  readonly context = input.required<PromulgationEditorContext>();

  private readonly registry = computed(
    () => new Map(this.editors.map((e) => [e.serviceId, e.component])),
  );

  protected inputs(): Record<string, unknown> {
    return { promulgation: this.promulgation(), context: this.context() };
  }

  protected editorFor(serviceId: string): Type<unknown> | null {
    const component = this.registry().get(serviceId);
    if (component) return component;
    if (isDevMode() && !this.warnedUnknown.has(serviceId)) {
      this.warnedUnknown.add(serviceId);
      // Surfaces a backend promulgation type shipped without a matching frontend editor during
      // development; production renders nothing for the entry.
      console.warn(
        `[PromulgationEditorOutlet] No editor registered for promulgation serviceId "${serviceId}"`,
      );
    }
    return null;
  }
}
