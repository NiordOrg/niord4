/**
 * The publications the site offers for download. `readonly` throughout: the rows the table
 * renders are built beside the fetched list, never written onto it.
 */

/** The one publication type the site treats differently: it can be browsed as a message list. */
export const PUBLICATION_TYPE_MESSAGE_REPORT = 'MESSAGE_REPORT';

/** A category's text in one language: its name is the heading drawn above its publications. */
export interface PublicationCategoryDesc {
  readonly lang: string;
  readonly name?: string;
  readonly description?: string;
}

export interface PublicationCategory {
  readonly categoryId: string;
  readonly descs?: readonly PublicationCategoryDesc[];
}

/** A publication's text in one language: the title the table shows and the file it links to. */
export interface PublicationDesc {
  readonly lang: string;
  readonly title?: string;
  readonly link?: string;
}

export interface Publication {
  readonly publicationId: string;
  /** A raw wire value; `PUBLICATION_TYPE_MESSAGE_REPORT` is the one the table acts on. */
  readonly type?: string;
  readonly category?: PublicationCategory;
  readonly descs?: readonly PublicationDesc[];
}
