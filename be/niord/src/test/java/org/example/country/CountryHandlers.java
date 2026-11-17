/*
 * Copyright 2026 Danish Maritime Authority.
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
package org.example.country;

import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Handler classes of a country, for the guard's handler check. Plain classes, not controllers: the tests make their
 * {@code HandlerMethod}s by hand.
 */
public final class CountryHandlers {

    private CountryHandlers() {
    }

    /** No annotation on the method or the class: public by omission */
    public static class Unannotated {
        @GetMapping("/rest/country/unannotated")
        public String unannotated() {
            return "";
        }
    }

    /** The handlers a subclass inherits from a class without an annotation */
    public static class UnannotatedBase {
        @GetMapping("/rest/country/inherited")
        public String inherited() {
            return "";
        }
    }

    /**
     * The class annotation does not cover an inherited handler: Spring Security takes the class annotation from the
     * class declaring the method, and {@link UnannotatedBase} has none.
     */
    @RolesAllowed("admin")
    public static class InheritsUnannotated extends UnannotatedBase {
        @GetMapping("/rest/country/own")
        public String own() {
            return "";
        }
    }

    /** Two annotations on one method: resolution fails, as it does at runtime */
    public static class Competing {
        @GetMapping("/rest/country/competing")
        @RolesAllowed("admin")
        @PermitAll
        public String competing() {
            return "";
        }
    }

    /** An annotated handler that allows CORS: refused, since CORS is configured nowhere */
    @CrossOrigin
    @PermitAll
    public static class CrossOriginHandler {
        @GetMapping("/rest/country/cross-origin")
        public String crossOrigin() {
            return "";
        }
    }

    /** An annotated handler: accepted */
    @RolesAllowed("user")
    public static class Annotated {
        @GetMapping("/rest/country/annotated")
        public String annotated() {
            return "";
        }
    }
}
