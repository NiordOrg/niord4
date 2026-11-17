import { Component, computed, inject, signal, viewChild, type Signal } from '@angular/core';
import { Router } from '@angular/router';
import { TranslocoPipe } from '@jsverse/transloco';

import { DomainFacade } from '../../../../core/domain/facades/domain.facade';
import { Card } from '../../../../shared/components/card/card';
import { Icon } from '../../../../shared/icons/icon';
import type { MainType, Message } from '../../../messages/models/message.model';
import type { SystemCategory } from '../../../messages/models/template.model';
import { RecentlyEditedDrafts } from '../../components/recently-edited-drafts/recently-edited-drafts';
import { TemplateSearchPopover } from '../../components/template-search-popover/template-search-popover';
import { EditorFacade } from '../../services/editor-facade';
import { TemplateExecutionSeedStore } from '../../services/template-execution-seed-store';
import { TemplateMessageStore } from '../../services/template-message-store';

/** A NW/NM create card's static config; `canCreate` gates whether it is among `creatableCards`. */
interface StartCard {
  readonly mainType: MainType;
  readonly nameKey: string;
  readonly blankKey: string;
  readonly templateKey: string;
  readonly canCreate: Signal<boolean>;
}

/**
 * The editor Start screen on the bare `/editor` route: "Create a message" with a domain-gated
 * NW/NM card each (blank create + a "from a template" entry), over the recently-edited drafts.
 *
 * A blank create seeds the transient {@link TemplateMessageStore} and enters the editor's template
 * route like the header's Clear flow. "From a template" opens the anchored template-search popover
 * (search + hover example); picking one seeds it into the {@link TemplateExecutionSeedStore} and
 * enters the execution view DIRECTLY on that template — no selection drawer stop-over.
 */
@Component({
  selector: 'app-message-start',
  imports: [TranslocoPipe, Card, Icon, RecentlyEditedDrafts, TemplateSearchPopover],
  templateUrl: './message-start.html',
  host: { class: 'block flex-1 overflow-y-auto' },
})
export class MessageStart {
  private readonly facade = inject(EditorFacade);
  private readonly domain = inject(DomainFacade);
  private readonly templateStore = inject(TemplateMessageStore);
  private readonly seedStore = inject(TemplateExecutionSeedStore);
  private readonly router = inject(Router);

  private readonly templateSearch = viewChild.required(TemplateSearchPopover);
  /** Which card's "from a template" button opened the popover — the pick's mainType. */
  private readonly templateMainType = signal<MainType>('NW');

  /** Whether the active domain exposes executable templates. */
  protected readonly hasTemplates = computed<boolean>(() => this.domain.capabilities().templates);

  protected readonly cards: readonly StartCard[] = [
    {
      mainType: 'NW',
      nameKey: 'editor.start.nwName',
      blankKey: 'editor.new_nw',
      templateKey: 'editor.new_nw_template',
      canCreate: computed(() => this.facade.canCreate('NW')),
    },
    {
      mainType: 'NM',
      nameKey: 'editor.start.nmName',
      blankKey: 'editor.new_nm',
      templateKey: 'editor.new_nm_template',
      canCreate: computed(() => this.facade.canCreate('NM')),
    },
  ];

  /** The cards the user may create from; a lone card is laid out centred. */
  protected readonly creatableCards = computed<readonly StartCard[]>(() =>
    this.cards.filter((card) => card.canCreate()),
  );

  /**
   * The line under the heading. With no card to create from (a plain user, or an editor in a domain
   * with no creatable type) it says so and points at the list, rather than promising a blank message
   * or a template the screen does not offer.
   */
  protected readonly subtitleKey = computed<string>(() => {
    if (this.creatableCards().length === 0) return 'editor.start.subtitleCannotCreate';
    return this.hasTemplates() ? 'editor.start.subtitle' : 'editor.start.subtitleNoTemplate';
  });

  /** Start a fresh blank message of `mainType`, entering the editor like the header Clear flow. */
  protected createBlank(mainType: MainType): void {
    const seed: Message = { id: '', mainType };
    this.templateStore.set(seed);
    void this.router.navigate(['/editor/edit']);
  }

  /** Open the template-search popover anchored on the invoking card button. */
  protected openTemplateSearch(mainType: MainType, event: MouseEvent): void {
    this.templateMainType.set(mainType);
    this.templateSearch().toggle(event.currentTarget as HTMLElement);
  }

  /** A template was picked: enter the execution view directly on a blank seed carrying it. */
  protected onTemplatePicked(template: SystemCategory): void {
    this.seedStore.set(
      { id: '', mainType: this.templateMainType(), categories: [template] },
      '/editor',
    );
    void this.router.navigate(['/editor/execute-template']);
  }
}
