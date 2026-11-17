import { Component, ElementRef, inject, viewChild } from '@angular/core';
import { NgComponentOutlet } from '@angular/common';
import { RouterOutlet } from '@angular/router';
import { TranslocoPipe } from '@jsverse/transloco';
import { MainMenuComponent } from '../main-menu/main-menu.component';
import { DefaultFooterComponent } from '../footer/default-footer.component';
import { NIORD_INFO_CONFIG } from '../../../niord-info.config';
import { SlotOutletComponent } from '../../slots/slot-outlet.component';

/**
 * The page around every listing: the menu, the view the route shows, the country's footer.
 *
 * The view is the page's main landmark, so a screen reader can jump to it past the menu. The
 * first tab stop is a link that does the same for a keyboard, out of sight until it has focus:
 * every card title and reference in a list is a button of its own, and without it the footer
 * is several hundred presses away. The link moves focus itself rather than following its
 * fragment, which would put `#main-content` into the address the router reads. It is drawn
 * above the corner ribbon a deployment that is not production names itself in, which would
 * otherwise cover the start of the link.
 *
 * A country may add a component of its own between the view and the footer through the
 * `layout.beforeFooter` slot; the base product adds none, and the slot then renders nothing.
 */
@Component({
  selector: 'app-layout',
  imports: [RouterOutlet, MainMenuComponent, NgComponentOutlet, SlotOutletComponent, TranslocoPipe],
  template: `
    <a
      href="#main-content"
      class="fixed left-2 top-0 z-10000 -translate-y-full rounded bg-surface-0 px-3 py-2 text-fg focus:translate-y-2"
      (click)="skipToMain($event)"
      >{{ 'SKIP_TO_CONTENT' | transloco }}</a
    >
    <app-main-menu />
    <main #main id="main-content" tabindex="-1" class="outline-none">
      <router-outlet />
    </main>
    <app-slot name="layout.beforeFooter" />
    <ng-container [ngComponentOutlet]="footerComponent" />
  `,
})
export class LayoutComponent {
  readonly footerComponent = inject(NIORD_INFO_CONFIG).footerComponent ?? DefaultFooterComponent;

  private readonly main = viewChild.required<ElementRef<HTMLElement>>('main');

  protected skipToMain(event: Event): void {
    event.preventDefault();
    this.main().nativeElement.focus();
  }
}
