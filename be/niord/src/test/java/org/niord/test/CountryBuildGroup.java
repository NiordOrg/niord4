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
package org.niord.test;

import org.junit.jupiter.api.Tag;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a test of niord-base that also runs in every country build: the security tests and the country checks of
 * the country contract (docs/fe-country-split-plan.md §4.11 rule 3, be/docs/country-api.md).
 *
 * <p>The marker is the JUnit tag {@value #TAG}. niord-parent ({@code be/pom.xml}) has two executions that select it,
 * {@code niord-country-build} (Surefire) and {@code niord-country-build-it} (Failsafe): each scans niord-base's test-jar
 * and runs only the tagged classes there. A country inherits them with the parent; niord-base, niord-app and
 * niord-bootstrap switch them off ({@code niord.countryGroup.skip}), since niord-base runs these tests in its own
 * suites. {@code CountryBuildGroupTest} lists the members, so adding or removing one is a deliberate change.
 *
 * <p>A member reads nothing outside its class path, since a country build has only niord-base's test-jar; its
 * configuration and controller fixtures live outside {@code org.niord} ({@code org.example.country}), where the
 * integration tests' application does not scan.
 *
 * <p>The name does not end in {@code Test}: Surefire's include pattern {@code **}{@code /*Test.java} would take an
 * annotation type for a test class.
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Tag(CountryBuildGroup.TAG)
public @interface CountryBuildGroup {

    /** The JUnit tag niord-parent's country build executions select */
    String TAG = "niord-country-build";
}
