import { Component, computed, input } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';

import { Icon } from '../../../../shared/icons/icon';
import type { TwitterPromulgation } from '../../../messages/promulgation/twitter-view/twitter-promulgation.model';
import type { PromulgationEditorContext } from '../promulgation-editor.token';

/**
 * Twitter channel editor: a single 140-character tweet textarea plus Generate (no Format — the
 * backend renders the tweet, there is nothing to reformat). All writes go through the shared editor
 * context (`patch`/`generate`) so the facade stays the single write path; enable/disable is owned by
 * the parent field's `<fieldset>` (off-channel / non-editable) and the per-type busy flag.
 */
@Component({
  selector: 'app-twitter-edit',
  imports: [TranslocoPipe, Icon],
  template: `
    <div class="flex max-w-xl flex-col gap-2">
      <textarea
        class="text-input"
        rows="3"
        maxlength="140"
        [value]="promulgation().tweet ?? ''"
        [attr.aria-label]="'editor.promulgation.twitter.tweet' | transloco"
        (input)="onTweet($any($event.target).value)"
      ></textarea>
      <div>
        <button
          type="button"
          class="btn-secondary text-xs"
          [disabled]="busy()"
          (click)="context().generate(typeId())"
        >
          @if (busy()) {
            <app-icon name="lucideLoaderCircle" [spin]="true" />
          } @else {
            <app-icon name="lucideRefreshCw" />
          }
          <span>{{ 'term.generate' | transloco }}</span>
        </button>
      </div>
    </div>
  `,
})
export class TwitterEdit {
  readonly promulgation = input.required<TwitterPromulgation>();
  readonly context = input.required<PromulgationEditorContext>();

  protected readonly typeId = computed(() => this.promulgation().type.typeId);
  protected readonly busy = computed(() => this.context().busy(this.typeId()));

  protected onTweet(tweet: string): void {
    this.context().patch(this.typeId(), { tweet });
  }
}
