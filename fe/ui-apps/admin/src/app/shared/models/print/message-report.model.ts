import type { ReportParams } from './print-settings.model';

export interface MessageReportProperties {
  /**
   * When `undefined`, the "Include thumbnails" checkbox is rendered. An explicit
   * boolean (true OR false) hides the checkbox — the value is baked into the report.
   */
  readonly mapThumbnails?: boolean;
}

export interface MessageReport {
  readonly reportId: string;
  readonly name: string;
  readonly properties: MessageReportProperties;
  readonly params: ReportParams;
}
