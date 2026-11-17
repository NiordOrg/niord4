import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';

import { DomainFacade } from '../domain/facades/domain.facade';
import { isNiordBackendRequest } from './backend-prefixes';

export const niordDomainInterceptor: HttpInterceptorFn = (req, next) => {
  if (!isNiordBackendRequest(req.url)) {
    return next(req);
  }

  const session = inject(DomainFacade).sessionDomain();
  if (!session) {
    return next(req);
  }

  return next(req.clone({ setHeaders: { NiordDomain: session.domainId } }));
};
