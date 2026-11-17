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
import { TranslocoPipe } from '@jsverse/transloco';

import { NotificationService } from '../../../../../core/notifications/notification.service';
import { Drawer } from '../../../../../shared/components/drawer/drawer';
import {
  AppTab,
  AppTabList,
  AppTabPanel,
  AppTabPanels,
  AppTabs,
} from '../../../../../shared/components/tabs/tabs';
import type { Contact } from '../../../services/api/contacts-api.service';
import type { User } from '../../../services/api/users-api.service';
import { MailingListsApiService } from '../../../services/api/mailing-lists-api.service';
import {
  RecipientTransfer,
  type RecipientSelection,
  type TransferItem,
} from '../../../components/recipient-transfer/recipient-transfer';
import type { MailingList } from './mailing-list.model';

const userName = (user: User): string => [user.firstName, user.lastName].filter(Boolean).join(' ');
const userItem = (user: User): TransferItem => ({
  key: user.username,
  email: user.email,
  name: userName(user),
});
const contactItem = (contact: Contact): TransferItem => ({
  key: contact.email,
  email: contact.email,
  name: contact.name ?? '',
});

const selectionKeyJson = (selection: RecipientSelection): string =>
  JSON.stringify([...selection.selected.map((item) => item.key)].sort());

/**
 * Manage-recipients drawer: Users and Contacts tabs, each a dual-list transfer with its OWN Save
 * (bulk PUT of the selected array). Saving toasts and KEEPS THE DRAWER OPEN so the user can work
 * both tabs in one visit; the drawer close is dirty-guarded across both tabs. Available to plain
 * admins (not sysadmin-gated). Partitions are (re)fetched when the drawer opens.
 */
@Component({
  selector: 'app-mailing-list-recipients-drawer',
  imports: [
    Drawer,
    TranslocoPipe,
    RecipientTransfer,
    AppTabs,
    AppTabList,
    AppTab,
    AppTabPanels,
    AppTabPanel,
  ],
  templateUrl: './mailing-list-recipients-drawer.html',
  host: { class: 'contents' },
})
export class MailingListRecipientsDrawer {
  private readonly api = inject(MailingListsApiService);
  private readonly notifications = inject(NotificationService);

  /** `undefined` = closed; the page owns visibility. */
  readonly mailingList = input.required<MailingList | undefined>();
  /** A per-tab save landed — the page refreshes the list so the recipient count updates. */
  readonly saved = output<void>();
  readonly closed = output<void>();

  protected readonly activeTab = signal<'users' | 'contacts'>('users');

  protected readonly usersSelection = signal<RecipientSelection>({ selected: [], available: [] });
  protected readonly contactsSelection = signal<RecipientSelection>({
    selected: [],
    available: [],
  });
  private usersBaseline = signal('');
  private contactsBaseline = signal('');
  private usersByKey = new Map<string, User>();
  private contactsByKey = new Map<string, Contact>();

  protected readonly savingUsers = signal(false);
  protected readonly savingContacts = signal(false);

  protected readonly usersDirty = computed(
    () => selectionKeyJson(this.usersSelection()) !== this.usersBaseline(),
  );
  protected readonly contactsDirty = computed(
    () => selectionKeyJson(this.contactsSelection()) !== this.contactsBaseline(),
  );
  /** Either tab's unsaved selection guards the drawer close. */
  protected readonly dirty = computed(() => this.usersDirty() || this.contactsDirty());

  constructor() {
    effect(() => {
      const list = this.mailingList();
      if (list) {
        untracked(() => this.loadPartitions(list.mailingListId));
      }
    });
  }

  private loadPartitions(mailingListId: string): void {
    this.activeTab.set('users');
    this.api.getRecipientUsers(mailingListId).subscribe({
      next: (partition) => {
        this.usersByKey = new Map(
          [...partition.selectedRecipients, ...partition.availableRecipients].map((user) => [
            user.username,
            user,
          ]),
        );
        const selection: RecipientSelection = {
          selected: partition.selectedRecipients.map(userItem),
          available: partition.availableRecipients.map(userItem),
        };
        this.usersSelection.set(selection);
        this.usersBaseline.set(selectionKeyJson(selection));
      },
      error: () => this.notifications.error('admin.mailingLists.recipients.loadError'),
    });
    this.api.getRecipientContacts(mailingListId).subscribe({
      next: (partition) => {
        this.contactsByKey = new Map(
          [...partition.selectedRecipients, ...partition.availableRecipients].map((contact) => [
            contact.email,
            contact,
          ]),
        );
        const selection: RecipientSelection = {
          selected: partition.selectedRecipients.map(contactItem),
          available: partition.availableRecipients.map(contactItem),
        };
        this.contactsSelection.set(selection);
        this.contactsBaseline.set(selectionKeyJson(selection));
      },
      error: () => this.notifications.error('admin.mailingLists.recipients.loadError'),
    });
  }

  protected saveUsers(): void {
    const list = this.mailingList();
    if (!list || this.savingUsers()) return;
    const users = this.usersSelection()
      .selected.map((item) => this.usersByKey.get(item.key))
      .filter((user): user is User => user !== undefined);
    this.savingUsers.set(true);
    this.api.updateRecipientUsers(list.mailingListId, users).subscribe({
      next: () => {
        this.savingUsers.set(false);
        this.usersBaseline.set(selectionKeyJson(this.usersSelection()));
        this.notifications.success('admin.mailingLists.recipients.savedUsers');
        this.saved.emit();
      },
      error: () => {
        this.savingUsers.set(false);
        this.notifications.error('admin.mailingLists.recipients.saveError');
      },
    });
  }

  protected saveContacts(): void {
    const list = this.mailingList();
    if (!list || this.savingContacts()) return;
    const contacts = this.contactsSelection()
      .selected.map((item) => this.contactsByKey.get(item.key))
      .filter((contact): contact is Contact => contact !== undefined);
    this.savingContacts.set(true);
    this.api.updateRecipientContacts(list.mailingListId, contacts).subscribe({
      next: () => {
        this.savingContacts.set(false);
        this.contactsBaseline.set(selectionKeyJson(this.contactsSelection()));
        this.notifications.success('admin.mailingLists.recipients.savedContacts');
        this.saved.emit();
      },
      error: () => {
        this.savingContacts.set(false);
        this.notifications.error('admin.mailingLists.recipients.saveError');
      },
    });
  }
}
