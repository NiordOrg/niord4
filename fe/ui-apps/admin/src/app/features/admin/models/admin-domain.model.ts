import type {
  Domain,
  DomainAreaRef,
  DomainCategoryRef,
} from '../../../core/domain/models/domain.model';

/** A localized name row on the domain's area/category refs (sorted server-side by `?lang=`). */
export interface AdminDomainEntityDesc {
  readonly lang: string;
  readonly name?: string;
}

/** An area bound to the domain — display via `descs[0].name`; saves send minimal `{ id }` refs. */
export interface AdminDomainAreaRef extends DomainAreaRef {
  readonly active?: boolean;
  readonly descs?: readonly AdminDomainEntityDesc[];
}

/** A category bound to the domain — same display/save contract as the area refs. */
export interface AdminDomainCategoryRef extends DomainCategoryRef {
  readonly active?: boolean;
  readonly descs?: readonly AdminDomainEntityDesc[];
}

/**
 * The writable admin view of a domain (the backend `DomainVo`, fetched with
 * `?inactive=true`) — the core session `Domain` shape plus the admin-only
 * Keycloak state, with the area/category refs widened to carry the localized
 * names the sysadmin list renders. The core model stays untouched: the session
 * catalog never sees these fields.
 */
export interface AdminDomain extends Domain {
  /**
   * Whether a Keycloak client exists for the domain. Tri-state: `true` = the
   * client exists; `false`/`null`/absent = missing OR the backend could not
   * reach Keycloak (treated alike). Backend-derived and
   * read-only: never sent as an edit; refreshed only by re-fetching the list
   * (the provisioning POST returns no body).
   */
  readonly inKeycloak?: boolean | null;
  readonly areas?: readonly AdminDomainAreaRef[];
  readonly categories?: readonly AdminDomainCategoryRef[];
}
