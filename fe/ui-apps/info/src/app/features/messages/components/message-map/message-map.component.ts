import {
  Component,
  computed,
  effect,
  inject,
  Injector,
  input,
  signal,
  viewChild,
} from '@angular/core';
import { DomSanitizer } from '@angular/platform-browser';
import { TranslocoPipe, translateSignal } from '@jsverse/transloco';

import { MapShellService } from '@niord/map/map-shell.service';
import { OlLayerSwitcher } from '@niord/map/ol-layer-switcher';
import { OlMap, type OlMapView } from '@niord/map/ol-map';
import { OlTileLayer } from '@niord/map/layers/ol-tile-layer';
import { OlWmsLayer } from '@niord/map/layers/ol-wms-layer';

import { AppConfigService } from '../../../../core/services/app-config.service';
import { Area, Message } from '../../../../core/models/message.model';
import { featuresForMessage } from '../../../../core/models/message.util';
import { openInfoDialog } from '../../../../shared/components/dialog/open-dialog';
import { MessageFeaturesLayer } from '../../../../shared/components/message-features-layer/message-features-layer';
import { ContentLang } from '../../../../shared/directives/content-lang';
import { initialMapView, rootAreaView } from '../../../../shared/utils/root-area-view';
import { MessageIdBadgeComponent } from '../message-id-badge/message-id-badge.component';
import { MessageLabelsLayer } from '../map-layers/message-labels-layer';
import { MessageMapTooltip } from '../map-layers/message-map-tooltip';
import { attributionHtml } from './map-attribution';

/** What OpenStreetMap asks a site that draws its tiles to say. */
const OSM_CREDIT =
  '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors.';

const MAP_ACCESSIBILITY = 'MAP_ACCESSIBILITY';
const MAP_COPYRIGHT = 'MAP_COPYRIGHT';

/**
 * The public site's chart: the shared map kit, the site's own message layers, and the two
 * panels that sit beside the chart rather than on it.
 *
 * The shell is provided here rather than inside `<app-ol-map>` so the layer switcher — a
 * sibling of the map, not a child — sees the same layers.
 */
@Component({
  selector: 'app-message-map',
  imports: [
    TranslocoPipe,
    OlMap,
    OlTileLayer,
    OlWmsLayer,
    OlLayerSwitcher,
    MessageFeaturesLayer,
    MessageLabelsLayer,
    MessageMapTooltip,
    MessageIdBadgeComponent,
    ContentLang,
  ],
  host: { class: 'block' },
  providers: [MapShellService],
  template: `
    <app-ol-map [view]="initialView">
      <app-ol-tile-layer [attributions]="attributions()" />

      @if (wmsVisible()) {
        <app-ol-wms-layer url="/wms/" [visible]="true" [name]="wmsName()" />
      }

      <app-message-features-layer
        [messages]="allMessages()"
        [fitExtent]="fitExtent()"
        [maxZoom]="maxZoom()"
        [fallbackView]="rootAreaView()"
        [interactive]="featuresAreInteractive()"
        (featureClicked)="onFeatureClicked($event)"
      />

      @if (detailsMap()) {
        <app-message-labels-layer [message]="message()" [name]="labelsName()" />
      }

      @if (featuresAreInteractive()) {
        <app-message-map-tooltip />
      }
    </app-ol-map>

    <app-ol-layer-switcher title="" class="absolute bottom-2 left-2 z-10" />

    <!-- The panel leaves every control of the chart uncovered, so none is hidden or takes focus
         out of sight. On a phone it starts clear of the zoom buttons in the top-left corner, as
         the line that reports a failed load does; at every width it ends far enough above the
         bottom of the chart to leave the credits there showing, and never reaches past that
         bottom, which a short screen would otherwise push it over. -->
    @if (showNoPosMessages() && noPos().length > 0) {
      <div
        class="absolute top-2 right-2 left-11 z-30 max-h-[min(25rem,calc(100%-4.5rem))] overflow-y-auto bg-surface-0/60 p-2 shadow-niord-md sm:left-auto sm:w-75 lg:w-100"
      >
        <div>
          <h4 class="flex items-center justify-between gap-2 font-semibold">
            {{ 'NO_POS_MSGS' | transloco }}
            <button
              type="button"
              class="link-button is-quiet text-xs"
              [attr.aria-expanded]="!minimized()"
              aria-controls="no-pos-messages"
              (click)="toggleMinimize()"
            >
              @if (minimized()) {
                {{ 'MAXIMIZE' | transloco }}
              } @else {
                {{ 'MINIMIZE' | transloco }}
              }
            </button>
          </h4>
        </div>
        <div id="no-pos-messages">
          @if (!minimized()) {
            @for (msg of noPos(); track msg.id) {
              <button
                type="button"
                class="row-interactive block w-full border-b border-surface-200 px-1.5 py-1 text-left last:border-b-0"
                (click)="openMessageDialog(msg.id)"
              >
                <app-message-id-badge [msg]="msg" [showStatus]="true" />
                @if (msg.descs) {
                  <strong [appContentLang]="msg.descs[0].lang">{{ msg.descs[0].title }}</strong>
                }
              </button>
            }
          }
        </div>
      </div>
    }
  `,
})
export class MessageMapComponent {
  private readonly appConfig = inject(AppConfigService);
  private readonly injector = inject(Injector);
  private readonly sanitizer = inject(DomSanitizer);

  readonly messages = input<readonly Message[] | undefined>(undefined);
  readonly message = input<Message | undefined>(undefined);
  readonly fitExtent = input(false);
  readonly rootArea = input<Area | undefined>(undefined);
  readonly showNoPosMessages = input(false);
  readonly maxZoom = input(10);

  readonly minimized = signal(false);

  private readonly tooltip = viewChild(MessageMapTooltip);
  private readonly featuresLayer = viewChild(MessageFeaturesLayer);

  /** The branding file words the link; the address is the country configuration's. */
  private readonly accessibility = translateSignal(MAP_ACCESSIBILITY, {
    url: this.appConfig.accessibilityStatement,
  });
  private readonly copyright = translateSignal(MAP_COPYRIGHT);

  /**
   * Where the map points until the messages frame it: the country's first root area, which the
   * build knows before anything has loaded.
   */
  protected readonly initialView: OlMapView = initialMapView(this.appConfig.rootAreaSpecs());

  /** One message on its own map: the details map inside the dialog. */
  protected readonly detailsMap = computed(() => this.message() !== undefined);

  protected readonly allMessages = computed<readonly Message[]>(() => {
    const list = this.messages();
    if (list) return list;
    const single = this.message();
    return single ? [single] : [];
  });

  /**
   * The messages with no position of their own, which the chart cannot draw and lists beside
   * itself instead. Derived from the messages alone, so the list is there with the first render,
   * whether or not the map has been created yet.
   */
  readonly noPos = computed<readonly Message[]>(() =>
    this.allMessages().filter((message) => featuresForMessage(message).length === 0),
  );

  /**
   * A reader follows a message from the chart, where it is one of many. The details map shows
   * the one message its dialog was opened for, so there is nothing on it to follow.
   */
  protected readonly featuresAreInteractive = computed(() => !this.detailsMap());

  /**
   * The site owner's credits beside OpenStreetMap's. They are read as signals, so a language
   * switch re-words them; until the bundle lands Transloco echoes the key name, and the credit
   * that is always true is shown alone rather than a key to a mariner. A deployment that names
   * no accessibility statement has no link to word. Each goes through `attributionHtml`,
   * because OpenLayers writes them into the page without Angular's binding.
   */
  protected readonly attributions = computed<readonly string[]>(() => {
    const accessibility = this.accessibility();
    const copyright = this.copyright();
    const owner =
      accessibility === MAP_ACCESSIBILITY || copyright === MAP_COPYRIGHT
        ? []
        : [...(this.appConfig.accessibilityStatement ? [accessibility] : []), copyright];
    const credits = [...owner, OSM_CREDIT];
    return credits.map((credit) => attributionHtml(credit, this.sanitizer));
  });

  /** The chart overlay, which only a deployment that has a chart server asks for. */
  protected readonly wmsVisible = computed(() => this.detailsMap() && this.appConfig.wmsLayer());
  protected readonly wmsName = translateSignal('LAYER_WMS');
  protected readonly labelsName = translateSignal('LAYER_LABELS');

  /**
   * Where the map points when nothing drawn on it has a position. A root area whose spec is
   * incomplete leaves the map where it was, as it always has.
   */
  protected readonly rootAreaView = computed<OlMapView | undefined>(() =>
    rootAreaView(this.rootArea()),
  );

  constructor() {
    // The tooltip asks the layer what is under a pixel; neither knows about the other.
    effect(() => {
      const tooltip = this.tooltip();
      const layer = this.featuresLayer();
      if (tooltip && layer) tooltip.setResolver((pixel) => layer.messagesAtPixel(pixel));
    });
  }

  protected onFeatureClicked(messageId: string): void {
    // The pointer is over the feature that was clicked, and nothing else will clear the
    // tooltip while a dialog is covering the map.
    this.tooltip()?.hide();
    this.openMessageDialog(messageId);
  }

  openMessageDialog(messageId: string): void {
    // The list the dialog steps through is the chart's own, followed rather than copied.
    const messageIds = computed(() => this.messages()?.map((msg) => msg.id) ?? [messageId]);
    void openInfoDialog(
      this.injector,
      () =>
        import('../message-details-dialog/message-details-dialog.component').then(
          (m) => m.MessageDetailsDialogComponent,
        ),
      { messageId, messageIds },
    );
  }

  toggleMinimize(): void {
    this.minimized.update((value) => !value);
  }
}
