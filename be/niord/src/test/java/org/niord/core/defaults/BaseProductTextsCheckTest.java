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
package org.niord.core.defaults;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The base product's texts pass the text checks ({@link DefaultTextsChecks}, docs/code-data-config-plan.md §4.7).
 *
 * <p>Runs over this module's test class path, main resources included: the base product's
 * {@code defaults/base/texts/en/}, and the test tree's stand-in da pack ({@code src/test/resources/defaults/packs/da/},
 * test data the test-jar excludes) as a country's staged pack; the root-bundle rule looks for root-level files in
 * en and da. The template rule reads the base product's templates in {@code bootstrap/templates/} by path, since
 * they are bootstrap data (C4.1), so removing the temporary resource mapping cannot empty the scan. A unit test, so
 * Backend CI's unit job runs it.
 */
class BaseProductTextsCheckTest {

    /** The base product's templates, bootstrap data since C4.1; the tests run in the module's folder */
    static final Path TEMPLATES = Path.of("bootstrap/templates");

    @Test
    void theBaseProductsTextsPassTheTextChecks() {
        ClassLoader cl = BaseProductTextsCheckTest.class.getClassLoader();

        List<DefaultTextsChecks.Finding> findings = new ArrayList<>(DefaultTextsChecks.checkTexts(cl));
        findings.addAll(DefaultTextsChecks.checkBaseKeys(cl));
        findings.addAll(DefaultTextsChecks.checkTemplates(templatesRoot(), "templates",
                DefaultTextsChecks.baseKeys(cl)));

        assertTrue(findings.isEmpty(), () -> "The default texts break the text checks:\n" + findings.stream()
                .map(DefaultTextsChecks.Finding::toString)
                .collect(Collectors.joining("\n")));
    }

    @Test
    void theChecksReadTheBaseProductsTexts() {
        // Guards against a green run that read nothing
        assertTrue(DefaultTextsChecks.baseKeys(BaseProductTextsCheckTest.class.getClassLoader())
                .contains("lang.en"));
    }

    /** The base product's templates folder, checked to hold a known template so a wrong path cannot pass empty */
    private static Path templatesRoot() {
        assertTrue(Files.isRegularFile(TEMPLATES.resolve("messages/message-support.ftl")),
                TEMPLATES.toAbsolutePath() + " holds the base product's templates");
        return TEMPLATES;
    }
}
