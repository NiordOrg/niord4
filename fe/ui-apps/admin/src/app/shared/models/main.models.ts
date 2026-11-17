export interface LanguageOption {
  readonly label: string;
  readonly value: string;
  readonly locale: string;
  /** Absent when the build ships no flag for the language; it is then shown as its code. */
  readonly flagPath?: string;
}
