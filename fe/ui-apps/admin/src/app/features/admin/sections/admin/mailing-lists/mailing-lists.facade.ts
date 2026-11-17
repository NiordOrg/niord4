import { Injectable, computed, inject, signal } from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { catchError, map, of, startWith, switchMap } from 'rxjs';

import { LanguageService } from '../../../../../core/main-shell/services/language.service';
import { MailingListsApiService } from '../../../services/api/mailing-lists-api.service';
import type { MailingList } from './mailing-list.model';

interface MailingListsState {
  readonly mailingLists: readonly MailingList[];
  readonly loading: boolean;
  readonly error: boolean;
}

const INITIAL: MailingListsState = { mailingLists: [], loading: true, error: false };

/**
 * Page-scoped list state for the Mailing Lists section. The fetch trigger includes the selected
 * UI language (the list endpoint lang-resolves the desc names server-side), so a language switch
 * refetches; mutations bump `refresh()` to refetch after create/update/delete/import and after a
 * recipient save (which changes the recipient count).
 */
@Injectable()
export class MailingListsFacade {
  private readonly api = inject(MailingListsApiService);
  private readonly language = inject(LanguageService);

  private readonly refreshTick = signal(0);
  private readonly trigger = computed(() => ({
    lang: this.language.selectedLanguage(),
    tick: this.refreshTick(),
  }));

  private readonly state = toSignal(
    toObservable(this.trigger).pipe(
      switchMap(({ lang }) =>
        this.api.getAllDetailed(lang).pipe(
          map(
            (mailingLists): MailingListsState => ({ mailingLists, loading: false, error: false }),
          ),
          catchError(() =>
            of<MailingListsState>({ mailingLists: [], loading: false, error: true }),
          ),
          startWith(INITIAL),
        ),
      ),
    ),
    { initialValue: INITIAL },
  );

  readonly mailingLists = computed(() => this.state().mailingLists);
  readonly loading = computed(() => this.state().loading);
  readonly error = computed(() => this.state().error);

  refresh(): void {
    this.refreshTick.update((tick) => tick + 1);
  }
}
