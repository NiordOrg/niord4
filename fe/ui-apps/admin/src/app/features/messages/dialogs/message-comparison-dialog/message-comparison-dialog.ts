import {
  afterNextRender,
  Component,
  computed,
  effect,
  ElementRef,
  inject,
  Injector,
  signal,
  viewChild,
} from '@angular/core';
import { httpResource } from '@angular/common/http';
import { TranslocoPipe } from '@jsverse/transloco';

import { htmlDiff } from '../../../../shared/utils/html-diff.util';

import { APP_CONFIG } from '../../../../core/config/app-config.token';
import { LanguageService } from '../../../../core/main-shell/services/language.service';
import { NotificationService } from '../../../../core/notifications/notification.service';
import { Dialog } from '../../../../shared/components/dialog/dialog';
import { HelpAnchor } from '../../../../core/main-shell/help/help-anchor';
import { JsonDiffPane } from '../../../../shared/components/json-diff-pane/json-diff-pane';
import {
  AppTab,
  AppTabList,
  AppTabPanel,
  AppTabPanels,
  AppTabs,
} from '../../../../shared/components/tabs/tabs';
import { MessageDetailsRenderer } from '../../components/message-details-renderer/message-details-renderer';
import { MessageIdField } from '../../components/message-id-field/message-id-field';
import type { Message } from '../../models/message.model';
import { MessageComparisonDialogService } from './message-comparison-dialog.service';
import { LanguageFlag } from '../../../../shared/components/language-flag/language-flag';

/**
 * Two-message comparison surface: two id pickers (deleted messages selectable) feed
 * two reactive per-side loads, rendered Side-by-side (through the details renderer at a
 * chosen content language) or as a structured Data diff. Opened from the editor action
 * menu (seeds side 2 with the open message) and the messages-list action menu (seeds
 * both from a 2-selection).
 *
 * Each side is a `httpResource` keyed on its id signal (owned by the dialog service):
 * an undefined id is idle, a load error toasts once and leaves that side empty, and a
 * `hasValue()` guard keeps `value()` from throwing in the error/loading state.
 */
@Component({
  selector: 'app-message-comparison-dialog',
  templateUrl: './message-comparison-dialog.html',
  imports: [
    LanguageFlag,
    Dialog,
    HelpAnchor,
    JsonDiffPane,
    AppTabs,
    AppTabList,
    AppTab,
    AppTabPanels,
    AppTabPanel,
    MessageDetailsRenderer,
    MessageIdField,
    TranslocoPipe,
  ],
  host: { class: 'contents' },
})
export class MessageComparisonDialog {
  protected readonly dialog = inject(MessageComparisonDialogService);
  private readonly apiBaseUrl = inject(APP_CONFIG).apiBaseUrl;
  private readonly language = inject(LanguageService);
  private readonly notifications = inject(NotificationService);
  private readonly injector = inject(Injector);

  // The two side-by-side rendered panes (always mounted — the tabs render eagerly). Their innerHTML
  // is the source material the "Changes" word-diff runs on.
  private readonly renderer1 = viewChild('renderer1', { read: ElementRef });
  private readonly renderer2 = viewChild('renderer2', { read: ElementRef });

  /** HTML word-diff of the two rendered messages (`<ins>`/`<del>` marked), for the Changes tab. */
  protected readonly diffHtml = signal('');

  /** Configured content languages, one pill each — scales to N languages. */
  protected readonly languages = this.language.modelLanguages;
  /** Locally-selected content language, defaulting to the current app language. */
  protected readonly previewLang = signal(this.language.selectedLanguage());
  protected readonly activeTab = signal<string>('sideBySide');
  /** Dialog width toggle: default large, toggle to extra-wide. */
  protected readonly showXL = signal(false);

  protected readonly dialogStyle = computed<Record<string, string>>(() =>
    this.showXL() ? { width: '75rem', maxWidth: '95vw' } : { width: '56rem', maxWidth: '95vw' },
  );

  /** Fresh per-open UI state: language/tab/size reset each time the dialog opens. */
  protected onOpened(): void {
    this.previewLang.set(this.language.selectedLanguage());
    this.activeTab.set('sideBySide');
    this.showXL.set(false);
  }

  private readonly message1Resource = httpResource<Message>(() =>
    this.requestFor(this.dialog.messageId1()),
  );
  private readonly message2Resource = httpResource<Message>(() =>
    this.requestFor(this.dialog.messageId2()),
  );

  /** The loaded message for each side, or undefined while idle/loading/errored. */
  protected readonly message1 = computed<Message | undefined>(() =>
    this.message1Resource.hasValue() ? this.message1Resource.value() : undefined,
  );
  protected readonly message2 = computed<Message | undefined>(() =>
    this.message2Resource.hasValue() ? this.message2Resource.value() : undefined,
  );

  /**
   * Raw JSON snapshots for the Data diff (both loaded = change diff; one loaded =
   * self-compare per the pane's contract). The JSON is deliberately left unsorted —
   * key order is only cosmetic in the diff.
   */
  protected readonly snapshots = computed<string[]>(() => {
    const snaps: string[] = [];
    const first = this.message1();
    const second = this.message2();
    if (first) snaps.push(JSON.stringify(first));
    if (second) snaps.push(JSON.stringify(second));
    return snaps;
  });

  constructor() {
    // Each side toasts once per distinct load error. Keyed on the id signals, a reopen
    // with the same failing id does not refetch, so error() does not change → no re-toast.
    effect(() => {
      if (this.message1Resource.error() !== undefined) {
        this.notifications.error('messages.compare.loadError');
      }
    });
    effect(() => {
      if (this.message2Resource.error() !== undefined) {
        this.notifications.error('messages.compare.loadError');
      }
    });

    // Recompute the word-diff whenever the Changes tab is active and either message (or the chosen
    // language) changes. `afterNextRender` defers the innerHTML read until the two panes have
    // re-rendered for the current signals before the word-diff runs.
    effect(() => {
      const onChangesTab = this.activeTab() === 'changes';
      const bothLoaded = this.message1() !== undefined && this.message2() !== undefined;
      this.previewLang();
      if (!onChangesTab || !bothLoaded) {
        this.diffHtml.set('');
        return;
      }
      afterNextRender(
        () => {
          const el1 = this.renderer1()?.nativeElement as HTMLElement | undefined;
          const el2 = this.renderer2()?.nativeElement as HTMLElement | undefined;
          this.diffHtml.set(htmlDiff(el1?.innerHTML ?? '', el2?.innerHTML ?? ''));
        },
        { injector: this.injector },
      );
    });
  }

  protected selectLanguage(lang: string): void {
    this.previewLang.set(lang);
  }

  protected toggleXL(): void {
    this.showXL.update((value) => !value);
  }

  private requestFor(id: string | null): { url: string } | undefined {
    return id
      ? { url: `${this.apiBaseUrl}/messages/message/${encodeURIComponent(id)}` }
      : undefined;
  }
}
