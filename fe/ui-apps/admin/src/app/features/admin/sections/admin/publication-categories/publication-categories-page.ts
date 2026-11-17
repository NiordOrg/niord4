import { Component } from '@angular/core';

import { AdminSectionPage } from '../../../shell/admin-section-page';
import { PublicationCategoriesTab } from '../publications/categories-tab';

/**
 * Publication categories, as a section of their own.
 *
 * They used to be the second tab of the publications dashboard, which put a
 * screen about configuration behind a screen about this week's work and gave the
 * section two headings competing for the same page. A category is not part of a
 * series — it is what a series points at, shared across all of them, and it
 * carries the publish flag that decides whether a whole group of publications
 * reaches the public site. That belongs beside Publications in the rail, not
 * inside it.
 *
 * The page is chrome only: the list, its editor and its import/export live in
 * the projected component, which is unchanged by the move.
 */
@Component({
  selector: 'app-publication-categories-page',
  imports: [AdminSectionPage, PublicationCategoriesTab],
  template: `
    <app-admin-section-page
      titleKey="admin.sections.publication-categories.label"
      descriptionKey="admin.sections.publication-categories.description"
    >
      <app-publication-categories-tab />
    </app-admin-section-page>
  `,
  host: { class: 'flex min-w-0 flex-1 flex-col' },
})
export class PublicationCategoriesPage {}
