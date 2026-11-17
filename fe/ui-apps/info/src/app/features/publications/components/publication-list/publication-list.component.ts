import { Component, input } from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';
import { RouterLink } from '@angular/router';
import { TranslocoPipe } from '@jsverse/transloco';
import { PUBLICATION_TYPE_MESSAGE_REPORT } from '../../../../core/models/publication.model';
import { PublicationRow } from '../../../../core/models/publication.util';
import { Icon } from '../../../../shared/icons/icon';
import { ContentLang } from '../../../../shared/directives/content-lang';

@Component({
  selector: 'app-publication-list',
  imports: [NgTemplateOutlet, RouterLink, TranslocoPipe, Icon, ContentLang],
  // A real three-column table: title, download, browse. Its header row is there for a screen
  // reader and hidden from the page — the site has always shown the columns without headings,
  // and the three words it needs are keys the bundles already carry. The title is the row's
  // header, so each Download and Browse link is announced with the publication it belongs to;
  // the cell keeps the look of the data cells beside it.
  //
  // Below the sm width the two link columns, which do not wrap, would push the table past a
  // 320 px screen (WCAG 1.4.10, Reflow), so there the links stand under the title, inside the
  // row's header cell, and the two link cells and their column headers are not drawn: a table of
  // one column, whose header cell takes the whole width (a category heading still spans three
  // columns, and the two empty ones must not take any of it). From sm up the copy under the
  // title is not drawn. Each copy that is not drawn is display:none, which also takes it out of
  // what a screen reader reads, so a link is met once. The table itself keeps its display, and
  // with it its semantics.
  template: `
    <ng-template #download let-pub>
      @if (pub.descs?.[0]?.link; as link) {
        <a class="link-button is-subtle" [href]="link" target="_blank" rel="noopener">
          <app-icon name="lucideDownload" class="mr-1 align-middle" />
          {{ 'OPEN_PUBLICATION' | transloco }}
        </a>
      }
    </ng-template>
    <ng-template #browse let-pub>
      @if (pub.type === messageReportType) {
        <a
          class="link-button is-subtle"
          [routerLink]="['/messages/details']"
          [queryParams]="{ publicationId: pub.publicationId }"
        >
          <app-icon name="lucideList" class="mr-1 align-middle" />
          {{ 'BROWSE_PUBLICATION' | transloco }}
        </a>
      }
    </ng-template>

    <table class="niord-table w-full">
      <thead class="sr-only">
        <tr>
          <th scope="col">{{ 'MENU_PUBLICATIONS' | transloco }}</th>
          <th scope="col" class="hidden sm:table-cell">{{ 'OPEN_PUBLICATION' | transloco }}</th>
          <th scope="col" class="hidden sm:table-cell">{{ 'BROWSE_PUBLICATION' | transloco }}</th>
        </tr>
      </thead>
      <tbody>
        @for (row of rows(); track row.publication.publicationId) {
          @let pub = row.publication;
          @if (row.heading; as heading) {
            <tr>
              <td colspan="3" class="border-0">
                <h4
                  class="publication-category-heading"
                  [appContentLang]="heading.descs?.[0]?.lang"
                >
                  {{ heading.descs?.[0]?.name }}
                </h4>
              </td>
            </tr>
          }
          <tr class="group">
            <th
              scope="row"
              class="w-full border-b border-[var(--niord-divider)] px-3 py-2 text-left align-middle font-normal text-fg group-last:border-b-0 sm:w-auto"
            >
              <!-- The title alone carries the content's language; the links are the page's. -->
              <span [appContentLang]="pub.descs?.[0]?.lang">
                <app-icon name="lucideBook" class="mr-1 align-middle" />
                {{ pub.descs?.[0]?.title }}
              </span>
              @if (pub.descs?.[0]?.link || pub.type === messageReportType) {
                <div class="mt-2 flex flex-wrap gap-x-5 gap-y-3 sm:hidden">
                  <ng-container
                    [ngTemplateOutlet]="download"
                    [ngTemplateOutletContext]="{ $implicit: pub }"
                  />
                  <ng-container
                    [ngTemplateOutlet]="browse"
                    [ngTemplateOutletContext]="{ $implicit: pub }"
                  />
                </div>
              }
            </th>
            <td class="hidden w-25 whitespace-nowrap align-middle sm:table-cell">
              <ng-container
                [ngTemplateOutlet]="download"
                [ngTemplateOutletContext]="{ $implicit: pub }"
              />
            </td>
            <td class="hidden w-25 whitespace-nowrap align-middle sm:table-cell">
              <ng-container
                [ngTemplateOutlet]="browse"
                [ngTemplateOutletContext]="{ $implicit: pub }"
              />
            </td>
          </tr>
        }
      </tbody>
    </table>
  `,
})
export class PublicationListComponent {
  rows = input<readonly PublicationRow[]>([]);

  protected readonly messageReportType = PUBLICATION_TYPE_MESSAGE_REPORT;
}
