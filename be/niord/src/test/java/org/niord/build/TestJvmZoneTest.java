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
package org.niord.build;

import org.junit.jupiter.api.Test;

import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The unit-test JVM runs in UTC, as the deployments do (the {@code argLine} property of {@code be/pom.xml}): in a zone
 * with summer time the hour the clocks go back is stored one hour off, and time-dependent tests failed in it.
 * {@link TestJvmZoneITTest} holds the integration-test JVM to the same.
 */
public class TestJvmZoneTest {

    @Test
    void theTestJvmRunsInUtc() {
        assertEquals("UTC", System.getProperty("user.timezone"), "the -Duser.timezone=UTC of the argLine");
        assertEquals(0, TimeZone.getDefault().getRawOffset());
        assertEquals(false, TimeZone.getDefault().useDaylightTime(), TimeZone.getDefault().getID());
    }
}
