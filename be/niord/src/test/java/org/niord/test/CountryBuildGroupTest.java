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

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.core.type.filter.TypeFilter;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The country build group is what it should be (docs/fe-country-split-plan.md §4.11 rule 3, be/docs/country-api.md):
 * exactly the listed classes carry {@link CountryBuildGroup}, and niord-parent runs them in a country build and only
 * there. Adding a class to the group, or taking one out, changes {@link #MEMBERS} here, deliberately. Not itself in
 * the group: it reads this repository's poms.
 */
public class CountryBuildGroupTest {

    /** The country build group: the security tests, the start-up guard's tests and the country's defaults checks */
    public static final Set<String> MEMBERS = Set.of(
            "org.niord.web.RestControllerSecurityAnnotationTest",
            "org.niord.web.RestHandlerSecurityITTest",
            "org.niord.web.MethodSecurityOrderingITTest",
            "org.niord.core.conf.SecurityConfigJwtTest",
            "org.niord.core.conf.SecurityConfigFrameOptionsTest",
            "org.niord.core.conf.JwtAuthorizedPartyITTest",
            "org.niord.core.conf.WebAppConfigTest",
            "org.niord.core.web.NiordDomainFilterTest",
            "org.niord.core.mail.MailHtmlTest",
            "org.niord.core.script.FmTemplateClassResolverTest",
            "org.niord.core.conf.CountryContractGuardTest",
            "org.niord.core.conf.CountryContractStartupTest",
            "org.niord.core.defaults.CountryDefaultsChecksTest");

    static final String TEST_JAR = "org.niord.base:niord-base:test-jar:tests";
    static final String SKIP = "niord.countryGroup.skip";

    @Test
    void exactlyTheListedClassesAreInTheGroup() {
        TypeFilter tagged = new AnnotationTypeFilter(CountryBuildGroup.class);
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false) {
            /** The include filter alone: test classes are no components */
            @Override
            protected boolean isCandidateComponent(MetadataReader metadataReader) throws IOException {
                return tagged.match(metadataReader, getMetadataReaderFactory());
            }
        };
        scanner.addIncludeFilter(tagged);

        Set<String> found = new TreeSet<>();
        for (String basePackage : List.of("org.niord", "org.example")) {
            for (BeanDefinition candidate : scanner.findCandidateComponents(basePackage)) {
                found.add(candidate.getBeanClassName());
            }
        }

        assertEquals(new TreeSet<>(MEMBERS), found, "the classes tagged @CountryBuildGroup");
    }

    @Test
    void niordParentRunsTheGroupInBothSuites() throws Exception {
        Document parent = pom("../pom.xml");

        Element unit = execution(parent, "maven-surefire-plugin", "niord-country-build");
        assertEquals(List.of("test"), texts(unit, "goals/goal"));
        assertGroupConfiguration(unit);
        assertEquals("false", text(unit, "configuration/redirectTestOutputToFile"),
                "the settings report reaches the Maven log");

        Element integration = execution(parent, "maven-failsafe-plugin", "niord-country-build-it");
        assertEquals(List.of("integration-test", "verify"), texts(integration, "goals/goal"));
        assertGroupConfiguration(integration);
        assertEquals(List.of("**/*ITTest.java"), texts(integration, "configuration/includes/include"));
        assertTrue(text(integration, "configuration/summaryFile")
                        .endsWith("/failsafe-reports/failsafe-summary-niord-country-build.xml"),
                "its own summary file, so the default execution's is not overwritten");
    }

    @Test
    void theGroupIsOnForACountryAndOffInTheBaseProductsModules() throws Exception {
        assertEquals("false", property(pom("../pom.xml"), SKIP), "niord-parent: on for every country");
        for (String module : List.of("../niord/pom.xml", "../niord-app/pom.xml", "../niord-bootstrap/pom.xml")) {
            assertEquals("true", property(pom(module), SKIP), module);
        }

        // The root pom of the base product's own tree switches it off for itself, by a file only that tree has
        Document parent = pom("../pom.xml");
        String marker = text(parent, "/project/profiles/profile[id='niord-base-reactor']/activation/file/exists");
        assertEquals("${basedir}/niord-bootstrap/src/assembly/bootstrap.xml", marker);
        assertTrue(Path.of("..").resolve(marker.substring("${basedir}/".length())).toFile().isFile(), marker);
        assertEquals("true", text(parent,
                "/project/profiles/profile[id='niord-base-reactor']/properties/" + SKIP));
    }

    private static void assertGroupConfiguration(Element execution) throws Exception {
        assertEquals("${" + SKIP + "}", text(execution, "configuration/skip"));
        assertEquals(List.of(TEST_JAR), texts(execution, "configuration/dependenciesToScan/dependency"));
        assertEquals(CountryBuildGroup.TAG, text(execution, "configuration/groups"));
        assertEquals("false", text(execution, "configuration/failIfNoSpecifiedTests"),
                "a country's -Dtest=<its own test> must not fail the group");
    }

    private static Document pom(String path) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Document document = factory.newDocumentBuilder().parse(Path.of(path).toFile());
        assertNotNull(document.getDocumentElement(), path);
        return document;
    }

    private static Element execution(Document pom, String plugin, String id) throws Exception {
        Node node = (Node) xpath().evaluate("/project/build/plugins/plugin[artifactId='" + plugin
                + "']/executions/execution[id='" + id + "']", pom, XPathConstants.NODE);
        assertNotNull(node, plugin + " has the execution " + id);
        return (Element) node;
    }

    private static String property(Document pom, String name) throws Exception {
        return xpath().evaluate("/project/properties/" + name, pom).trim();
    }

    private static String text(Node node, String path) throws Exception {
        return xpath().evaluate(path, node).trim();
    }

    private static List<String> texts(Node node, String path) throws Exception {
        NodeList nodes = (NodeList) xpath().evaluate(path, node, XPathConstants.NODESET);
        List<String> texts = new ArrayList<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            texts.add(nodes.item(i).getTextContent().trim());
        }
        return texts;
    }

    private static XPath xpath() {
        return XPathFactory.newInstance().newXPath();
    }
}
