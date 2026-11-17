import { Component, viewChild } from '@angular/core';

import { FilterPanel } from '../../../../../shared/components/filter-panel/filter-panel';
import { MessageTagsField } from '../../../../../shared/forms/message-tags-field/message-tags-field';
import type { MessageTag } from '../../../../../shared/models/message-tag.model';
import { TagPicker, type TagPickerToggle } from '../../tag-picker/tag-picker';
import type { SliceValueOf } from '../../../models/messages-filter-params.model';
import { EntityFilterPanelBase } from '../entity-filter-panel.base';
import { FilterPanelAutofocus } from '../filter-panel-autofocus.directive';

/**
 * Tags filter — chips plus an inline name search, with the tag picker behind the field's browse
 * button. The two are doors onto one slice: a typeahead pick writes the whole array, a picker
 * toggle flips one tag, and both land in the same filter slice.
 *
 * The browse button opens the PICKER in filter mode rather than the field's default tag-manager
 * dialog: filter mode keeps locked tags selectable, offers the scope chips, and applies each
 * toggle live, none of which the manager does.
 */
@Component({
  selector: 'app-tag-filter-panel',
  templateUrl: './tag-filter-panel.html',
  imports: [FilterPanel, FilterPanelAutofocus, MessageTagsField, TagPicker],
})
export class TagFilterPanel extends EntityFilterPanelBase<'tag', MessageTag, string> {
  private readonly picker = viewChild.required(TagPicker);

  protected readonly sliceName = 'tag';

  protected extractValue(slice: SliceValueOf<'tag'>): readonly MessageTag[] {
    return slice.tags;
  }
  protected idOf(item: MessageTag): string {
    return item.tagId;
  }
  protected toSliceValue(items: readonly MessageTag[]): SliceValueOf<'tag'> {
    return { tags: items };
  }

  /** Bound as a value, so it must stay an arrow — the field calls it detached from this class. */
  protected readonly openPicker = (event: MouseEvent): void => {
    if (event.currentTarget instanceof HTMLElement) {
      this.picker().toggle(event.currentTarget);
    }
  };

  protected onPickerToggle({ tag, selected }: TagPickerToggle): void {
    const current = this.value();
    this.onChange(selected ? [...current, tag] : current.filter((t) => t.tagId !== tag.tagId));
  }
}
