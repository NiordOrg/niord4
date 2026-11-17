/*
 * Copyright 2016 Danish Maritime Authority.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.niord.core.user;

import org.niord.model.DataFilter;
import org.springframework.security.oauth2.jwt.Jwt;

import java.security.Principal;

/**
 * Within the current transaction, this class can be used to add information about
 * the current user to a DataFilter
 */
public class CurrentTransactionUserResolver implements DataFilter.UserResolver {

    private final UserService userService;
    private final CurrentUserContext currentUserContext;

    /**
     * Constructor
     * @param userService a reference to the user service
     * @param currentUserContext a reference to the current-user security context
     */
    public CurrentTransactionUserResolver(UserService userService, CurrentUserContext currentUserContext) {
        this.userService = userService;
        this.currentUserContext = currentUserContext;
    }

    /** {@inheritDoc} **/
    @Override
    public Principal getPrincipal() {
        try {
            // PORT: getCallerPrincipal() now returns a Spring Security Jwt, which does not
            // PORT: implement java.security.Principal. Wrap it so Principal.getName() yields the
            // PORT: same value (preferred_username) the OidcJwtCallerPrincipal exposed.
            Jwt jwt = currentUserContext.getCallerPrincipal();
            if (jwt != null) {
                String name = jwt.getClaimAsString("preferred_username");
                return () -> name;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** {@inheritDoc} **/
    @Override
    public boolean isUserInRole(String role) {
        try {
            return userService.isCallerInRole(role);
        } catch (Exception ignored) {
        }
        return false;
    }
}
