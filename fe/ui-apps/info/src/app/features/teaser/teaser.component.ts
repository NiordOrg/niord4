import { Component, computed, inject } from '@angular/core';
import { httpResource } from '@angular/common/http';
import { TranslocoPipe } from '@jsverse/transloco';
import { ActivatedRoute, RouterLink } from '@angular/router';

import { MapShellService } from '@niord/map/map-shell.service';
import { OlMap, type OlMapView } from '@niord/map/ol-map';
import { OlTileLayer } from '@niord/map/layers/ol-tile-layer';

import { AppConfigService } from '../../core/services/app-config.service';
import { LanguageService } from '../../core/services/language.service';
import { MessageService } from '../../core/services/message.service';
import { sortDescs } from '../../core/models/message.util';
import { Message } from '../../core/models/message.model';
import { MessageFeaturesLayer } from '../../shared/components/message-features-layer/message-features-layer';
import { Icon } from '../../shared/icons/icon';
import { initialMapView } from '../../shared/utils/root-area-view';

@Component({
  selector: 'app-teaser',
  imports: [TranslocoPipe, RouterLink, OlMap, OlTileLayer, MessageFeaturesLayer, Icon],
  host: { class: 'fixed inset-0 block bg-surface-200' },
  // The layers are declared here, so the shell they register with has to be here too: a
  // component projected into <app-ol-map> is injected from the template that declares it.
  providers: [MapShellService],
  template: `
    <!-- The link fills the frame, so the browser's focus outline falls outside the screen, and an
         outline on the link itself is painted under the map, which is positioned above it. The
         focus ring is therefore drawn by a layer over the map and the notes beside the link,
         inward from the frame's edge; it takes no clicks, so the credit's link still does. -->
    <a
      class="relative block h-full w-full after:pointer-events-none after:absolute after:inset-0 after:z-50 focus-visible:after:outline-2 focus-visible:after:-outline-offset-2 focus-visible:after:outline-primary-500"
      [routerLink]="['/messages/map']"
      target="_blank"
      rel="noopener"
      aria-labelledby="teaser-banner"
    >
      <!-- One big link, so the map must not move under the pointer: a drag that panned it
           instead of following the link would be a broken link. -->
      <app-ol-map
        class="absolute inset-0"
        [view]="initialView"
        [readOnly]="true"
        [interactive]="false"
      >
        <app-ol-tile-layer [attributions]="mapCredits" />
        <app-message-features-layer
          [messages]="messages()"
          [fitExtent]="true"
          [maxZoom]="10"
          [fallbackView]="initialView"
          [interactive]="false"
        />
      </app-ol-map>
      <!-- The banner names the link: its text carries line breaks as markup, so binding the key
           to aria-label would read the tags aloud, where the rendered element reads as its text. -->
      <div
        id="teaser-banner"
        class="absolute right-10 top-10 z-30 bg-chrome/60 p-5 text-lg/7 text-chrome-fg shadow-niord-md"
        [innerHTML]="'TEASER_TEXT' | transloco"
      ></div>
    </a>
    <!-- OpenStreetMap's credit, with its link to the licence. It sits beside the teaser's link
         rather than in it, as the notice below does: a link inside a link is neither valid markup
         nor something a screen reader can announce, so the map's own credit line is left empty and
         this one takes its place, drawn with OpenLayers' attribution classes so it looks like the
         credit of every other map on the site. It opens in a new tab, as the teaser's link does:
         the teaser lives in a frame on somebody else's page. Its words are English on a page in
         every language, so it says so. -->
    <div class="ol-control ol-attribution ol-uncollapsible z-40">
      <ul>
        <li lang="en">
          &copy;
          <a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noopener"
            >OpenStreetMap</a
          >
          contributors.
        </li>
      </ul>
    </div>
    <!-- A chart with nothing on it reads as "no warnings in this area" on the page the teaser is
         embedded in, so a read that failed says so. It sits beside the link rather than in it,
         where the banner alone names the link, and lets a click through to it. The region is
         there from the start so the notice is announced when it arrives; politely, because it
         speaks inside somebody else's page. -->
    <div
      role="status"
      class="pointer-events-none absolute bottom-2 left-2 z-40 max-w-[calc(100%-3rem)]"
    >
      @if (failed()) {
        <p class="message message-error">
          <app-icon name="lucideTriangleAlert" class="message-icon" />
          {{ 'MSGS_LOAD_FAILED' | transloco }}
        </p>
      }
    </div>
  `,
})
export class TeaserComponent {
  private readonly route = inject(ActivatedRoute);
  private readonly appConfig = inject(AppConfigService);
  private readonly languageService = inject(LanguageService);
  private readonly api = inject(MessageService);

  /**
   * The area the teaser shows: the one the embedding page names with `?area=`, otherwise the
   * country's first root area, as the site's own map starts on. A deployment with no root area
   * shows every message.
   */
  private readonly area =
    (this.route.snapshot.queryParams['area'] as string | undefined) ??
    this.appConfig.rootAreaSpecs()[0]?.areaId;

  /**
   * Where the map points until the messages frame it, and where it stays when there are none to
   * frame: the area it shows, when the country names it, otherwise the country's first.
   */
  protected readonly initialView: OlMapView = initialMapView(
    this.appConfig.rootAreaSpecs(),
    this.area,
  );
  /**
   * The map's own credit line, empty: that line is drawn inside the teaser's link, where
   * OpenStreetMap's credit could not link to its licence. The template gives the credit beside the
   * link instead.
   */
  protected readonly mapCredits: readonly string[] = [];

  constructor() {
    // The teaser is embedded on other sites, which choose its language through ?lang=. Writing
    // that into the language service is state leaving the signal graph, which is what a
    // constructor write is for; the read below then keys on the result. It is the embedding
    // site's choice, not the reader's, so it is not remembered for their next visit.
    const requested = this.route.snapshot.queryParams['lang'] as string | undefined;
    if (requested) this.languageService.useLanguage(requested);
  }

  private readonly resource = httpResource<Message[]>(
    () => ({
      url: '/rest/public/v1/messages',
      params: {
        lang: this.languageService.language(),
        mainType: ['NW', 'NM'],
        externalize: 'false',
        ...(this.area ? { areaId: this.area } : {}),
      },
    }),
    { defaultValue: [], parse: (raw) => this.api.parseList(raw) },
  );

  readonly messages = computed<Message[]>(() => {
    const language = this.languageService.language();
    const fetched = this.resource.hasValue() ? this.resource.value() : [];
    return fetched.map((message) => sortDescs(message, language));
  });

  /** A read that did not arrive, which an empty chart must not be taken for. */
  readonly failed = computed(() => this.resource.status() === 'error');
}
