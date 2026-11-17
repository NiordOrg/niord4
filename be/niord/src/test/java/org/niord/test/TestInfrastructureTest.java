/*
 * Copyright 2024 Danish Maritime Authority.
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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Minimal unit-level smoke test proving that the JUnit 5 (Jupiter) engine runs under Surefire
 * during {@code mvn test}. It deliberately exercises no Niord business logic and starts no
 * containers, and is named {@code *Test} so Surefire (not Failsafe) runs it.
 * <p>
 * The pre-existing JUnit 4 unit tests in this module continue to run via the JUnit Vintage engine;
 * this Jupiter test proves both engines coexist after the test-infrastructure changes.
 */
class TestInfrastructureTest {

    @Test
    void surefireRunsJupiterTests() {
        assertEquals(4, 2 + 2, "a basic Jupiter assertion should pass under Surefire");
    }

    @Test
    void namingConventionIsConsistent() {
        // Documents the convention enforced by the Surefire/Failsafe configuration:
        //   *Test   -> unit test, run by Surefire during `mvn test`
        //   *ITTest -> integration test, run by Failsafe during `mvn verify`
        String unitName = getClass().getSimpleName();
        assertTrue(unitName.endsWith("Test"), "unit tests end with 'Test'");
        assertFalse(unitName.endsWith("ITTest"), "a unit test must not end with 'ITTest'");
        assertTrue("TestInfrastructureITTest".endsWith("ITTest"), "integration tests end with 'ITTest'");
    }
}
