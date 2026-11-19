import { ActiveDescendantKeyManager } from '@angular/cdk/a11y';
import { NgTemplateOutlet } from '@angular/common';
import { httpResource } from '@angular/common/http';
import {
  Component,
  DestroyRef,
  type ElementRef,
  Injector,
  afterNextRender,
  computed,
  effect,
  inject,
  output,
  signal,
  viewChild,
  viewChildren,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { TranslocoPipe } from '@jsverse/transloco';
import { Subject, debounceTime } from 'rxjs';

import { APP_CONFIG } from '../../../../core/config/app-config.token';
import { DomainFacade } from '../../../../core/domain/facades/domain.facade';
import { LanguageService } from '../../../../core/main-shell/services/language.service';
import { ErrorBanner } from '../../../../shared/components/error-banner/error-banner';
import { PickerPopover } from '../../../../shared/components/picker-popover/picker-popover';
import { AutocompleteOption } from '../../../../shared/forms/autocomplete/autocomplete-option';
import { Icon } from '../../../../shared/icons/icon';
import { mediaQuerySignal } from '../../../../shared/utils/media-query';
import { MessageDetailsRenderer } from '../../../messages/components/message-details-renderer/message-details-renderer';
import type { Message } from '../../../messages/models/message.model';
import type { SystemCategory } from '../../../messages/models/template.model';
import { CategoriesApiService } from '../../../messages/services/api/categories-api.service';
import {
  categoryName,
  fetchHydratedTemplates,
  isPerPosition,
} from '../template-execution/template-search.util';

/**
 * Anchored template-search popover for the Start screen's "from a template" buttons: a
 * quick single-pick alternative to the full selection drawer. A debounced name search over
 * the domain's TEMPLATE categories (hydrated so the per-position tag and the example link
 * work), a hover-driven example pane on the right (the drawer's recipe), and a CLICK picks
 * exactly one template — the host seeds the execute-template flow with it directly.
 *
 * It opens with the caret already in the search box and the full domain list showing, so the
 * trigger click is the ONLY click needed before typing. The list is a combobox listbox:
 * ArrowUp/Down move the active row (which also drives the example pane) and Enter picks it,
 * so the whole flow is reachable without the mouse. Closing hands focus back to the trigger.
 *
 * Below the `md` breakpoint there is no room for the example beside the list, and a touch screen
 * has no hover to drive it, so the panel shows one thing at a time: tapping a row opens that
 * template's example in place of the list, with a Back button and its own pick button. Enter
 * still picks the highlighted row directly.
 */
@Component({
  selector: 'app-template-search-popover',
  imports: [
    NgTemplateOutlet,
    TranslocoPipe,
    PickerPopover,
    ErrorBanner,
    MessageDetailsRenderer,
    AutocompleteOption,
    Icon,
  ],
  template: `
    <app-picker-popover panelWidth="min(44rem, 92vw)">
      <div class="flex min-h-0 gap-3 p-3">
        <!-- Search + single-pick list -->
        <div
          class="flex h-[min(22rem,55vh)] min-w-0 flex-1 flex-col gap-2"
          [class.hidden]="!!detailOf()"
        >
          <input
            #searchInput
            type="text"
            class="text-input"
            role="combobox"
            aria-autocomplete="list"
            [attr.aria-controls]="listboxId"
            [attr.aria-expanded]="results().length > 0"
            [attr.aria-activedescendant]="activeId()"
            [value]="searchName()"
            (input)="onSearch($any($event.target).value)"
            (keydown)="onKeydown($event)"
            [attr.placeholder]="'editor.template.search' | transloco"
            [attr.aria-label]="'editor.template.search' | transloco"
          />
          <div class="min-h-0 flex-1 overflow-y-auto">
            @if (loading()) {
              <p class="p-2 text-sm text-fg-subtle">{{ 'term.loading' | transloco }}</p>
            } @else if (loadFailed()) {
              <app-error-banner
                [title]="'common.fetchError' | transloco"
                [retryLabel]="'common.retry' | transloco"
                (retry)="fetch()"
              />
            } @else if (results().length === 0) {
              <p class="p-2 text-sm text-fg-subtle">
                {{ 'editor.template.drawer.noResults' | transloco }}
              </p>
            } @else {
              <ul class="flex flex-col" role="listbox" [id]="listboxId">
                @for (cat of results(); track cat.id) {
                  <!-- The row IS the option: a nested <button> would put a non-option element
                       between the listbox and its options, breaking the ARIA relationship.
                       Focus stays on the combobox input; picking runs off aria-activedescendant. -->
                  <li
                    [appAutocompleteOption]="cat"
                    #option="appAutocompleteOption"
                    [class]="option.active() ? rowClass + rowActiveClass : rowClass"
                    (mouseenter)="onHover(cat)"
                    (mousedown)="onOptionMousedown($event, cat)"
                  >
                    <span class="min-w-0 flex-1 truncate">{{ nameOf(cat) }}</span>
                    <span
                      class="shrink-0 rounded border border-surface-200 px-1.5 py-0.5 text-[10px] font-semibold tracking-wide text-fg-muted uppercase dark:border-surface-600"
                    >
                      {{
                        (isPerPosition(cat)
                          ? 'editor.template.tag.perPosition'
                          : 'editor.template.tag.templateLevel'
                        ) | transloco
                      }}
                    </span>
                  </li>
                }
              </ul>
            }
          </div>
        </div>

        @if (!narrow()) {
          <!-- Example (shown on hover) -->
          <div
            class="flex h-[min(22rem,55vh)] w-72 shrink-0 flex-col gap-2 border-l border-surface-200 pl-3 dark:border-surface-700"
          >
            <span class="text-xs font-semibold tracking-wide text-fg-muted uppercase">
              {{ 'editor.template.example' | transloco }}
            </span>
            <ng-container [ngTemplateOutlet]="exampleBody" />
          </div>
        } @else if (detailOf(); as cat) {
          <!-- Narrow: the tapped template's example replaces the list, with its own pick button -->
          <div class="flex h-[min(22rem,55vh)] min-w-0 flex-1 flex-col gap-2">
            <div class="flex items-center gap-2">
              <button #detailBack type="button" class="btn-tertiary" (click)="closeDetail()">
                <app-icon name="lucideArrowLeft" />
                <span>{{ 'editor.template.backToList' | transloco }}</span>
              </button>
            </div>
            <span class="truncate text-sm font-semibold text-fg">{{ nameOf(cat) }}</span>
            <ng-container [ngTemplateOutlet]="exampleBody" />
            <button type="button" class="btn-primary w-full justify-center" (click)="pick(cat)">
              {{ 'editor.template.useThis' | transloco }}
            </button>
          </div>
        }
      </div>
    </app-picker-popover>

    <ng-template #exampleBody>
      <div class="min-h-0 flex-1 overflow-y-auto text-[0.8125rem] leading-relaxed">
        @if (!hovered()) {
          <p class="p-2 text-sm text-fg-subtle">
            {{ 'editor.template.drawer.exampleHint' | transloco }}
          </p>
        } @else if (!hovered()!.messageId) {
          <p class="p-2 text-sm text-fg-subtle">
            {{ 'editor.template.drawer.noExample' | transloco }}
          </p>
        } @else if (example.isLoading()) {
          <p class="p-2 text-sm text-fg-subtle">{{ 'term.loading' | transloco }}</p>
        } @else if (example.error()) {
          <app-error-banner
            [title]="'common.fetchError' | transloco"
            [retryLabel]="'common.retry' | transloco"
            (retry)="example.reload()"
          />
        } @else if (example.hasValue()) {
          <app-message-details-renderer
            [message]="example.value()!"
            [showPromulgation]="true"
            [language]="lang()"
          />
        } @else {
          <p class="p-2 text-sm text-fg-subtle">
            {{ 'editor.template.drawer.noExample' | transloco }}
          </p>
        }
      </div>
    </ng-template>
  `,
  host: { class: 'contents' },
})
export class TemplateSearchPopover {
  private readonly categoriesApi = inject(CategoriesApiService);
  private readonly domain = inject(DomainFacade);
  private readonly language = inject(LanguageService);
  private readonly apiBaseUrl = inject(APP_CONFIG).apiBaseUrl;
  private readonly destroyRef = inject(DestroyRef);

  /** The clicked template (hydrated). The host closes over which mainType invoked the popover. */
  readonly picked = output<SystemCategory>();

  private readonly injector = inject(Injector);

  private readonly popover = viewChild.required(PickerPopover);
  /** Resolves once the overlay renders — the open-edge focus target. */
  private readonly searchInput = viewChild<ElementRef<HTMLInputElement>>('searchInput');
  /** The narrow detail view's Back button — where focus goes when the detail opens. */
  private readonly detailBack = viewChild<ElementRef<HTMLButtonElement>>('detailBack');
  private readonly options = viewChildren(AutocompleteOption);

  protected readonly listboxId = 'template-search-listbox';
  protected readonly rowClass =
    'flex w-full cursor-pointer items-center gap-2 rounded px-2 py-1.5 text-left text-sm hover:bg-surface-100 dark:hover:bg-surface-800';
  protected readonly rowActiveClass = ' bg-surface-100 dark:bg-surface-800';

  protected readonly lang = computed<string>(() => this.language.selectedLanguage());
  protected readonly searchName = signal('');
  protected readonly results = signal<readonly SystemCategory[]>([]);
  protected readonly loading = signal(false);
  /** The template list could not be read: said so in the list's place, with a retry. */
  protected readonly loadFailed = signal(false);
  protected readonly activeId = signal<string | undefined>(undefined);

  /** Too narrow for the example beside the list (Tailwind's `md`): one pane at a time. */
  protected readonly narrow = mediaQuerySignal('(max-width: 47.99rem)');
  /** The tapped template whose example the narrow panel shows in place of the list. */
  private readonly detail = signal<SystemCategory | undefined>(undefined);
  /** The detail view's template — only while narrow, so widening the window restores the list. */
  protected readonly detailOf = computed<SystemCategory | undefined>(() =>
    this.narrow() ? this.detail() : undefined,
  );

  /** The trigger that opened the popover — focus goes back here on close. */
  private anchorEl: HTMLElement | undefined;

  /** The template whose example is shown in the right pane (hover OR keyboard-active row). */
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

  private readonly search$ = new Subject<void>();
  private fetchSeq = 0;

  private readonly keyManager: ActiveDescendantKeyManager<AutocompleteOption<unknown>>;

  constructor() {
    // Debounce only free-text typing; the open-edge fetch fires immediately.
    this.search$.pipe(debounceTime(300), takeUntilDestroyed()).subscribe(() => this.fetch());

    this.keyManager = new ActiveDescendantKeyManager(this.options, this.injector).withWrap();
    this.keyManager.change.subscribe(() => {
      const active = this.keyManager.activeItem;
      this.activeId.set(active?.id);
      // Arrowing drives the example pane exactly like a hover would.
      this.hovered.set(active?.value() as SystemCategory | undefined);
      // Optional-chained: jsdom (unit tests) doesn't implement scrollIntoView.
      active?.element.nativeElement.scrollIntoView?.({ block: 'nearest' });
    });
    inject(DestroyRef).onDestroy(() => this.keyManager.destroy());

    // However it closes (pick / Esc / outside click), focus goes back to the trigger rather
    // than dropping onto <body>. The open edge is handled in `toggle()`.
    effect(() => {
      if (!this.popover().shown() && this.anchorEl?.isConnected) {
        this.anchorEl.focus();
      }
    });
  }

  /** Toggle the popover on its trigger; opening resets the search + hover and fetches. */
  toggle(anchor: HTMLElement): void {
    const popover = this.popover();
    if (popover.shown()) {
      popover.close();
      return;
    }
    this.anchorEl = anchor;
    this.searchName.set('');
    this.hovered.set(undefined);
    this.detail.set(undefined);
    this.clearActive();
    popover.open(anchor);
    // The search box only lands in the document once CDK attaches the overlay, so focus it on
    // the far side of that render — the trigger click is then the only click before typing.
    afterNextRender(() => this.searchInput()?.nativeElement.focus(), { injector: this.injector });
    this.fetch();
  }

  /** Fetch + hydrate the domain template list for the current name filter (drawer recipe). */
  protected fetch(): void {
    const seq = ++this.fetchSeq;
    this.loading.set(true);
    this.loadFailed.set(false);
    fetchHydratedTemplates(this.categoriesApi, {
      domainId: this.domain.sessionDomain()?.domainId,
      language: this.lang(),
      name: this.searchName().trim() || undefined,
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
          this.loadFailed.set(true);
        },
      });
  }

  protected onSearch(name: string): void {
    this.searchName.set(name);
    // The old highlight points at a row the next result set may not contain.
    this.clearActive();
    this.search$.next();
  }

  /**
   * Arrow keys move the highlight, Enter picks it. Esc is left alone — `PickerPopover`'s
   * document-level handler closes the popover, which restores focus to the trigger.
   */
  protected onKeydown(event: KeyboardEvent): void {
    switch (event.key) {
      case 'ArrowDown':
        this.keyManager.setNextItemActive();
        event.preventDefault();
        break;
      case 'ArrowUp':
        this.keyManager.setPreviousItemActive();
        event.preventDefault();
        break;
      case 'Enter': {
        const active = this.keyManager.activeItem;
        if (active) {
          this.pick(active.value() as SystemCategory);
          event.preventDefault();
        }
        break;
      }
    }
  }

  protected onHover(cat: SystemCategory): void {
    this.hovered.set(cat);
  }

  // Picking runs on mousedown + preventDefault so the search box never blurs mid-click
  // (options are not focusable — the combobox owns focus, activedescendant owns the highlight).
  // A narrow panel opens the row's example first; its own button then picks.
  protected onOptionMousedown(event: MouseEvent, cat: SystemCategory): void {
    event.preventDefault();
    if (this.narrow()) {
      this.openDetail(cat);
    } else {
      this.pick(cat);
    }
  }

  /** Show `cat`'s example in place of the list; the search box is hidden, so focus moves to Back. */
  private openDetail(cat: SystemCategory): void {
    this.hovered.set(cat);
    this.detail.set(cat);
    afterNextRender(() => this.detailBack()?.nativeElement.focus(), { injector: this.injector });
  }

  /** Back from the example to the list, with the caret in the search box again. */
  protected closeDetail(): void {
    this.detail.set(undefined);
    this.hovered.set(undefined);
    this.clearActive();
    afterNextRender(() => this.searchInput()?.nativeElement.focus(), { injector: this.injector });
  }

  protected pick(cat: SystemCategory): void {
    this.popover().close();
    this.picked.emit(cat);
  }

  private clearActive(): void {
    this.keyManager.setActiveItem(-1);
    this.activeId.set(undefined);
  }

  protected nameOf(cat: SystemCategory): string {
    return categoryName(cat, this.lang());
  }

  protected readonly isPerPosition = isPerPosition;
}
