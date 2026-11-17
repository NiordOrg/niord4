import { Component, computed, inject } from '@angular/core';
import { NgOptimizedImage } from '@angular/common';
import { httpResource } from '@angular/common/http';
import { TranslocoPipe } from '@jsverse/transloco';

import { AuthFacade } from '../../../../core/auth/facades/auth.facade';
import { APP_CONFIG } from '../../../../core/config/app-config.token';
import { DomainFacade } from '../../../../core/domain/facades/domain.facade';
import { LanguageService } from '../../../../core/main-shell/services/language.service';
import { computeInitialMapView } from '../../../../shared/components/ol-map/map-view-defaults';
// TYPE-ONLY on purpose: a value import of OlMap here would keep the OpenLayers edge alive in
// this file and the @defer on <app-home-map> would move nothing. `map-view-defaults` is pure
// (its own OL imports are type-only), so `computeInitialMapView` can stay eager.
import type { OlMapView } from '@niord/map/ol-map';
import { HomeMap } from '../home-map/home-map';
import { SlotOutlet } from '../../../../shared/slots/slot-outlet';
import type { Message } from '../../../messages/models/message.model';
import type { MessageSearchResult } from '../../../messages/models/message-search-result.model';

@Component({
  selector: 'app-home-page',
  imports: [NgOptimizedImage, TranslocoPipe, HomeMap, SlotOutlet],
  templateUrl: './home-page.html',
  // `main` in layout.html is now a flex column; making the host a flex column
  // child that grows to fill main lets the inner wrapper use `flex-1` for the
  // map area without measuring viewport-minus-chrome math.
  host: {
    class: 'flex flex-1 flex-col',
  },
})
export class HomePage {
  private readonly auth = inject(AuthFacade);
  private readonly config = inject(APP_CONFIG);
  private readonly domain = inject(DomainFacade);
  private readonly language = inject(LanguageService);

  protected readonly isAuthenticated = this.auth.isAuthenticated;

  protected readonly initialView = computed<OlMapView | undefined>(() =>
    computeInitialMapView(this.domain.mapDefaults(), this.config.map),
  );

  /**
   * All published messages, fetched on mount so the dots are already on the
   * map by the time the welcome panel is interacted with. maxSize 1000 is
   * generous — anonymous search hits PUBLISHED only and typical deployments
   * sit well below that count.
   *
   * Keyed on the active language: the backend localizes each message's `descs`
   * to the requested `lang`, so a language switch must re-fetch for the map
   * labels and hover tooltip to show the new-language titles.
   *
   * TODO(MSG-LANDING-PAGINATION): If any deployment exceeds 1000 published
   * messages, the silent truncation hides the overflow on the landing map.
   * Add a "showing N of total" indicator (or page through results) before
   * shipping to a deployment that approaches this threshold. Errors are
   * swallowed to an empty list so an outage doesn't blank the welcome panel.
   */
  private readonly resource = httpResource<readonly Message[]>(
    () => ({
      url: `${this.config.apiBaseUrl}/messages/search`,
      params: { lang: this.language.selectedLanguage(), page: '0', maxSize: '1000' },
    }),
    {
      defaultValue: [] as readonly Message[],
      parse: (raw) => (raw as MessageSearchResult).data as readonly Message[],
    },
  );

  // A failed fetch leaves the resource in its error state (value() throws); read via hasValue()
  // so an outage degrades to an empty list rather than blanking the welcome panel.
  protected readonly messages = computed<readonly Message[]>(() =>
    this.resource.hasValue() ? this.resource.value() : [],
  );

  protected login(): void {
    this.auth.login();
  }

  /** The map's @error action: a hard reload is the only way to retry a failed defer block. */
  protected reload(): void {
    window.location.reload();
  }
}
