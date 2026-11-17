/**
 * Single source of truth for the two admin areas' sections. The rail, the overview
 * launcher cards, and the child-route registration are all projected from these
 * descriptors so they can never drift apart (one entry = one rail item = one card
 * = one route).
 *
 * `helpSection` keys into `HELP_URLS` (`core/main-shell/help/help-urls.json`). Overview
 * pages use a non-empty key mapped to the manual's overview chapter — an empty string
 * would be dropped by `HelpUrlService` and render no help link. A section without
 * `helpSection` shows no help link.
 */
export interface AdminSectionDescriptor {
  /** URL path segment under the area root — fixed now so later work only swaps the component. */
  readonly path: string;
  readonly labelKey: string;
  readonly descriptionKey: string;
  /** Icon shown on the rail entry and the overview launcher card. */
  readonly icon: string;
  readonly helpSection?: string;
  /** Rail entry shows the live running-batch-jobs count badge. */
  readonly hasBatchBadge?: boolean;
  /**
   * Optional backend setting key gating the section's RUNTIME VISIBILITY. When
   * set, the rail entry and overview card are withheld until the setting resolves
   * truthy (boolean `true` or string `'true'`); a fetch error/missing setting
   * keeps it hidden. The route itself is always registered (deep links resolve) —
   * the setting affects only the rail entry and overview card, never routability.
   */
  readonly settingGate?: string;
}

export interface AdminAreaDescriptor {
  readonly titleKey: string;
  readonly introKey: string;
  /** Non-empty `HELP_URLS` key for the overview route (the manual's overview chapter). */
  readonly overviewHelpSection: string;
  readonly sections: readonly AdminSectionDescriptor[];
}

function section(path: string, icon: string, helpSection?: string): AdminSectionDescriptor {
  return {
    path,
    labelKey: `admin.sections.${path}.label`,
    descriptionKey: `admin.sections.${path}.description`,
    icon,
    helpSection,
  };
}

/** Icon for the area overview rail entry + card (both areas). */
export const OVERVIEW_ICON = 'lucideHouse';

export const ADMIN_AREA: AdminAreaDescriptor = {
  titleKey: 'menu.admin',
  introKey: 'admin.overview.adminIntro',
  overviewHelpSection: 'admin-manual',
  sections: [
    section('charts', 'lucideMap', 'charts'),
    section('areas', 'lucideMapPin', 'areas'),
    section('publications', 'lucideBook', 'publications'),
    // Its own section rather than a tab inside Publications: the categories are
    // configuration a series points AT, they are read and changed on their own
    // schedule, and one of them carries the flag that decides whether a whole
    // group of publications reaches the public site at all.
    section('publication-categories', 'lucideTags', 'publication-categories'),
    section('sources', 'lucideBookmark', 'sources'),
    section('users', 'lucideUsers', 'users-contacts'),
    section('mailing-lists', 'lucideMail', 'mailing-lists'),
  ],
};

export const SYSADMIN_AREA: AdminAreaDescriptor = {
  titleKey: 'menu.sysadmin',
  introKey: 'admin.overview.sysadminIntro',
  overviewHelpSection: 'sysadmin-manual',
  sections: [
    section('series', 'lucideList', 'message-series'),
    section('domains', 'lucideGlobe', 'domains'),
    section('promulgation', 'lucideSend', 'promulgation-types'),
    section('schedules', 'lucideCalendar', 'firing-schedules'),
    section('dictionaries', 'lucideLanguages', 'dictionaries'),
    section('param-types', 'lucideSlidersHorizontal', 'parameter-types'),
    section('script-resources', 'lucideCode', 'script-resources'),
    section('categories', 'lucideFolder', 'categories'),
    section('reports', 'lucideFile', 'print-reports'),
    section('mails', 'lucideInbox', 'mail-queue'),
    // Visibility-gated section: the rail entry and overview card are shown only
    // when the backend `adminIntegrationPageEnabled` setting resolves truthy; the
    // route is always registered (deep links resolve); the setting affects only
    // the rail entry and overview card.
    {
      ...section('niord-integration', 'lucideRefreshCw', 'integration'),
      settingGate: 'adminIntegrationPageEnabled',
    },
    // Visibility-gated section: `secomEnabled` is a value `/rest/settings/web`
    // computes from the running `niord.secom.enabled`, true exactly where the
    // SECOM subsystem runs, so the rail entry and overview card appear only on a
    // node that actually runs it. The route is always registered; on a node
    // without the subsystem the page itself explains that rather than rendering
    // five broken panels. No help section: nothing about SECOM or S-124 is in the
    // user guides yet, so no help link shows here.
    {
      ...section('secom', 'lucideShield'),
      settingGate: 'secomEnabled',
    },
    section('settings', 'lucideSettings', 'settings'),
    { ...section('batch', 'lucideServer', 'batch-jobs'), hasBatchBadge: true },
  ],
};
