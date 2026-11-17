import { EDITOR_AREA_ROLES, type CountryMenuEntry } from '@niord/admin/api';

/** The way to the harbour notes, after Messages, for those who may open them. */
export const COUNTRY_MENU_ENTRIES: readonly CountryMenuEntry[] = [
  {
    after: 'messages',
    labelKey: 'country.harbour.menu',
    icon: 'lucideBook',
    routerLink: 'harbour',
    roles: EDITOR_AREA_ROLES,
  },
];
