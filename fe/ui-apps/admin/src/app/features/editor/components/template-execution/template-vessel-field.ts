import { Component, computed, input, output } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';

import { RemoteValidatedInput } from './remote-validated-input';

/** The `work_vessel` std-field value stored under `vessel` on a template's `templateData`. */
export interface VesselData {
  name?: string;
  callSign?: string;
  contact?: boolean;
  channel?: number;
  guardVessels?: boolean;
  minDist?: number;
  minDistType?: string;
}

/**
 * The bespoke `work_vessel` standard template field (the sample's templates name it): vessel name, call sign (server-validated),
 * a VHF-contact toggle + channel, a
 * guard-vessels toggle, and a minimum-distance caution with an nm/m unit. Value-in / value-out — the
 * per-template card owns the `templateData[i].vessel` record.
 */
@Component({
  selector: 'app-template-vessel-field',
  imports: [TranslocoPipe, RemoteValidatedInput],
  template: `
    <div class="flex flex-col gap-2 text-sm">
      <div class="flex flex-wrap items-center gap-2">
        <span class="w-32 shrink-0 font-medium">{{ 'term.name' | transloco }}</span>
        <input
          type="text"
          class="text-input min-w-0 flex-1"
          [value]="current().name ?? ''"
          (change)="patch({ name: $any($event.target).value || undefined })"
          [attr.aria-label]="'term.name' | transloco"
        />
      </div>

      <div class="flex flex-wrap items-center gap-2">
        <span class="w-32 shrink-0 font-medium">{{ 'term.call_sign' | transloco }}</span>
        <app-remote-validated-input
          class="min-w-0 flex-1"
          kind="call-sign"
          [value]="current().callSign"
          (valueChange)="patch({ callSign: $any($event) })"
          [ariaLabel]="'term.call_sign' | transloco"
        />
      </div>

      <div class="flex flex-wrap items-center gap-2">
        <span class="w-32 shrink-0 font-medium">{{ 'term.contact' | transloco }}</span>
        <label class="flex items-center gap-1.5">
          <input
            type="checkbox"
            class="checkbox"
            [checked]="current().contact ?? false"
            (change)="patch({ contact: $any($event.target).checked })"
          />
          <span>{{ 'editor.template.vessel.contact' | transloco }}</span>
        </label>
        <input
          type="number"
          step="any"
          class="text-input w-20"
          [disabled]="!current().contact"
          [value]="current().channel ?? ''"
          (change)="patch({ channel: toNumber($any($event.target).value) })"
          [attr.aria-label]="'editor.template.vessel.contact' | transloco"
        />
      </div>

      <div class="flex flex-wrap items-center gap-2">
        <span class="w-32 shrink-0 font-medium">{{
          'editor.template.vessel.guardVessels' | transloco
        }}</span>
        <label class="flex items-center gap-1.5">
          <input
            type="checkbox"
            class="checkbox"
            [checked]="current().guardVessels ?? false"
            (change)="patch({ guardVessels: $any($event.target).checked })"
          />
          <span>{{ 'editor.template.vessel.guardVesselsInArea' | transloco }}</span>
        </label>
      </div>

      <div class="flex flex-wrap items-center gap-2">
        <span class="w-32 shrink-0 font-medium">{{
          'editor.template.caution.title' | transloco
        }}</span>
        <span>{{ 'editor.template.caution.minDist' | transloco }}</span>
        <input
          type="number"
          step="any"
          class="text-input w-20"
          [value]="current().minDist ?? ''"
          (change)="patch({ minDist: toNumber($any($event.target).value) })"
          [attr.aria-label]="'editor.template.caution.minDist' | transloco"
        />
        <div class="flex gap-1">
          <button
            type="button"
            class="rounded border px-2 py-0.5 text-xs"
            [class.border-primary]="current().minDistType === 'nm'"
            [class.bg-primary]="current().minDistType === 'nm'"
            [class.text-white]="current().minDistType === 'nm'"
            [class.border-surface-300]="current().minDistType !== 'nm'"
            (click)="patch({ minDistType: 'nm' })"
          >
            nm
          </button>
          <button
            type="button"
            class="rounded border px-2 py-0.5 text-xs"
            [class.border-primary]="current().minDistType === 'm'"
            [class.bg-primary]="current().minDistType === 'm'"
            [class.text-white]="current().minDistType === 'm'"
            [class.border-surface-300]="current().minDistType !== 'm'"
            (click)="patch({ minDistType: 'm' })"
          >
            m
          </button>
        </div>
      </div>
    </div>
  `,
  host: { class: 'block' },
})
export class TemplateVesselField {
  readonly value = input<VesselData | undefined>(undefined);
  readonly valueChange = output<VesselData>();

  /** The stored value or the default (`contact` on, unit metres) for display. */
  protected readonly current = computed<VesselData>(
    () => this.value() ?? { contact: true, minDistType: 'm' },
  );

  protected patch(patch: Partial<VesselData>): void {
    this.valueChange.emit({ ...this.current(), ...patch });
  }

  protected toNumber(raw: string): number | undefined {
    return raw === '' ? undefined : Number(raw);
  }
}
