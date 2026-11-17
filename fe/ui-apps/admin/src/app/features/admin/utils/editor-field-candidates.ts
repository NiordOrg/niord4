/**
 * The additional-editor-field candidates an area/category/series can force-enable: only the
 * fields that are OFF by default in the deployment's `editorFieldsBase` config. The always-shown
 * core fields (type/id/title/references/areas/categories/positions/subject/description/
 * publish_date) can't be force-enabled, so they are excluded. Order + membership track the
 * `editorFieldsBase` `false` keys; labels come from `msg.field.<id>`.
 */
export const EDITOR_FIELD_CANDIDATES: readonly string[] = [
  'orig_info',
  'charts',
  'event_dates',
  'attachments',
  'source',
  'part_types',
  'prohibition',
  'signals',
  'layout',
];
