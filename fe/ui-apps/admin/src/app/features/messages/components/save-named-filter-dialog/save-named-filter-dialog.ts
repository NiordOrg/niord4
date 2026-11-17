import { Component, computed, DestroyRef, inject, model, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { TranslocoPipe } from '@jsverse/transloco';

import { Dialog } from '../../../../shared/components/dialog/dialog';
import { NamedFiltersFacade } from '../../services/named-filters.facade';

@Component({
  selector: 'app-save-named-filter-dialog',
  templateUrl: './save-named-filter-dialog.html',
  imports: [Dialog, FormsModule, TranslocoPipe],
})
export class SaveNamedFilterDialog {
  private readonly facade = inject(NamedFiltersFacade);
  private readonly destroyRef = inject(DestroyRef);

  readonly visible = model<boolean>(false);
  readonly name = model<string>('');
  readonly saving = signal(false);
  readonly saveError = signal<unknown | undefined>(undefined);
  readonly canSave = computed(() => this.name().trim().length > 0 && !this.saving());

  onSave(): void {
    if (!this.canSave()) return;
    this.saving.set(true);
    this.facade
      .save(this.name())
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.saving.set(false);
          this.saveError.set(undefined);
          this.name.set('');
          this.visible.set(false);
        },
        error: (err: unknown) => {
          this.saving.set(false);
          this.saveError.set(err);
        },
      });
  }

  onCancel(): void {
    this.reset();
    this.visible.set(false);
  }

  onHide(): void {
    this.reset();
  }

  private reset(): void {
    this.name.set('');
    this.saveError.set(undefined);
  }
}
