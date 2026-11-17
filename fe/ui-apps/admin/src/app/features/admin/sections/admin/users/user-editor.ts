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
import { disabled, email, form, FormField, required } from '@angular/forms/signals';
import { TranslocoPipe } from '@jsverse/transloco';

import { LanguageService } from '../../../../../core/main-shell/services/language.service';
import { NotificationService } from '../../../../../core/notifications/notification.service';
import { problemDetail } from '../../../../../shared/api/problem-detail';
import { Card } from '../../../../../shared/components/card/card';
import { Drawer } from '../../../../../shared/components/drawer/drawer';
import { EntityTree } from '../../../../../shared/components/entity-tree/entity-tree';
import { Icon } from '../../../../../shared/icons/icon';
import {
  AppTab,
  AppTabList,
  AppTabPanel,
  AppTabPanels,
  AppTabs,
} from '../../../../../shared/components/tabs/tabs';
import type { HierarchicalEntity } from '../../../../../shared/components/entity-tree/hierarchical-entity.model';
import {
  MailingList,
  MailingListsApiService,
} from '../../../services/api/mailing-lists-api.service';
import { Group, User, UsersApiService } from '../../../services/api/users-api.service';
import { MailingListMembershipPanel, MembershipToggle } from './mailing-list-membership-panel';

export interface UserEditorModel {
  readonly mode: 'add' | 'edit';
  readonly user: User;
}

interface UserFormModel {
  username: string;
  email: string;
  firstName: string;
  lastName: string;
  language: string;
  keycloakPassword: string;
  requiredActions: {
    updateProfile: boolean;
    updatePassword: boolean;
    verifyEmail: boolean;
  };
}

/** The three Keycloak required-actions, flattened to `keycloakActions` on ADD only. */
const REQUIRED_ACTIONS = [
  { control: 'updateProfile', wire: 'UPDATE_PROFILE' },
  { control: 'updatePassword', wire: 'UPDATE_PASSWORD' },
  { control: 'verifyEmail', wire: 'VERIFY_EMAIL' },
] as const;

/**
 * Removes the groups the user already belongs to from the available-groups tree,
 * so only joinable groups are offered. A joined PARENT is dropped but
 * its still-unjoined children are surfaced in its place, so nested groups stay
 * joinable.
 */
function pruneJoinedGroups(groups: readonly Group[], joined: ReadonlySet<string>): Group[] {
  const result: Group[] = [];
  for (const group of groups) {
    const children = group.children
      ? pruneJoinedGroups(group.children as readonly Group[], joined)
      : [];
    if (joined.has(String(group.id))) {
      result.push(...children);
    } else {
      result.push({ ...group, children });
    }
  }
  return result;
}

/**
 * Create/edit form for a Keycloak user — a WIDE drawer with inner tabs
 * (User details / Groups / Mailing lists; the latter two exist only in edit
 * mode). `username` is required and immutable on edit — it is the mailing-list
 * membership key, so renames are impossible here (Keycloak's console owns
 * that). The password is write-only (blank = unchanged) and the three
 * required-action checkboxes flatten into `keycloakActions` on ADD only
 * (they cannot be re-applied via this form on edit).
 */
@Component({
  selector: 'app-user-editor',
  imports: [
    Card,
    Drawer,
    FormField,
    Icon,
    AppTabs,
    AppTabList,
    AppTab,
    AppTabPanels,
    AppTabPanel,
    EntityTree,
    MailingListMembershipPanel,
    TranslocoPipe,
  ],
  templateUrl: './user-editor.html',
  host: { class: 'contents' },
})
export class UserEditor {
  private readonly languageSettings = inject(LanguageService);
  private readonly api = inject(UsersApiService);
  private readonly mailingListsApi = inject(MailingListsApiService);
  private readonly notifications = inject(NotificationService);

  /** `undefined` = closed; the page owns visibility. */
  readonly model = input.required<UserEditorModel | undefined>();
  /** The read-only Keycloak group tree (page-loaded once, domain-scoped server-side). */
  readonly groups = input.required<readonly Group[]>();
  /** The all-mailing-lists catalog (page-loaded, lang-resolved). */
  readonly mailingLists = input.required<readonly MailingList[]>();

  readonly saved = output<void>();
  readonly closed = output<void>();

  protected readonly languages = this.languageSettings.modelLanguages;

  protected readonly saving = signal(false);
  protected readonly activeTab = signal<'details' | 'groups' | 'lists'>('details');

  /** The user's current group memberships — `undefined` while loading (edit mode). */
  protected readonly userGroups = signal<readonly Group[] | undefined>(undefined);
  /** The groups available to JOIN — the full tree minus the ones already joined. */
  protected readonly availableGroups = computed<readonly Group[]>(() => {
    const joined = new Set((this.userGroups() ?? []).map((group) => String(group.id)));
    return pruneJoinedGroups(this.groups(), joined);
  });
  /** The selected AVAILABLE tree node — only ACTIVE groups are selectable (Join target). */
  protected readonly selectedGroup = signal<Group | undefined>(undefined);
  /** Feeds the tree's `selectedIds` so the clicked group shows the selection rail. */
  protected readonly selectedGroupIds = computed<readonly (number | string)[]>(() => {
    const group = this.selectedGroup();
    return group ? [group.id] : [];
  });
  /** Mailing-list membership ids — `undefined` while the lookup is in flight. */
  protected readonly memberIds = signal<ReadonlySet<string> | undefined>(undefined);

  private readonly formModel = signal<UserFormModel>({
    username: '',
    email: '',
    firstName: '',
    lastName: '',
    language: '',
    keycloakPassword: '',
    requiredActions: { updateProfile: false, updatePassword: false, verifyEmail: false },
  });
  protected readonly form = form(this.formModel, (path) => {
    required(path.username);
    // The membership key — immutable once the user exists.
    disabled(path.username, { when: () => this.model()?.mode === 'edit' });
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
    this.model()?.mode === 'edit' ? 'admin.users.editor.editTitle' : 'admin.users.editor.addTitle',
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

  private rebuild(model: UserEditorModel): void {
    const user = model.user;
    const next: UserFormModel = {
      username: user.username ?? '',
      email: user.email ?? '',
      firstName: user.firstName ?? '',
      lastName: user.lastName ?? '',
      language: user.language ?? '',
      keycloakPassword: '',
      requiredActions: { updateProfile: false, updatePassword: false, verifyEmail: false },
    };
    this.formModel.set(next);
    this.baselineJson.set(JSON.stringify(next));
    this.form().reset();
    this.activeTab.set('details');
    this.selectedGroup.set(undefined);
    this.userGroups.set(undefined);
    this.memberIds.set(undefined);
    this.saving.set(false);
    if (model.mode === 'edit') {
      this.loadUserGroups(user.keycloakId);
      this.loadMemberships(user.username);
    }
  }

  private loadUserGroups(keycloakId: string): void {
    this.api.getUserGroups(keycloakId).subscribe({
      next: (groups) => this.userGroups.set(groups),
      error: () => {
        this.userGroups.set([]);
        this.notifications.error('admin.users.toast.groupsError');
      },
    });
  }

  private loadMemberships(username: string): void {
    this.mailingListsApi.getForUser(username).subscribe({
      next: (lists) => this.memberIds.set(new Set(lists.map((l) => l.mailingListId))),
      error: () => {
        this.memberIds.set(new Set());
        this.notifications.error('admin.users.toast.membershipError');
      },
    });
  }

  /** Only an ACTIVE tree node becomes the Join target. */
  protected onGroupSelected(entity: HierarchicalEntity): void {
    const group = entity as Group;
    this.selectedGroup.set(group.active ? group : undefined);
  }

  protected joinSelectedGroup(): void {
    const model = this.model();
    const group = this.selectedGroup();
    if (!model || !group) return;
    this.api.joinGroup(model.user.keycloakId, String(group.id)).subscribe({
      next: () => {
        this.notifications.success('admin.users.toast.joinedGroup');
        this.selectedGroup.set(undefined);
        this.loadUserGroups(model.user.keycloakId);
      },
      error: (error: unknown) => this.groupChangeFailed(error),
    });
  }

  protected leaveGroup(group: Group): void {
    const model = this.model();
    if (!model) return;
    this.api.leaveGroup(model.user.keycloakId, String(group.id)).subscribe({
      next: () => {
        this.notifications.success('admin.users.toast.leftGroup');
        this.loadUserGroups(model.user.keycloakId);
      },
      error: (error: unknown) => this.groupChangeFailed(error),
    });
  }

  /**
   * A join or leave Keycloak did not carry out: the backend answers 403 (Keycloak refused), 404 (no
   * such user or group) or 502 (Keycloak failed or could not be reached) with a sentence saying
   * which, shown when it is there.
   */
  private groupChangeFailed(error: unknown): void {
    const detail = problemDetail(error);
    if (detail === undefined) {
      this.notifications.error('admin.users.toast.groupError');
    } else {
      this.notifications.error('admin.users.toast.groupRefused', { detail });
    }
  }

  /** Membership toggle — toast-QUIET on success; the flags re-resolve. */
  protected onMembershipToggle(toggle: MembershipToggle): void {
    const model = this.model();
    if (!model) return;
    this.mailingListsApi
      .updateUserStatus(toggle.list.mailingListId, model.user, toggle.recipient)
      .subscribe({
        next: () => this.loadMemberships(model.user.username),
        error: () => this.notifications.error('admin.users.toast.membershipError'),
      });
  }

  protected save(): void {
    const model = this.model();
    if (!model || this.saveDisabled()) {
      return;
    }
    const value = this.formModel();
    const user: User = {
      keycloakId: model.user.keycloakId,
      username: value.username,
      email: value.email,
      firstName: value.firstName || undefined,
      lastName: value.lastName || undefined,
      // Blank language is OMITTED from the payload.
      language: value.language || undefined,
      // Write-only: blank means unchanged and stays off the wire.
      keycloakPassword: value.keycloakPassword || undefined,
      // Required-actions ride the payload on ADD only.
      keycloakActions:
        model.mode === 'add'
          ? REQUIRED_ACTIONS.filter((a) => value.requiredActions[a.control]).map((a) => a.wire)
          : undefined,
    };
    const request = model.mode === 'add' ? this.api.create(user) : this.api.update(user);
    this.saving.set(true);
    request.subscribe({
      next: () => {
        this.notifications.success(
          model.mode === 'add' ? 'admin.users.toast.added' : 'admin.users.toast.updated',
        );
        this.saved.emit();
      },
      error: () => {
        this.saving.set(false);
        this.notifications.error('admin.users.toast.saveError');
      },
    });
  }
}
