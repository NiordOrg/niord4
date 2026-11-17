import { Component, inject, input } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Observable, take } from 'rxjs';

import { EntityMultiselectControl } from '../entity-multiselect-control/entity-multiselect-control';
import {
  EntityMultiselectField,
  type EntityMultiselectMagnifier,
} from '../entity-multiselect-field';
import type { MessageTag } from '../../models/message-tag.model';
import { MESSAGE_TAGS_DATA_SOURCE, type MessageTagsDataSource } from './message-tags-data.token';
import {
  MESSAGE_TAGS_DIALOG_OPENER,
  type MessageTagsDialogOpener,
} from './message-tags-dialog.token';

@Component({
  selector: 'app-message-tags-field',
  templateUrl: '../entity-multiselect-field.html',
  imports: [EntityMultiselectControl],
})
export class MessageTagsField extends EntityMultiselectField<MessageTag, string> {
  private readonly dataSource: MessageTagsDataSource = inject(MESSAGE_TAGS_DATA_SOURCE);
  private readonly dialogOpener: MessageTagsDialogOpener = inject(MESSAGE_TAGS_DIALOG_OPENER);

  readonly includeLocked = input<boolean>(false);

  /**
   * Overrides what the browse button opens. Without it the button opens the tag manager dialog,
   * which is the right surface when the field is used to ASSIGN tags. A consumer that browses
   * against a different surface — the message filter, whose picker toggles tags live and includes
   * locked ones — passes its own opener instead. `event.currentTarget` is the button, so the
   * handler can anchor a popover on it.
   */
  readonly browse = input<((event: MouseEvent) => void) | undefined>(undefined);

  override readonly optionLabel = 'name';
  override readonly magnifier: EntityMultiselectMagnifier = {
    icon: 'lucideTags',
    tooltipKey: 'messages.filter.tag.openDialogTooltip',
    buttonClass: 'text-xs',
    onClick: (event) => {
      const browse = this.browse();
      if (browse) {
        browse(event);
        return;
      }
      this.openDialog();
    },
  };

  protected idOf(item: MessageTag): string {
    return item.tagId;
  }
  /** A filter restored from a URL pushes `_placeholder` stubs carrying only the tag id. */
  protected override isResolved(item: MessageTag): boolean {
    return item._placeholder !== true;
  }
  protected searchEntities(query: string): Observable<readonly MessageTag[]> {
    return this.dataSource.search(query);
  }
  protected resolveByIds(ids: readonly string[]): Observable<readonly MessageTag[]> {
    return this.dataSource.resolveByIds(ids);
  }

  protected openDialog(): void {
    this.dialogOpener
      .open({ includeLocked: this.includeLocked() })
      .pipe(take(1), takeUntilDestroyed(this.destroyRef))
      .subscribe((tag) => {
        if (tag) this.appendOne(tag);
      });
  }
}
