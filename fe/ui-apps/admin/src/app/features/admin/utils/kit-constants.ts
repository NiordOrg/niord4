/**
 * Debounce cadence for admin text filters that drive SERVER searches: commit
 * after 1s of idle typing, immediately on blur. Client-side filters (catalog
 * sections) filter per keystroke and don't use this.
 */
export const ADMIN_TEXT_FILTER_DEBOUNCE_MS = 1000;

/**
 * Rows per page for the admin/sysadmin tables — both the client-paged catalog
 * lists and the server-paged mail queue. Shared so every section paginates
 * consistently; the batch job queue intentionally differs (5) and keeps its own
 * local constant.
 */
export const ADMIN_PAGE_SIZE = 20;
