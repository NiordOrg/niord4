import { Component, input, inject, computed } from '@angular/core';
import { Area } from '../../../../core/models/message.model';
import { MessageLabelService } from '../../../../core/services/message-label.service';
import { ContentLang } from '../../../../shared/directives/content-lang';

/** One name of a lineage, preceded by the divider unless it is the first, and its language. */
interface NameSegment {
  readonly text: string;
  readonly lang: string;
}

/**
 * An area's name, or its lineage from the root down. Each name is in the language the backend
 * had it in, which is not always the page's, so each carries its own.
 */
@Component({
  selector: 'app-message-area-name',
  imports: [ContentLang],
  template: `
    @for (segment of segments(); track $index) {
      <span [appContentLang]="segment.lang">{{ segment.text }}</span>
    }
  `,
})
export class MessageAreaNameComponent {
  private readonly labels = inject(MessageLabelService);

  area = input.required<Area>();
  lineage = input(true);
  divider = input(' - ');

  /** Root first; an area with no name in any language is left out, divider and all. */
  protected readonly segments = computed<readonly NameSegment[]>(() => {
    const names: { name: string; lang: string }[] = [];
    let current: Area | undefined = this.area();
    while (current) {
      const desc = this.labels.desc(current);
      if (desc?.name) names.unshift({ name: desc.name, lang: desc.lang });
      current = this.lineage() ? current.parent : undefined;
    }
    return names.map(({ name, lang }, index) => ({
      text: (index > 0 ? this.divider() : '') + name,
      lang,
    }));
  });
}
