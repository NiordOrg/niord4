import { Component, computed, inject } from '@angular/core';
import { httpResource } from '@angular/common/http';
import { ActivatedRoute } from '@angular/router';
import { TranslocoService, translateSignal } from '@jsverse/transloco';
import { MessageDetailCardComponent } from '../message-detail-card/message-detail-card.component';
import { MessageAreaNameComponent } from '../message-detail-card/message-area-name.component';
import { LanguageService } from '../../../../core/services/language.service';
import { DateFormatService } from '../../../../core/services/date-format.service';
import { MessageService } from '../../../../core/services/message.service';
import { Message, Area } from '../../../../core/models/message.model';
import {
  computeAreaHeadings,
  filterByAreaIds,
  isActiveAt,
  sortDescs,
} from '../../../../core/models/message.util';
import { Icon } from '../../../../shared/icons/icon';

/** The keys the sheet's own banner is built from, and the two it reports a failed read with. */
const PRINT_BANNER = 'PRINT_BANNER';
const PRINT_DATE_FORMAT = 'PRINT_DATE_FORMAT';
const MENU_NM = 'MENU_NM';
const MENU_NW = 'MENU_NW';
const MSG_LOAD_FAILED = 'MSG_LOAD_FAILED';
const MSGS_LOAD_FAILED = 'MSGS_LOAD_FAILED';

@Component({
  selector: 'app-message-print-view',
  imports: [MessageDetailCardComponent, MessageAreaNameComponent, Icon],
  styles: `
    :host {
      display: block;
    }

    .print-body {
      font-size: 11px;
      font-family: Helvetica, Arial, sans-serif;
      margin: 0;
      padding: 0;
      background-color: #eee;
    }

    .message-details-list {
      margin: 10px auto;
      max-width: 820px;
      padding: 0 10px;
      font-size: 12px;
    }

    /* The sheet's banner wears the site's chrome, and its area headings the listings' heading
       colour, so a brand's own values reach paper as well. */
    .message-search-text {
      font-size: 120%;
      margin-bottom: 5mm;
      padding: 3mm;
      color: var(--niord-header-fg);
      background-color: var(--niord-header-bg);
      box-shadow: 0 1px 2px rgba(0, 0, 0, 0.2);
    }

    .message-details-item {
      padding: 20px;
      margin: 5px 0;
      background-color: white;
      box-shadow: 0 2px 4px rgba(0, 0, 0, 0.3);
    }

    h4.message-area-heading {
      color: var(--niord-site-heading);
      font-size: 20px;
      margin-top: 30px;
    }

    /* Nothing here is operable: the page is printed, and a link that looks like one invites a
       click that does nothing. */
    :host ::ng-deep .link-button {
      all: unset;
      color: inherit;
      font: inherit;
      cursor: default;
    }

    :host ::ng-deep app-message-detail-card app-icon {
      display: none;
    }

    @media print {
      .print-body {
        background: white;
      }
      .message-search-text {
        -webkit-print-color-adjust: exact;
        print-color-adjust: exact;
      }
      .message-details-item {
        -webkit-print-color-adjust: exact;
        print-color-adjust: exact;
        page-break-inside: avoid;
        box-shadow: none;
        border: 1px solid #ddd;
      }
    }
  `,
  template: `
    <div class="print-body">
      <div class="message-details-list">
        <div class="message-search-text">{{ bannerText() }}</div>

        @if (error(); as err) {
          <p class="message message-error">
            <app-icon name="lucideTriangleAlert" class="message-icon" />
            {{ err }}
          </p>
        }

        @for (msg of messages(); track msg.id) {
          @if (areaHeadings().get(msg.id); as heading) {
            <h4 class="message-area-heading">
              <app-message-area-name [area]="heading" [lineage]="false" />
            </h4>
          }
          <div class="message-details-item">
            <app-message-detail-card [msg]="msg" format="details" />
          </div>
        }
      </div>
    </div>
  `,
})
export class MessagePrintViewComponent {
  private readonly route = inject(ActivatedRoute);
  private readonly transloco = inject(TranslocoService);
  private readonly languageService = inject(LanguageService);
  private readonly language = this.languageService.language;
  private readonly api = inject(MessageService);
  private readonly dateFormat = inject(DateFormatService);

  /**
   * The sheet prints what its address names, and the address is fixed: the route is reached from
   * a `/details.html` link of the previous site, which the container redirects here with its
   * query intact, and nothing on the sheet navigates.
   */
  private readonly query = this.route.snapshot.queryParams;
  /** The sub-areas the address narrows a list to (`?subAreaId=`), as an area's numeric keys. */
  private readonly subAreaIds = new Set(
    this.route.snapshot.queryParamMap.getAll('subAreaId').map(Number),
  );
  private readonly singleId = this.query['messageId'] as string | undefined;

  /** The moment the sheet was made, stamped on it once and rendered in the deployment's zone. */
  private readonly printedAt = new Date();

  /**
   * `?active=true` narrows a list to what is in force, judged at the instant stamped on the
   * sheet. The public API has no such filter, so it is applied here; a publication is printed
   * whole, as the page lists it.
   */
  private readonly activeOnly = this.query['active'] === 'true' && !this.query['publication'];

  constructor() {
    // The sheet is printed in the language its address names. Writing it into the language
    // service is state arriving from outside the signal graph, which is what a constructor write
    // is for; everything below keys on the result. It is the address's language, not a choice
    // the reader made, so it is not remembered.
    const requested = this.query['language'] ?? this.query['lang'];
    if (requested) this.languageService.useLanguage(requested);
  }

  /** One message: `?messageId=`. The resource stays idle when the sheet prints a list. */
  private readonly oneResource = httpResource<Message | undefined>(
    () =>
      this.singleId
        ? {
            url: `/rest/public/v1/message/${encodeURIComponent(this.singleId)}`,
            params: { lang: this.language(), externalize: 'false' },
          }
        : undefined,
    { defaultValue: undefined, parse: (raw) => this.api.parseOne(raw) },
  );

  /** A list: the filter the address names. Idle when the sheet prints one message. */
  private readonly listResource = httpResource<Message[]>(
    () =>
      this.singleId
        ? undefined
        : {
            url: '/rest/public/v1/messages',
            params: {
              // `lang` is the public API's name for it; the address's own `?language=` is only
              // what chose the language this sheet is printed in.
              lang: this.language(),
              // The public API points repository links at the backend's own host unless this
              // is off; the container that serves this site proxies root-relative paths only.
              externalize: 'false',
              ...(this.mainTypes().length > 0 ? { mainType: this.mainTypes() } : {}),
              ...(this.query['areaId'] ? { areaId: this.query['areaId'] as string } : {}),
              ...(this.query['publication']
                ? { publication: this.query['publication'] as string }
                : {}),
            },
          },
    { defaultValue: [], parse: (raw) => this.api.parseList(raw) },
  );

  readonly messages = computed<readonly Message[]>(() => {
    const language = this.language();
    if (this.singleId) {
      const one = this.oneResource.hasValue() ? this.oneResource.value() : undefined;
      return one ? [sortDescs(one, language)] : [];
    }
    const listed = this.listResource.hasValue() ? this.listResource.value() : [];
    const sorted = listed.map((message) => sortDescs(message, language));
    const inForce = this.activeOnly
      ? sorted.filter((message) => isActiveAt(message, this.printedAt.getTime()))
      : sorted;
    return filterByAreaIds(inForce, this.subAreaIds);
  });

  /** A printed list is grouped by area; one message is printed on its own, without a heading. */
  readonly areaHeadings = computed(() =>
    this.singleId ? new Map<string, Area>() : computeAreaHeadings(this.messages()),
  );

  /**
   * A print window renders before its translation bundle has arrived, and `translate()` answers
   * with the key name until it does. Reading the banner's own key as a signal is what redraws
   * the sheet when the bundle lands, and what leaves the banner empty until then rather than
   * printing the key name across the top of it.
   */
  private readonly bannerPattern = translateSignal<string>(PRINT_BANNER);

  readonly bannerText = computed(() =>
    this.bannerPattern() === PRINT_BANNER
      ? ''
      : this.transloco.translate(PRINT_BANNER, {
          nm: this.transloco.translate(MENU_NM),
          nw: this.transloco.translate(MENU_NW),
          date: this.dateFormat.format(this.printedAt, PRINT_DATE_FORMAT),
        }),
  );

  private readonly oneFailed = translateSignal<string>(MSG_LOAD_FAILED);
  private readonly listFailed = translateSignal<string>(MSGS_LOAD_FAILED);

  readonly error = computed(() => {
    if (this.oneResource.status() === 'error') return this.oneFailed();
    if (this.listResource.status() === 'error') return this.listFailed();
    return undefined;
  });

  /** `?mainType=` may be absent, one value or several; the backend takes it repeated. */
  private mainTypes(): string[] {
    const requested = this.query['mainType'] as string | string[] | undefined;
    if (!requested) return [];
    return Array.isArray(requested) ? requested : [requested];
  }
}
