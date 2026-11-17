import { Component, computed, input, signal } from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';

import { Icon } from '../../icons/icon';
import { diffJson, type DiffNode } from '../../util/json-diff';

/**
 * Renders a recursive diff of up to two revision snapshots (opaque JSON strings). 0 snapshots → an
 * empty (cleared) pane; 1 → the snapshot compared to itself (data shown, no highlights); 2 → the
 * change/add/delete diff. Container nodes collapse/expand. A snapshot that fails to parse logs to
 * the console and shows nothing — deliberately no toast.
 */
@Component({
  selector: 'app-json-diff-pane',
  imports: [NgTemplateOutlet, Icon],
  template: `
    @if (root(); as node) {
      <div class="overflow-auto font-mono text-xs leading-relaxed">
        <ng-container *ngTemplateOutlet="nodeTpl; context: { $implicit: node }" />
      </div>
    }

    <ng-template #nodeTpl let-node>
      @if (node.children) {
        <div [class]="kindClass(node)">
          <button
            type="button"
            class="inline-flex items-center gap-1 hover:underline"
            [attr.aria-expanded]="!isCollapsed(node.path)"
            (click)="toggle(node.path)"
          >
            <app-icon
              [name]="isCollapsed(node.path) ? 'lucideChevronRight' : 'lucideChevronDown'"
              class="text-[0.65rem]"
            />
            <span>{{ node.label }}</span>
          </button>
          @if (!isCollapsed(node.path)) {
            <ul class="ml-3 border-l border-surface-200 pl-3 dark:border-surface-700">
              @for (child of node.children; track child.path) {
                <li>
                  <ng-container *ngTemplateOutlet="nodeTpl; context: { $implicit: child }" />
                </li>
              }
            </ul>
          }
        </div>
      } @else {
        <div [class]="kindClass(node)">
          <span class="text-fg-muted">{{ node.label }}:</span>
          @if (node.kind === 'changed') {
            <span class="line-through opacity-60">{{ node.oldValue }}</span>
            <span> → {{ node.newValue }}</span>
          } @else if (node.kind === 'removed') {
            <span>{{ node.oldValue }}</span>
          } @else {
            <span>{{ node.newValue }}</span>
          }
        </div>
      }
    </ng-template>
  `,
  host: { class: 'contents' },
})
export class JsonDiffPane {
  /** 0, 1, or 2 revision snapshot JSON strings. */
  readonly snapshots = input<readonly string[]>([]);

  private readonly collapsed = signal<ReadonlySet<string>>(new Set());

  protected readonly root = computed<DiffNode | undefined>(() => {
    const snapshots = this.snapshots();
    if (snapshots.length === 0) {
      return undefined;
    }
    try {
      const left: unknown = JSON.parse(snapshots[0]);
      // 1-selected compares the snapshot to itself (data shown, no highlights).
      const right: unknown = snapshots.length > 1 ? JSON.parse(snapshots[1]) : left;
      return diffJson(left, right);
    } catch (error) {
      console.error('JsonDiffPane: could not parse a revision snapshot', error);
      return undefined;
    }
  });

  protected isCollapsed(path: string): boolean {
    return this.collapsed().has(path);
  }

  protected toggle(path: string): void {
    const next = new Set(this.collapsed());
    if (next.has(path)) {
      next.delete(path);
    } else {
      next.add(path);
    }
    this.collapsed.set(next);
  }

  protected kindClass(node: DiffNode): string {
    switch (node.kind) {
      case 'added':
        return 'text-[var(--niord-diff-added)]';
      case 'removed':
        return 'text-[var(--niord-diff-removed)]';
      case 'changed':
        return 'text-[var(--niord-diff-changed)]';
      default:
        return 'text-fg';
    }
  }
}
