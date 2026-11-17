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
import org.niord.core.conf.CountryContractGuard;
import org.niord.core.script.ScriptResourceCheck;
import org.niord.test.CountryBuildGroup;
import org.niord.test.CountryBuildGroupTest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code be/docs/country-api.md}, the backend contract for a country, names what the code enforces: every type a
 * script may look up ({@link ScriptResourceCheck#ALLOWED_BEAN_TYPES}), every protected bean type, property and class
 * of the start-up guard ({@link CountryContractGuard}), every member of the country build group and every rule of the
 * override check ({@code be/k8s/scripts/CheckOverrides.java}). A name the code adds without the document fails here.
 * Three lists the document gives in a form that can be read are held to the code both ways, so that a name the code
 * drops fails too: the backticked types of the "Protected beans" sentence against
 * {@link CountryContractGuard#PROTECTED_BEAN_TYPES}, the first column of the protected-property table against
 * {@link CountryContractGuard#PROTECTED_PROPERTIES}, and the rules of the Overrides section against the override
 * check's rule constants. The rest of the document (the reasons, the dependency versions, the prose) is not checked.
 * Not in the country build group: it reads this repository's tree.
 */
class CountryApiDocTest {

    static final Path DOC = Path.of("../docs/country-api.md");
    static final Path CHECK = Path.of("../k8s/scripts/CheckOverrides.java");

    @Test
    void theDocumentNamesTheScriptAllowList() throws IOException {
        assertNamed(ScriptResourceCheck.ALLOWED_BEAN_TYPES);
    }

    @Test
    void theDocumentNamesEveryProtectedBeanTypeAndClass() throws IOException {
        List<String> names = new ArrayList<>();
        CountryContractGuard.PROTECTED_BEAN_TYPES.forEach(t -> names.add(t.getSimpleName()));
        CountryContractGuard.METHOD_SECURITY_BEAN_TYPES.forEach(t -> names.add(t.getSimpleName()));
        CountryContractGuard.CORS_BEAN_TYPES.forEach(t -> names.add(t.getSimpleName()));
        CountryContractGuard.METHOD_SECURITY_ANNOTATIONS.forEach(a -> names.add("@" + simple(a)));
        names.add("@" + simple(CountryContractGuard.PROPERTY_SOURCE_ANNOTATIONS.getFirst()));
        CountryContractGuard.PROTECTED_CLASSES.forEach(c -> names.add(simple(c)));
        CountryContractGuard.REVIEWED_FRAMEWORK_HANDLERS.forEach(c -> names.add(simple(c)));
        names.add("JwtDecoder");
        names.add(simple(CountryContractGuard.JWT_DECODER_CONFIGURATION));
        names.add("@CrossOrigin");
        names.add("addCorsMappings");

        assertNamed(names);
    }

    @Test
    void theDocumentNamesEveryProtectedPropertyRule() throws IOException {
        List<String> names = new ArrayList<>(CountryContractGuard.PROTECTED_PROPERTIES);
        names.add(CountryContractGuard.ISSUER_URI);
        names.add(CountryContractGuard.ALLOWED_CLIENTS_REASON);

        assertNamed(names);
    }

    @Test
    void theDocumentNamesTheCountryBuildGroup() throws IOException {
        List<String> names = new ArrayList<>(CountryBuildGroupTest.MEMBERS.stream().map(CountryApiDocTest::simple)
                .toList());
        names.add(CountryBuildGroup.TAG);
        names.add("niord-country-build-it");

        assertNamed(names);
    }

    @Test
    void theDocumentNamesEveryRuleOfTheOverrideCheck() throws IOException {
        List<String> rules = overrideCheckRules();
        assertEquals(7, rules.size(), "the override check's rules in " + CHECK + ": " + rules);

        assertNamed(rules.stream().map(r -> "`" + r + "`").toList());
    }

    // ------------------------------------------------------------------------------------------- //
    // The lists held both ways
    // ------------------------------------------------------------------------------------------- //

    @Test
    void theProtectedBeansSentenceListsExactlyTheProtectedBeanTypes() throws IOException {
        String paragraph = paragraph("**Protected beans.**");
        int from = paragraph.indexOf("nor add a second:");
        assertTrue(from >= 0, "the Protected beans paragraph lists the types after 'nor add a second:'");
        String sentence = paragraph.substring(from, paragraph.indexOf(". ", from));

        assertSameNames("the Protected beans sentence", backticked(sentence),
                CountryContractGuard.PROTECTED_BEAN_TYPES.stream().map(Class::getSimpleName).toList());
    }

    @Test
    void thePropertyTableListsExactlyTheProtectedProperties() throws IOException {
        String doc = readDoc();
        int table = doc.indexOf("| Property | Rule |");
        assertTrue(table >= 0, "the protected-property table");
        List<String> firstColumn = new ArrayList<>();
        for (String row : doc.substring(table, doc.indexOf("\n\n", table)).split("\n")) {
            String[] cells = row.split("\\|");
            if (cells.length > 1 && !row.startsWith("| Property") && !row.startsWith("|---")) {
                firstColumn.addAll(backticked(cells[1]));
            }
        }

        assertSameNames("the first column of the protected-property table", firstColumn,
                CountryContractGuard.PROTECTED_PROPERTIES);
    }

    @Test
    void theOverridesSectionListsExactlyTheOverrideChecksRules() throws IOException {
        String doc = readDoc();
        int from = doc.indexOf("- The rules, each its own finding:");
        assertTrue(from >= 0, "the Overrides section's rule list");
        String bullet = doc.substring(from, doc.indexOf("\n- ", from + 1));
        List<String> named = new ArrayList<>();
        Matcher rule = Pattern.compile("`([a-z][\\w-]*)`\\s+\\(").matcher(bullet);
        while (rule.find()) {
            named.add(rule.group(1));
        }

        assertSameNames("the Overrides section's rule list", named, overrideCheckRules());
    }

    /** The paragraph that starts with the given text, up to the next blank line */
    private static String paragraph(String start) throws IOException {
        String doc = readDoc();
        int from = doc.indexOf(start);
        assertTrue(from >= 0, DOC + " has no paragraph starting " + start);
        int to = doc.indexOf("\n\n", from);
        return doc.substring(from, to < 0 ? doc.length() : to);
    }

    /** The document, with the line ends a checkout on Windows may give it made plain */
    private static String readDoc() throws IOException {
        return Files.readString(DOC, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    private static List<String> backticked(String text) {
        List<String> names = new ArrayList<>();
        Matcher name = Pattern.compile("`([^`]+)`").matcher(text);
        while (name.find()) {
            names.add(name.group(1));
        }
        return names;
    }

    private static void assertSameNames(String where, List<String> documented, List<String> code) {
        Set<String> onlyInDoc = new TreeSet<>(documented);
        code.forEach(onlyInDoc::remove);
        Set<String> onlyInCode = new TreeSet<>(code);
        documented.forEach(onlyInCode::remove);
        assertTrue(onlyInDoc.isEmpty() && onlyInCode.isEmpty(), where + " in " + DOC + " and the code differ: "
                + "only in the document " + onlyInDoc + ", only in the code " + onlyInCode);
    }

    /** The rule constants of the override check */
    private static List<String> overrideCheckRules() throws IOException {
        List<String> rules = new ArrayList<>();
        Matcher rule = Pattern.compile("public static final String \\w+ = \"([\\w-]+)\";")
                .matcher(Files.readString(CHECK, StandardCharsets.UTF_8));
        while (rule.find()) {
            rules.add(rule.group(1));
        }
        return rules;
    }

    private static void assertNamed(List<String> names) throws IOException {
        String doc = readDoc();
        List<String> missing = names.stream().filter(name -> !doc.contains(name)).toList();
        assertTrue(missing.isEmpty(), DOC + " does not name " + missing
                + "; a change to the country contract changes the document with it (and its Changes section)");
    }

    private static String simple(String className) {
        return className.substring(className.lastIndexOf('.') + 1);
    }
}
