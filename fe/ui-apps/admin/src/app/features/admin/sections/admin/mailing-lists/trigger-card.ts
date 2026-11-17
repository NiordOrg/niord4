import { Component, computed, inject, input, output } from '@angular/core';
import { FormField, type FieldTree } from '@angular/forms/signals';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';

import { DomainFacade } from '../../../../../core/domain/facades/domain.facade';
import { LanguageService } from '../../../../../core/main-shell/services/language.service';
import { LocaleFormatService } from '../../../../../core/main-shell/services/locale-format.service';
import { ScriptResourcesField } from '../../../components/script-resources-field/script-resources-field';
import { Icon } from '../../../../../shared/icons/icon';
import { TimeField } from '../../../../../shared/forms/time-field/time-field';
import { getZonedDateParts } from '../../../../../shared/utils/zoned-date';
import {
  MAILING_LIST_TRIGGER_TYPES,
  TRIGGER_SCHEDULE_TYPES,
  timeStringToDate,
  type MailingListTriggerType,
  type TriggerFormModel,
} from './mailing-list.model';

/**
 * One mailing-list trigger, rendered as a collapsible card inside the editor drawer. Binds to the
 * editor's Signal Forms sub-field `form.triggers[i]`; structural actions (copy / delete / test) are
 * emitted to the editor which owns the trigger array. The type toggle re-renders the variant
 * fields; the inactive variant's values are dropped only at serialization time (never destructively
 * on switch). Disabling a language's subject clears it.
 */
@Component({
  selector: 'app-trigger-card',
  imports: [FormField, TranslocoPipe, Icon, TimeField, ScriptResourcesField],
  templateUrl: './trigger-card.html',
  host: { class: 'contents' },
})
export class TriggerCard {
  private readonly domain = inject(DomainFacade);
  private readonly transloco = inject(TranslocoService);
  private readonly language = inject(LanguageService);
  private readonly localeFormat = inject(LocaleFormatService);

  readonly triggerField = input.required<FieldTree<TriggerFormModel>>();
  readonly index = input.required<number>();
  readonly editable = input(true);

  readonly duplicate = output<void>();
  readonly remove = output<void>();
  readonly test = output<void>();

  protected readonly triggerTypes = MAILING_LIST_TRIGGER_TYPES;
  protected readonly scheduleTypes = TRIGGER_SCHEDULE_TYPES;

  /** The current trigger value (read model). */
  protected readonly value = computed(() => this.triggerField()().value());

  /**
   * Whether the zone is required here and still unanswered.
   *
   * A scheduled trigger's time names no instant without one, so the editor's schema refuses to
   * save it — and the card says which field is holding the gate shut rather than leaving the
   * disabled button to be guessed at.
   */
  protected readonly timeZoneInvalid = computed(() =>
    this.triggerField().scheduledExecutionTimeZone().invalid(),
  );

  /** Timezone options; if the stored tz is outside the Intl list, keep it selectable. */
  protected readonly timeZones = computed(() => {
    const list = Intl.supportedValuesOf('timeZone');
    const current = this.value().scheduledExecutionTimeZone;
    return current && !list.includes(current) ? [current, ...list] : list;
  });

  /** Collapsed header summary — status list or schedule cadence, per type. */
  protected readonly summary = computed(() => {
    // Depend on the active language so the imperative translate()s below re-run on a language switch.
    this.language.selectedLanguage();
    const v = this.value();
    if (v.type === 'STATUS_CHANGE') {
      const statuses = [
        v.statusPublished
          ? this.transloco.translate('admin.mailingLists.trigger.status.published')
          : null,
        v.statusCancelled
          ? this.transloco.translate('admin.mailingLists.trigger.status.cancelled')
          : null,
        v.statusExpired
          ? this.transloco.translate('admin.mailingLists.trigger.status.expired')
          : null,
      ]
        .filter((label): label is string => label !== null)
        .join(', ');
      return this.transloco.translate('admin.mailingLists.trigger.summary.statusChange', {
        statuses,
      });
    }
    const time = v.scheduledExecutionTime ? this.formatTime(v.scheduledExecutionTime) : '';
    return this.transloco.translate('admin.mailingLists.trigger.summary.scheduled', {
      schedule: this.transloco.translate(
        'admin.mailingLists.trigger.scheduleType.' + v.scheduleType,
      ),
      time,
      timeZone: v.scheduledExecutionTimeZone,
    });
  });

  /** Locale-aware time (12h/24h per locale) — matches the expanded time-field's display. */
  private formatTime(value: Date): string {
    return this.localeFormat.formatTime(value, { local: true });
  }

  protected toggleExpand(): void {
    const field = this.triggerField().expanded();
    field.value.set(!field.value());
  }

  protected setType(type: MailingListTriggerType): void {
    if (!this.editable()) {
      return;
    }
    const tree = this.triggerField();
    tree.type().value.set(type);
    if (type !== 'SCHEDULED') {
      return;
    }
    // Switching TO scheduled seeds the empty fields: the zone this trigger already carries,
    // else the one the session domain works in — and the time as that zone reads it right
    // now. A schedule fires on a wall clock, so an hour seeded from some other clock would
    // send the mail at a time nobody chose; with no zone to seed from, seed nothing and let
    // the editor's requirement ask for one.
    const current = this.value();
    const zone = current.scheduledExecutionTimeZone || this.domain.sessionDomain()?.timeZone;
    if (!zone) {
      return;
    }
    if (!current.scheduledExecutionTime) {
      const { hour, minute } = getZonedDateParts(new Date(), zone);
      const pad = (n: number): string => String(n).padStart(2, '0');
      tree.scheduledExecutionTime().value.set(timeStringToDate(`${pad(hour)}:${pad(minute)}`));
    }
    if (!current.scheduledExecutionTimeZone) {
      tree.scheduledExecutionTimeZone().value.set(zone);
    }
  }

  /** Toggle a language's subject; disabling clears the subject. */
  protected toggleSubject(subjectIndex: number): void {
    const subjectTree = this.triggerField().subjects[subjectIndex];
    const enabled = !subjectTree.enabled().value();
    subjectTree.enabled().value.set(enabled);
    if (!enabled) {
      subjectTree.subject().value.set('');
    }
  }

  protected setSubject(subjectIndex: number, subject: string): void {
    this.triggerField().subjects[subjectIndex].subject().value.set(subject);
  }

  protected setScheduleType(scheduleType: string): void {
    this.triggerField()
      .scheduleType()
      .value.set(scheduleType as TriggerFormModel['scheduleType']);
  }

  protected setTimeZone(zone: string): void {
    this.triggerField().scheduledExecutionTimeZone().value.set(zone);
  }
}
