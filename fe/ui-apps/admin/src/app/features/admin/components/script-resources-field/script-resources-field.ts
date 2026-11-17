import {
  Component,
  computed,
  effect,
  input,
  model,
  output,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import {
  CdkDrag,
  type CdkDragDrop,
  CdkDragHandle,
  CdkDropList,
  moveItemInArray,
} from '@angular/cdk/drag-drop';
import { FormValueControl } from '@angular/forms/signals';
import { TranslocoPipe } from '@jsverse/transloco';

import { Icon } from '../../../../shared/icons/icon';
import { ScriptResourcePickerPopover } from '../script-resource-picker-popover/script-resource-picker-popover';
import { ScriptResourceType } from '../../services/api/script-resources-api.service';

/**
 * Reusable script-resource PATH field — a Signal Forms `FormValueControl` consumed
 * by other admin sections. Two modes:
 *
 * - single (`[multiple]="false"`, the default): a string value — one path input with
 *   a pick-from-catalog button and a pencil deep-link to the resource editor.
 * - multiple: a `string[]` value — a reorderable list of path rows (keyboard move
 *   up/down), each with pick + deep-link, with a ≥1-row invariant.
 *
 * Validation lives in the consuming form's schema (Signal Forms controls don't
 * self-validate); a consumer typically applies `required` + a path `pattern`.
 * `[pathPattern]` narrows the pick-from-catalog popover to a single type (e.g.
 * Reports pass `.+\.(ftl|FTL)` → FM only); the pencil deep-link opens
 * `/sysadmin/script-resources?path=…` in a new tab, where the path is looked up,
 * never created (a query param, not a path segment, so slash-containing resource
 * paths survive the SPA-fallback server).
 */
@Component({
  selector: 'app-script-resources-field',
  imports: [Icon, ScriptResourcePickerPopover, CdkDropList, CdkDrag, CdkDragHandle, TranslocoPipe],
  templateUrl: './script-resources-field.html',
  styles: `
    .cdk-drag-placeholder {
      opacity: 0.4;
    }
  `,
  host: { class: 'block' },
})
export class ScriptResourcesField implements FormValueControl<string | readonly string[]> {
  readonly multiple = input(false);
  // Not named `pattern`: Signal Forms' [formField] reflects the field's `pattern`
  // constraint (readonly RegExp[]) onto a same-named control input, clashing with
  // this string input.
  readonly pathPattern = input('.+\\.(ftl|FTL|js|JS)');
  readonly pathsChanged = output<void>();

  /** Two-way bound by `[formField]`, or `[value]`/`(valueChange)`. */
  readonly value = model<string | readonly string[]>('');
  /** Set by the form when the field is disabled. */
  readonly disabled = input(false);
  /** Emitted on blur for Signal Forms touched-tracking. */
  readonly touch = output<void>();

  protected readonly single = signal('');
  protected readonly paths = signal<readonly string[]>(['']);

  protected readonly picker = viewChild.required(ScriptResourcePickerPopover);

  /** Which control the next pick writes to: the single value or a multiple-mode row index. */
  private pickTarget: 'single' | number = 'single';
  /** Last value written to/from the model — guards the sync effect against the emit() echo. */
  private lastSynced: string | readonly string[] = '';

  constructor() {
    // Push an external value write (form/`[value]`) into the internal single/paths state.
    // The guard skips the echo from emit(), which sets `value` from single/paths.
    effect(() => {
      const v = this.value();
      const multiple = this.multiple();
      untracked(() => {
        if (v === this.lastSynced) {
          return;
        }
        this.lastSynced = v;
        if (multiple) {
          const array = Array.isArray(v) ? [...v] : [];
          this.paths.set(array.length > 0 ? array : ['']);
        } else {
          this.single.set(typeof v === 'string' ? v : '');
        }
      });
    });
  }

  /** Snapshot of the paths for the template (iteration + length checks). */
  protected readonly pathRows = computed(() => [...this.paths()]);

  // ── Single mode ───────────────────────────────────────────────────────
  protected onSingleInput(value: string): void {
    this.single.set(value);
    this.emit();
  }

  // ── Multiple mode ─────────────────────────────────────────────────────
  protected onPathInput(index: number, value: string): void {
    this.paths.update((paths) => paths.map((path, i) => (i === index ? value : path)));
    this.emit();
  }

  protected addPath(): void {
    this.paths.update((paths) => [...paths, '']);
    this.emit();
  }

  protected removePath(index: number): void {
    this.paths.update((paths) => {
      const next = paths.filter((_, i) => i !== index);
      return next.length > 0 ? next : ['']; // ≥1-row invariant
    });
    this.emit();
  }

  protected moveUp(index: number): void {
    if (index <= 0) {
      return;
    }
    this.swap(index, index - 1);
  }

  protected moveDown(index: number): void {
    if (index >= this.paths().length - 1) {
      return;
    }
    this.swap(index, index + 1);
  }

  private swap(a: number, b: number): void {
    this.paths.update((paths) => {
      const next = [...paths];
      [next[a], next[b]] = [next[b], next[a]];
      return next;
    });
    this.emit();
  }

  /** Drag-reorder a path row (the grip handle); the move-up/down buttons remain the keyboard path. */
  protected onDrop(event: CdkDragDrop<unknown>): void {
    if (event.previousIndex === event.currentIndex) {
      return;
    }
    this.paths.update((paths) => {
      const next = [...paths];
      moveItemInArray(next, event.previousIndex, event.currentIndex);
      return next;
    });
    this.emit();
  }

  // ── Picker + deep-link ────────────────────────────────────────────────
  protected openPickerForSingle(event: Event): void {
    this.pickTarget = 'single';
    this.picker().open(event.currentTarget as HTMLElement, this.pickerType());
  }

  protected openPickerForRow(index: number, event: Event): void {
    this.pickTarget = index;
    this.picker().open(event.currentTarget as HTMLElement, this.pickerType());
  }

  protected onPicked(path: string): void {
    if (this.pickTarget === 'single') {
      this.onSingleInput(path);
    } else {
      this.onPathInput(this.pickTarget, path);
    }
  }

  /**
   * Deep-link to the resource editor, which looks the path up and never creates it;
   * the path rides a query param (it contains slashes).
   */
  protected deepLink(path: string): string {
    return `/sysadmin/script-resources?path=${encodeURIComponent(path)}`;
  }

  /** Narrow the picker to a single type when the pattern restricts to one extension. */
  private pickerType(): ScriptResourceType | undefined {
    const pattern = this.pathPattern().toLowerCase();
    const ftl = pattern.includes('ftl');
    const js = pattern.includes('js');
    if (ftl && !js) {
      return 'FM';
    }
    if (js && !ftl) {
      return 'JS';
    }
    // Single mode defaults to the FM catalog; multiple stays unfiltered.
    return this.multiple() ? undefined : 'FM';
  }

  private emit(): void {
    const next = this.multiple() ? this.paths() : this.single();
    this.lastSynced = next;
    this.value.set(next);
    this.touch.emit();
    this.pathsChanged.emit();
  }
}
