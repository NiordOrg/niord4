import { httpResource } from '@angular/common/http';
import {
  Component,
  computed,
  DestroyRef,
  effect,
  inject,
  input,
  output,
  signal,
  untracked,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { TranslocoPipe } from '@jsverse/transloco';
import { debounceTime, Subject } from 'rxjs';

import { APP_CONFIG } from '../../../../core/config/app-config.token';
import { DomainFacade } from '../../../../core/domain/facades/domain.facade';
import { LanguageService } from '../../../../core/main-shell/services/language.service';
import { Drawer } from '../../../../shared/components/drawer/drawer';
import { EntityTree } from '../../../../shared/components/entity-tree/entity-tree';
import { MessageDetailsRenderer } from '../../../messages/components/message-details-renderer/message-details-renderer';
import type { Message } from '../../../messages/models/message.model';
import type { CategoryRef } from '../../../messages/models/messages-filter-params.model';
import type { SystemCategory } from '../../../messages/models/template.model';
import { CategoriesApiService } from '../../../messages/services/api/categories-api.service';
import { categoryName, fetchHydratedTemplates, isPerPosition } from './template-search.util';

/**
 * The template SELECTION drawer — the browse-and-pick surface for choosing templates.
 * Three panes inside a wide drawer: a single-select category (ancestor) filter
 * fed by the real category tree, a searchable checklist of the domain's TEMPLATE categories (each
 * tagged per-position / template-level), and an example panel that renders the hovered template's
 * linked example message. Confirming emits the checked templates (hydrated with their
 * `stdTemplateFields` / `templateParams`) — the execution view reconciles them into its ordered set.
 *
 * All data is backend-driven: `GET /categories/category-roots` (tree), `GET
 * /categories/search?type=TEMPLATE` (list, domain-scoped), `getTemplateDetails` (hydration) and
 * `GET /messages/message/{id}` (example).
 */
@Component({
  selector: 'app-template-selection-drawer',
  imports: [TranslocoPipe, Drawer, EntityTree, MessageDetailsRenderer],
  templateUrl: './template-selection-drawer.html',
  host: { class: 'contents' },
})
export class TemplateSelectionDrawer {
  private readonly categoriesApi = inject(CategoriesApiService);
  private readonly domain = inject(DomainFacade);
  private readonly language = inject(LanguageService);
  private readonly apiBaseUrl = inject(APP_CONFIG).apiBaseUrl;
  private readonly destroyRef = inject(DestroyRef);

  /** Parent-owned visibility. */
  readonly visible = input<boolean>(false);
  /** Ids of the templates already chosen — pre-checked when the drawer opens. */
  readonly preselectedIds = input<readonly number[]>([]);

  /** The checked templates (hydrated), emitted on Confirm. */
  readonly confirmed = output<readonly SystemCategory[]>();
  /** A close intent passed the drawer's gate (parent flips `visible`). */
  readonly closed = output<void>();

  protected readonly lang = computed<string>(() => this.language.selectedLanguage());

  protected readonly roots = signal<readonly CategoryRef[]>([]);
  private rootsLoaded = false;

  /** The selected parent-category filter (undefined = all templates). */
  protected readonly ancestorId = signal<number | undefined>(undefined);
  protected readonly searchName = signal('');
  protected readonly results = signal<readonly SystemCategory[]>([]);
  protected readonly loading = signal(false);
  protected readonly checkedIds = signal<ReadonlySet<number>>(new Set());

  /** The template whose example is shown in the right pane (on hover). */
  protected readonly hovered = signal<SystemCategory | undefined>(undefined);
  private readonly hoveredMessageId = computed<string | undefined>(() => this.hovered()?.messageId);

  /** The hovered template's example message, localized to the active language, or idle when none. */
  protected readonly example = httpResource<Message>(() => {
    const id = this.hoveredMessageId();
    return id
      ? {
          url: `${this.apiBaseUrl}/messages/message/${encodeURIComponent(id)}`,
          // Localize to the active language: the backend filters descs to `lang`, so the example
          // renders in ONE language. Without it a partially-translated example mixes DA and EN
          // (the renderer falls back to descs[0] per entity).
          params: { lang: this.lang() },
        }
      : undefined;
  });

  protected readonly selectedCount = computed<number>(() => this.checkedIds().size);

  private readonly search$ = new Subject<void>();
  private fetchSeq = 0;

  constructor() {
    // Debounce only free-text typing; category-filter changes and open re-fetch immediately.
    this.search$.pipe(debounceTime(300), takeUntilDestroyed()).subscribe(() => this.fetch());

    // Opening edge: (lazily) load the category tree, seed the checked set from the current chosen
    // templates, clear the hover, and fetch the full domain template list.
    effect(() => {
      if (!this.visible()) {
        return;
      }
      untracked(() => this.onOpened());
    });
  }

  private onOpened(): void {
    this.checkedIds.set(new Set(this.preselectedIds()));
    this.hovered.set(undefined);
    this.searchName.set('');
    this.ancestorId.set(undefined);
    if (!this.rootsLoaded) {
      this.rootsLoaded = true;
      this.categoriesApi
        .getRoots({ language: this.lang() })
        .pipe(takeUntilDestroyed(this.destroyRef))
        // Only parent categories (type CATEGORY) drive the ancestor filter — the TEMPLATE leaves
        // live in the checklist, not the tree.
        .subscribe((roots) => this.roots.set(pruneToCategories(roots)));
    }
    this.fetch();
  }

  /** Fetch + hydrate the domain template list for the current name / ancestor filter. */
  private fetch(): void {
    const seq = ++this.fetchSeq;
    this.loading.set(true);
    fetchHydratedTemplates(this.categoriesApi, {
      domainId: this.domain.sessionDomain()?.domainId,
      language: this.lang(),
      name: this.searchName().trim() || undefined,
      ancestorId: this.ancestorId(),
    })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (results) => {
          if (seq !== this.fetchSeq) {
            return;
          }
          this.results.set(results);
          this.loading.set(false);
        },
        error: () => {
          if (seq !== this.fetchSeq) {
            return;
          }
          this.results.set([]);
          this.loading.set(false);
        },
      });
  }

  protected onSearch(name: string): void {
    this.searchName.set(name);
    this.search$.next();
  }

  protected selectAncestor(id: number | undefined): void {
    this.ancestorId.set(id);
    this.fetch();
  }

  protected nameOf(cat: CategoryRef): string {
    return categoryName(cat, this.lang());
  }

  protected readonly isPerPosition = isPerPosition;

  protected isChecked(cat: SystemCategory): boolean {
    return this.checkedIds().has(cat.id);
  }

  protected toggle(cat: SystemCategory): void {
    this.checkedIds.update((set) => {
      const next = new Set(set);
      if (next.has(cat.id)) {
        next.delete(cat.id);
      } else {
        next.add(cat.id);
      }
      return next;
    });
  }

  protected onHover(cat: SystemCategory): void {
    this.hovered.set(cat);
  }

  protected confirm(): void {
    const checked = this.checkedIds();
    this.confirmed.emit(this.results().filter((cat) => checked.has(cat.id)));
  }
}

/** Recursively keep only parent categories (drop TEMPLATE leaves) for the ancestor filter tree. */
function pruneToCategories(nodes: readonly CategoryRef[]): CategoryRef[] {
  return nodes
    .filter((node) => (node as SystemCategory).type !== 'TEMPLATE')
    .map((node) => ({
      ...node,
      children: pruneToCategories((node.children ?? []) as CategoryRef[]),
    }));
}
