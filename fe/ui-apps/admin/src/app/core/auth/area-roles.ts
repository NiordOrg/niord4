import type { AuthRole } from './facades/auth.facade';

// Same lists are consumed by `roleGuard` (via `data.rolesRequired`) and
// `MenuService.canSee*` so menu visibility tracks route protection.
// Admin/sysadmin appear in EDITOR_AREA_ROLES because the role guard's
// `some()` check requires the full list — there's no implicit hierarchy.
export const EDITOR_AREA_ROLES: readonly AuthRole[] = ['editor', 'admin', 'sysadmin'];

// The editor *shell* route additionally admits the plain `user` role: per the admin manual,
// `user` (e.g. case officers) may open un-published NW/NM messages to proof-read and comment.
// Read access only — every mutating action stays gated on EDITOR_AREA_ROLES / `isEditor`.
export const EDITOR_VIEW_ROLES: readonly AuthRole[] = ['user', 'editor', 'admin', 'sysadmin'];

export const ADMIN_AREA_ROLES: readonly AuthRole[] = ['admin', 'sysadmin'];

// The sysadmin area is a separate top-level nav item and route tree (two
// separate shells), admitting only the sysadmin role.
export const SYSADMIN_AREA_ROLES: readonly AuthRole[] = ['sysadmin'];
