import { InjectionToken, Type } from '@angular/core';
import type { LanguageOption } from './core/config/language-config';

export interface RootAreaSpec {
  areaId: string;
  latitude: number;
  longitude: number;
  zoomLevel: number;
}

/** The part of the configuration a country build owns (`country-specific/<cc>/info.config.ts`). */
export interface InfoConfig {
  timeZone: string;
  /** Build-time fallback for the deployment ribbon; the backend `mode` setting wins. */
  executionMode: string;
  wmsLayer: boolean;
  /** Empty means "no area filter": the whole data set. */
  rootAreas: RootAreaSpec[];
  /** Custom footer component. If omitted, a minimal default footer is rendered. */
  footerComponent?: Type<unknown>;
  /**
   * The address of the deployment's accessibility statement. The footer and the map's credits
   * both link it, and the branding files word the link in each language without repeating the
   * address, so a new statement is this one line. When omitted, neither links one.
   */
  accessibilityStatement?: string;
  /**
   * The public hosts this country's backend serves its repository under, each as an address
   * writes it: `host` or `host:port`, lower case. Message HTML saved while the backend wrote
   * absolute links carries them, and a repository link on one of them is loaded from this site
   * instead — the backend's host is another origin, and one a ship may not reach. A repository
   * link on any other host is another deployment's and is left as written. When omitted, only a
   * link on the site's own host is rewritten.
   */
  backendHosts?: readonly string[];
}

export interface NiordInfoConfig extends InfoConfig {
  languages: string[];
  defaultLanguage: string;
  /** Every language the build ships a bundle for, with its name and flag. */
  languageOptions: readonly LanguageOption[];
  /** The languages the build ships branding for; absent means every language. */
  brandingLanguages?: readonly string[];
}

export const NIORD_INFO_CONFIG = new InjectionToken<NiordInfoConfig>('NIORD_INFO_CONFIG');
