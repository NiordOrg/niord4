import { map, of, switchMap, type Observable } from 'rxjs';

import type { CategoryRef } from '../../../messages/models/messages-filter-params.model';
import type { SystemCategory } from '../../../messages/models/template.model';
import type { CategoriesApiService } from '../../../messages/services/api/categories-api.service';

/**
 * Shared data recipe behind the two template-picking surfaces (the selection DRAWER and the
 * Start screen's search POPOVER): the search → hydrate pipeline plus the small category-display
 * helpers both render with. Keeping it here means a fix to the hydrate merge or the desc-name
 * fallback lands in both pickers at once.
 */

/** The category's name in `lang`, falling back to the first desc. */
export function categoryName(cat: CategoryRef, lang: string): string {
  const descs = cat.descs;
  return descs?.find((desc) => desc.lang === lang)?.name ?? descs?.[0]?.name ?? '';
}

/** Whether the template carries a per-position param (the "per position" list tag). */
export function isPerPosition(cat: SystemCategory): boolean {
  return (cat.templateParams ?? []).some((param) => param.positionList);
}

/** Preserve search order, replacing each thin ref with its hydrated detail when available. */
export function mergeDetails(
  cats: readonly SystemCategory[],
  details: readonly SystemCategory[],
): SystemCategory[] {
  const byId = new Map(details.map((detail) => [detail.id, detail]));
  return cats.map((cat) => byId.get(cat.id) ?? cat);
}

/**
 * Search the domain's TEMPLATE categories and hydrate every hit with its detail
 * (`stdTemplateFields` / `templateParams` / example `messageId`) — the list both pickers render.
 */
export function fetchHydratedTemplates(
  categoriesApi: CategoriesApiService,
  options: { domainId?: string; language: string; name?: string; ancestorId?: number },
): Observable<SystemCategory[]> {
  return categoriesApi.searchTemplates(options).pipe(
    switchMap((cats) =>
      cats.length === 0
        ? of([] as SystemCategory[])
        : categoriesApi
            .getTemplateDetails(
              cats.map((cat) => cat.id),
              { language: options.language },
            )
            .pipe(map((details) => mergeDetails(cats, details))),
    ),
  );
}
