import { Component, inject } from '@angular/core';
import { Observable, of } from 'rxjs';

import { UserDirectoryApiService } from '../../../core/auth/services/users-api.service';
import { EntityMultiselectControl } from '../entity-multiselect-control/entity-multiselect-control';
import { EntityMultiselectField } from '../entity-multiselect-field';

@Component({
  selector: 'app-email-address-field',
  templateUrl: '../entity-multiselect-field.html',
  imports: [EntityMultiselectControl],
})
export class EmailAddressField extends EntityMultiselectField<string, string> {
  private readonly api = inject(UserDirectoryApiService);

  protected idOf(item: string): string {
    return item;
  }
  protected searchEntities(query: string): Observable<readonly string[]> {
    return this.api.searchEmails(query);
  }
  protected resolveByIds(ids: readonly string[]): Observable<readonly string[]> {
    // Emails are their own IDs; nothing to resolve from a server.
    return of(ids);
  }
}
