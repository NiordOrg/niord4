import { Component } from '@angular/core';

import { Layout } from './core/main-shell/layout/layout';
import { DiscardConfirmHost } from './shared/components/confirm/discard-confirm-host';

@Component({
  selector: 'app-root',
  imports: [Layout, DiscardConfirmHost],
  templateUrl: './app.html',
})
export class App {}
