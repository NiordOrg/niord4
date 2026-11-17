import { Component, computed, inject } from '@angular/core';
import { translateSignal } from '@jsverse/transloco';

import { UserField } from '../../../../../core/auth/components/user-field/user-field';
import type { UserType } from '../../../models/messages-filter-params.model';
import { FilterPanel } from '../../../../../shared/components/filter-panel/filter-panel';
import { MessagesFacade } from '../../../services/messages-facade.service';

interface UserTypeOption {
  readonly label: string;
  readonly value: UserType;
}

@Component({
  selector: 'app-user-filter-panel',
  templateUrl: './user-filter-panel.html',
  imports: [FilterPanel, UserField],
})
export class UserFilterPanel {
  private readonly messages = inject(MessagesFacade);

  private readonly createdLabel = translateSignal('messages.filter.user.type.createdBy');
  private readonly updatedLabel = translateSignal('messages.filter.user.type.updatedBy');
  private readonly lastUpdatedLabel = translateSignal('messages.filter.user.type.lastUpdatedBy');

  readonly slice = computed(() => this.messages.filter.slice('user')());
  readonly username = computed(() => this.slice().username ?? null);
  readonly userType = computed(() => this.slice().userType ?? null);

  readonly userTypeOptions = computed<UserTypeOption[]>(() => [
    { label: this.createdLabel(), value: 'CREATED_BY' },
    { label: this.updatedLabel(), value: 'UPDATED_BY' },
    { label: this.lastUpdatedLabel(), value: 'LAST_UPDATED_BY' },
  ]);

  onUsernameChange(username: string | null): void {
    this.messages.filter.setSliceValue('user', {
      username: username ?? undefined,
      userType: this.userType() ?? undefined,
    });
  }

  onUserTypeChange(value: UserType | null): void {
    this.messages.filter.setSliceValue('user', {
      username: this.username() ?? undefined,
      userType: value ?? undefined,
    });
  }

  clear(): void {
    this.messages.filter.clearFilter('user');
  }
}
