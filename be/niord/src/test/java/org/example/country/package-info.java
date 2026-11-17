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
/**
 * Fixtures of the country contract's start-up guard (CountryContractGuardTest, CountryContractStartupTest): a
 * country's configuration, beans and controllers as the guard sees them.
 *
 * <p>Deliberately outside {@code org.niord}: the integration tests' application ({@code NiordTestApplication}) scans
 * {@code org.niord}, test classes included, and would register a fixture configuration or controller in every
 * integration test. Nothing scans this package; each test names the fixtures it starts. They ship in niord-base's
 * test-jar with the tests, so a country build runs them too.
 */
package org.example.country;
