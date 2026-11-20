import { Component, inject } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { TranslocoPipe } from '@jsverse/transloco';

import { Icon } from '../../../shared/icons/icon';
import { Toast } from '../../../shared/components/toast/toast';
import { AuthFacade } from '../../auth/facades/auth.facade';
import { DomainBootstrapBanner } from '../../domain/components/domain-bootstrap-banner/domain-bootstrap-banner';
import { UpdateBanner } from '../../version/update-banner';
import { Footer } from '../footer/footer';
import { Header } from '../header/header';

@Component({
  selector: 'app-main-layout',
  imports: [
    Header,
    Footer,
    RouterOutlet,
    Toast,
    Icon,
    TranslocoPipe,
    UpdateBanner,
    DomainBootstrapBanner,
  ],
  templateUrl: './layout.html',
  styleUrl: './layout.css',
})
export class Layout {
  protected readonly auth = inject(AuthFacade);
}
