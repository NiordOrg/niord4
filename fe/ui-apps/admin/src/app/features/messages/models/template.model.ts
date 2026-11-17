import type { CategoryRef } from './message.model';

/**
 * Message-template model — a "template" is a category whose `type === 'TEMPLATE'` carrying
 * FreeMarker/JS `scriptResourcePaths`, the `stdTemplateFields` its execute form shows, and its
 * custom `templateParams`. Shared here (the messages model hub) so both the editor's execute flow
 * and the admin param-type authoring can reference one set of shapes.
 */

export type ParamTypeKind = 'STANDARD' | 'LIST' | 'COMPOSITE';

export interface TemplateParamDesc {
  readonly lang?: string;
  readonly name?: string;
}

/** One parameter of a template (or of a COMPOSITE param-type). `type` references a {@link ParamType.name}. */
export interface TemplateParam {
  readonly paramId: string;
  readonly type: string;
  readonly mandatory?: boolean;
  readonly positionList?: boolean;
  readonly descs?: readonly TemplateParamDesc[];
}

/** A LIST param-type value (a dictionary entry) — the whole object is stored as the chosen value. */
export interface TemplateListValue {
  readonly id?: number;
  readonly key: string;
  readonly descs?: readonly { readonly lang?: string; readonly value?: string }[];
}

/** A parameter type from `GET /templates/parameter-types` — STANDARD (by `name`), LIST or COMPOSITE. */
export interface ParamType {
  readonly id?: number;
  readonly type: ParamTypeKind;
  readonly name: string;
  /** LIST only — the selectable dictionary values. */
  readonly values?: readonly TemplateListValue[];
  /** COMPOSITE only — the nested parameters. */
  readonly templateParams?: readonly TemplateParam[];
}

/** Full TEMPLATE-category detail: the thin {@link CategoryRef} plus the execution metadata. */
export interface SystemCategory extends CategoryRef {
  readonly type?: 'CATEGORY' | 'TEMPLATE';
  readonly scriptResourcePaths?: readonly string[];
  readonly stdTemplateFields?: readonly string[];
  readonly templateParams?: readonly TemplateParam[];
  /** The template's example message id (sample preview — E4b). */
  readonly messageId?: string;
}

/**
 * The opaque per-template value map sent as one element of the execute `templateParams` list:
 * custom params keyed by `paramId`, plus the `posDecimals` precision and derived `positions`.
 */
export interface TemplateParamValues {
  readonly posDecimals?: string;
  readonly positions?: readonly unknown[];
  readonly [paramId: string]: unknown;
}
