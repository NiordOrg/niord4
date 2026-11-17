import {
  Component,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
  untracked,
} from '@angular/core';
import { email, form, FormField, required } from '@angular/forms/signals';
import { TranslocoPipe } from '@jsverse/transloco';

import { LanguageService } from '../../../../../core/main-shell/services/language.service';
import { NotificationService } from '../../../../../core/notifications/notification.service';
import { Drawer } from '../../../../../shared/components/drawer/drawer';
import {
  AppTab,
  AppTabList,
  AppTabPanel,
  AppTabPanels,
  AppTabs,
} from '../../../../../shared/components/tabs/tabs';
import { Contact, ContactsApiService } from '../../../services/api/contacts-api.service';
import { MailingListsApiService } from '../../../services/api/mailing-lists-api.service';
import { MailingListMembershipPanel, MembershipToggle } from './mailing-list-membership-panel';
import { UsersFacade } from './users.facade';

export interface ContactEditorModel {
  readonly mode: 'add' | 'edit';
  readonly contact: Contact;
}

interface ContactFormModel {
  email: string;
  name: string;
  language: string;
}

/**
 * Create/edit form for a contact — its OWN drawer (never the user editor's),
 * with inner tabs (Contact details / Mailing lists; the membership tab exists
 * only in edit mode). The mailing-list membership is keyed by the contact's
 * EMAIL (never the numeric id).
 */
@Component({
  selector: 'app-contact-editor',
  imports: [
    Drawer,
    FormField,
    AppTabs,
    AppTabList,
    AppTab,
    AppTabPanels,
    AppTabPanel,
    MailingListMembershipPanel,
    TranslocoPipe,
  ],
  templateUrl: './contact-editor.html',
  host: { class: 'contents' },
})
export class ContactEditor {
  private readonly languageSettings = inject(LanguageService);
  private readonly api = inject(ContactsApiService);
  private readonly mailingListsApi = inject(MailingListsApiService);
  private readonly notifications = inject(NotificationService);
  protected readonly facade = inject(UsersFacade);

  /** `undefined` = closed; the tab owns visibility. */
  readonly model = input.required<ContactEditorModel | undefined>();
  readonly saved = output<void>();
  readonly closed = output<void>();

  protected readonly languages = this.languageSettings.modelLanguages;

  protected readonly saving = signal(false);
  protected readonly activeTab = signal<'details' | 'lists'>('details');
  /** Mailing-list membership ids — `undefined` while the lookup is in flight. */
  protected readonly memberIds = signal<ReadonlySet<string> | undefined>(undefined);

  private readonly formModel = signal<ContactFormModel>({ email: '', name: '', language: '' });
  protected readonly form = form(this.formModel, (path) => {
    required(path.email);
    email(path.email);
  });

  /**
   * Net change against the opened values. Signal Forms' `dirty` tracks user
   * interaction and never clears on revert, so a serialized baseline comparison
   * gates Save instead.
   */
  private readonly baselineJson = signal('');
  protected readonly changed = computed(
    () => JSON.stringify(this.formModel()) !== this.baselineJson(),
  );

  protected readonly saveDisabled = computed(
    () => !this.changed() || this.form().invalid() || this.saving(),
  );

  protected readonly headerKey = computed(() =>
    this.model()?.mode === 'edit'
      ? 'admin.users.contacts.editor.editTitle'
      : 'admin.users.contacts.editor.addTitle',
  );

  protected readonly isEdit = computed(() => this.model()?.mode === 'edit');

  constructor() {
    effect(() => {
      const model = this.model();
      if (model) {
        untracked(() => this.rebuild(model));
      }
    });
  }

  private rebuild(model: ContactEditorModel): void {
    const contact = model.contact;
    const next: ContactFormModel = {
      email: contact.email ?? '',
      name: contact.name ?? '',
      language: contact.language ?? '',
    };
    this.formModel.set(next);
    this.baselineJson.set(JSON.stringify(next));
    this.form().reset();
    this.activeTab.set('details');
    this.memberIds.set(undefined);
    this.saving.set(false);
    if (model.mode === 'edit') {
      this.loadMemberships(contact.email);
    }
  }

  private loadMemberships(email: string): void {
    this.mailingListsApi.getForContact(email).subscribe({
      next: (lists) => this.memberIds.set(new Set(lists.map((l) => l.mailingListId))),
      error: () => {
        this.memberIds.set(new Set());
        this.notifications.error('admin.users.toast.membershipError');
      },
    });
  }

  /** Membership toggle — toast-QUIET on success; the flags re-resolve. */
  protected onMembershipToggle(toggle: MembershipToggle): void {
    const model = this.model();
    if (!model) return;
    this.mailingListsApi
      .updateContactStatus(toggle.list.mailingListId, model.contact, toggle.recipient)
      .subscribe({
        next: () => this.loadMemberships(model.contact.email),
        error: () => this.notifications.error('admin.users.toast.membershipError'),
      });
  }

  protected save(): void {
    const model = this.model();
    if (!model || this.saveDisabled()) {
      return;
    }
    const value = this.formModel();
    const contact: Contact = {
      id: model.contact.id,
      email: value.email,
      name: value.name || undefined,
      // Blank language is OMITTED from the payload.
      language: value.language || undefined,
    };
    const request = model.mode === 'add' ? this.api.create(contact) : this.api.update(contact);
    this.saving.set(true);
    request.subscribe({
      next: () => {
        this.notifications.success(
          model.mode === 'add'
            ? 'admin.users.contacts.toast.added'
            : 'admin.users.contacts.toast.updated',
        );
        this.saved.emit();
      },
      error: () => {
        this.saving.set(false);
        this.notifications.error('admin.users.contacts.toast.saveError');
      },
    });
  }
}
