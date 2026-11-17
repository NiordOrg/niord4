export const PAPER_SIZES = ['A3', 'A4', 'A5', 'B4', 'B5', 'letter', 'legal', 'ledger'] as const;

export type PaperSize = (typeof PAPER_SIZES)[number];

export type PageOrientation = 'portrait' | 'landscape';

export interface PrintSettings {
  readonly language?: string;
  readonly report?: string;
  readonly pageSize: PaperSize;
  readonly pageOrientation: PageOrientation;
  readonly mapThumbnails: boolean;
}

export type ReportParams = Readonly<Record<string, unknown>>;

export const DEFAULT_PRINT_SETTINGS: PrintSettings = {
  pageSize: 'A4',
  pageOrientation: 'portrait',
  mapThumbnails: false,
};
