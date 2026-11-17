export interface NamedFilter {
  readonly id: number;
  readonly name: string;
  readonly parameters: string;
}

export interface NamedFilterCreatePayload {
  readonly name: string;
  readonly parameters: string;
}
