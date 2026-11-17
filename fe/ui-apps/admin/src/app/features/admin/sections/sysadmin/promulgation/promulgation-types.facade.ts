import { httpResource } from '@angular/common/http';
import { Injectable, computed, inject } from '@angular/core';

import { PromulgationAdminApiService } from '../../../services/api/promulgation-admin-api.service';
import type { AdminPromulgationType, PromulgationServiceRef } from './promulgation-admin.model';

/**
 * Page-scoped list state for Promulgation Types: the type catalog plus the
 * backend service registry that drives the per-service "Add" menu (the base
 * product's services and any a country's backend adds — the frontend never
 * hardcodes the service list). Neither endpoint is lang-parameterized.
 * Mutations refetch the types list only; the registry is static per session and
 * re-requested only from the error-banner retry.
 */
@Injectable()
export class PromulgationTypesFacade {
  private readonly api = inject(PromulgationAdminApiService);

  // value() throws in the resource's error state — always read via hasValue().
  private readonly typesResource = httpResource<readonly AdminPromulgationType[]>(
    () => this.api.typesRequest(),
    { defaultValue: [] },
  );
  private readonly servicesResource = httpResource<readonly PromulgationServiceRef[]>(
    () => this.api.servicesRequest(),
    { defaultValue: [] },
  );

  readonly types = computed<readonly AdminPromulgationType[]>(() =>
    this.typesResource.hasValue() ? this.typesResource.value() : [],
  );
  readonly services = computed<readonly PromulgationServiceRef[]>(() =>
    this.servicesResource.hasValue() ? this.servicesResource.value() : [],
  );
  readonly loading = computed(
    () => this.typesResource.isLoading() || this.servicesResource.isLoading(),
  );
  readonly error = computed(
    () => this.typesResource.status() === 'error' || this.servicesResource.status() === 'error',
  );

  /** Refetch the types list — after every successful mutation. */
  refresh(): void {
    this.typesResource.reload();
  }

  /** Error-banner retry: reload both the catalog and the service registry. */
  retry(): void {
    this.typesResource.reload();
    this.servicesResource.reload();
  }
}
