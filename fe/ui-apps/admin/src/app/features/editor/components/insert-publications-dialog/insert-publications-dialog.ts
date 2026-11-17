import { Component, computed, DestroyRef, inject, input, model, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { TranslocoPipe } from '@jsverse/transloco';

import { DomainFacade } from '../../../../core/domain/facades/domain.facade';
import { LanguageService } from '../../../../core/main-shell/services/language.service';
import { NotificationService } from '../../../../core/notifications/notification.service';
import { Dialog } from '../../../../shared/components/dialog/dialog';
import { Icon } from '../../../../shared/icons/icon';
import { PublicationsField } from '../../../messages/components/publications-field/publications-field';
import type { PublicationRef } from '../../../messages/models/messages-filter-params.model';
import { PUBLICATION_CITATION_STATUSES } from '../../../messages/models/messages-filter-params.model';
import { MessagesApiService } from '../../../messages/services/api/messages-api.service';
import { PublicationsApiService } from '../../../messages/services/api/publications-api.service';
import { EditorFacade } from '../../services/editor-facade';

/** Which message-publication channel the invoking editor belongs to. */
export type PublicationKind = 'EXTERNAL' | 'INTERNAL';

const PARAMS_PLACEHOLDER = '${parameters}';

/**
 * Insert / update a message publication. Opened from a Publication editor's toolbar: EDIT mode when
 * the cursor sits inside a `publication=<id>` span (prefilled by id), else INSERT.
 *
 * The picked publication's own `messagePublicationFormat` decides whether a comma-separated
 * `parameters` field is shown (the template carries `${parameters}`);
 * a `link` field is shown for a `LINK`-type publication. On selection the dialog additionally runs
 * `extract-message-publication` to prefill the parameters/link already embedded in the message (a
 * `null` answer means the publication isn't in the message yet — nothing to prefill).
 *
 * Both actions POST `update-message-publications` (omitting `lang` updates every language); the
 * server-rendered per-language `publication` / `internalPublication` HTML is patched into the working
 * message through the facade. The dialog inserts nothing at the cursor. A failed update toasts and
 * leaves the dialog open (mutation-failure policy).
 */
@Component({
  selector: 'app-insert-publications-dialog',
  imports: [TranslocoPipe, Dialog, Icon, PublicationsField],
  template: `
    <app-dialog
      [visible]="visible()"
      (visibleChange)="visible.set($event)"
      (opened)="onOpened()"
      [modal]="true"
      [header]="'editor.dialog.publications.header' | transloco"
      [style]="{ width: '36rem' }"
    >
      <div class="flex flex-col gap-4">
        <app-publications-field
          [value]="pickedArray()"
          (valueChange)="onPublicationChange($event)"
          [multiple]="false"
          [messagePublication]="kind()"
          [type]="null"
          [statuses]="pickerStatuses"
          [domain]="sessionDomainId()"
          [nameOnly]="true"
          [placeholder]="'editor.dialog.publications.select'"
          [ariaLabel]="'editor.dialog.publications.select'"
        />

        @if (hasParams()) {
          <input
            type="text"
            class="w-full text-input"
            [value]="parameters()"
            (input)="parameters.set($any($event.target).value)"
            [placeholder]="'editor.dialog.publications.parameters' | transloco"
            [attr.aria-label]="'editor.dialog.publications.parameters' | transloco"
            autocomplete="off"
          />
        }

        @if (showLink()) {
          <div class="flex items-center gap-2">
            <span class="inline-flex shrink-0 items-center gap-1.5 text-sm text-fg-subtle">
              <app-icon name="lucideLink" />
              {{ 'editor.dialog.publications.link' | transloco }}
            </span>
            <input
              type="text"
              class="w-full text-input"
              [value]="link()"
              (input)="link.set($any($event.target).value)"
              [attr.aria-label]="'editor.dialog.publications.link' | transloco"
              autocomplete="off"
            />
          </div>
        }
      </div>

      <ng-template #footer>
        <button type="button" class="btn-tertiary" (click)="visible.set(false)">
          {{ 'term.cancel' | transloco }}
        </button>
        <button type="button" class="btn-primary" [disabled]="!canUpdate()" (click)="update(true)">
          {{ 'editor.dialog.publications.updateAll' | transloco }}
        </button>
        <button type="button" class="btn-primary" [disabled]="!canUpdate()" (click)="update(false)">
          {{ 'editor.dialog.publications.updateLangOnly' | transloco: { language: langLabel() } }}
        </button>
      </ng-template>
    </app-dialog>
  `,
  host: { class: 'contents' },
})
export class InsertPublicationsDialog {
  private readonly api = inject(MessagesApiService);
  private readonly publicationsApi = inject(PublicationsApiService);
  private readonly facade = inject(EditorFacade);
  private readonly notify = inject(NotificationService);
  private readonly language = inject(LanguageService);
  private readonly domain = inject(DomainFacade);
  private readonly destroyRef = inject(DestroyRef);

  /**
   * The desk the citation is being written from.
   *
   * A publication reaches this picker where it is owned and wherever its owner
   * has shared it, so the domain being worked in is the one that decides — the
   * journal number every desk cites stays on offer, and another desk's weekly
   * notices do not. Resolution by id below is deliberately unscoped: a citation
   * already in the message has to render whatever it names.
   */
  protected readonly sessionDomainId = computed(
    () => this.domain.sessionDomain()?.domainId ?? null,
  );

  readonly visible = model(false);
  /** The channel the invoking Publication editor belongs to (contextual — the backend derives the
   * actual channel from the publication, so it is not sent on the update). */
  readonly kind = input<PublicationKind>('EXTERNAL');
  /** The invoking editor's model language (the extract/update `lang` + the per-language action). */
  readonly lang = input<string>('');
  /** Set when the cursor sits inside a publication span ⇒ EDIT mode (prefill by id). */
  readonly publicationId = input<string | undefined>(undefined);

  protected readonly picked = signal<PublicationRef | undefined>(undefined);
  protected readonly parameters = signal('');
  protected readonly link = signal('');

  /**
   * The dialog picker offers only released publications.
   *
   * A citation names something a reader is meant to go and look up, so an issue
   * still being filled is not citable however useful it is to an editor filtering
   * their own work by it — which is why the message filter's default is wider than
   * this one.
   */
  protected readonly pickerStatuses: readonly string[] = PUBLICATION_CITATION_STATUSES;

  /** Single-select over the multi-select field: at most one chip. */
  protected readonly pickedArray = computed<readonly PublicationRef[]>(() => {
    const pub = this.picked();
    return pub ? [pub] : [];
  });

  /** Whether any language's message-publication template carries `${parameters}`. */
  protected readonly hasParams = computed<boolean>(() =>
    (this.picked()?.descs ?? []).some((desc) =>
      (desc.messagePublicationFormat ?? '').includes(PARAMS_PLACEHOLDER),
    ),
  );

  /**
   * Whether this message may carry its own link for the picked publication.
   *
   * Three conditions, all of them load-bearing. The publication must be a `LINK`
   * publication, because that is the only kind whose citation renders as an anchor
   * at all. It must not be produced separately per language, because a
   * language-specific publication already has one link per language and a single
   * free-text override could only be wrong in all but one of them. And it must carry
   * no link of its own — an override exists to supply a destination the publication
   * does not have, not to let one message quietly point somewhere else.
   *
   * `languageSpecific` is only known for a publication issue; a legacy-half row does
   * not carry the flag, and an override is not offered on a rule that cannot be
   * evaluated. The link test reads every language's desc rather than the active one
   * because a publication that is not language-specific shares one link across all of
   * them, so a link in any language is the publication's own link.
   */
  protected readonly showLink = computed<boolean>(() => {
    const pub = this.picked();
    if (!pub || pub.type !== 'LINK' || pub.languageSpecific !== false) {
      return false;
    }
    return (pub.descs ?? []).every((desc) => !desc.link);
  });

  protected readonly canUpdate = computed<boolean>(() => this.picked() !== undefined);

  protected readonly langLabel = computed<string>(() => {
    const code = this.lang();
    return (
      this.language.modelLanguages.find((option) => option.value === code)?.label ??
      code.toUpperCase()
    );
  });

  protected onOpened(): void {
    this.parameters.set('');
    this.link.set('');
    this.picked.set(undefined);

    const id = this.publicationId();
    if (!id) {
      return;
    }
    // EDIT mode: resolve the publication by id (re-resolved every open, so reopening on the same span
    // never shows a stale/empty picker), then prefill from the message.
    this.publicationsApi
      .getByIds([id])
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (pubs) => {
          const pub = pubs[0];
          if (pub) {
            this.picked.set(pub);
            this.prefill(pub.publicationId);
          }
        },
      });
  }

  protected onPublicationChange(items: readonly PublicationRef[]): void {
    // Single-select field: the array holds zero or one item.
    const next = items[0];
    this.picked.set(next);
    // Reset parameters/link on every selection change, then re-prefill from the message.
    this.parameters.set('');
    this.link.set('');
    if (next) {
      this.prefill(next.publicationId);
    }
  }

  private prefill(publicationId: string): void {
    const message = this.facade.message();
    if (!message) {
      return;
    }
    this.api
      .extractMessagePublication(message, publicationId, this.lang())
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        // A null answer means the publication is not embedded in the message — nothing to prefill.
        // Deliberately silent: no toast, no mode change.
        next: (msgPub) => {
          if (msgPub) {
            this.parameters.set(msgPub.parameters ?? '');
            this.link.set(msgPub.link ?? '');
          }
        },
      });
  }

  protected update(allLanguages: boolean): void {
    const pub = this.picked();
    const message = this.facade.message();
    if (!pub || !message) {
      return;
    }
    this.api
      .updateMessagePublications(message, {
        publicationId: pub.publicationId,
        parameters: this.parameters() || undefined,
        link: this.link() || undefined,
        lang: allLanguages ? undefined : this.lang(),
      })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (updated) => {
          this.facade.applyPublicationsUpdate(updated);
          this.visible.set(false);
        },
        error: () => this.notify.error('editor.dialog.publications.updateError'),
      });
  }
}
