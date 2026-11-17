/*
 * Copyright 2026 Danish Emergency Management Agency.
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

package org.niord.core;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.classreading.MetadataReader;

import java.util.TreeSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fails the build if the test suite collapses.
 *
 * <p>The upstream reason for this guard was Quarkus-specific and is gone: adding
 * {@code quarkus-junit5} switched Surefire to the JUnit Platform provider, and without
 * {@code junit-vintage-engine} the platform discovered none of the JUnit 4 tests, so the build
 * reported "Tests run: 0" followed by BUILD SUCCESS. There is no Quarkus here, no JUnit 4 suite
 * and no vintage engine, so that particular trap cannot recur.
 *
 * <p>The <em>idea</em> still earns its place, because the same failure shape reaches this build
 * by other routes: a Surefire include pattern that stops matching, a module that stops being
 * built, a renamed package that takes a directory of tests out of the scan. All of them look
 * identical from the console — a green build that ran far less than it used to. A floor under
 * the test count is what makes that loud.
 *
 * <p><b>Counted by classpath scan, not by the JUnit Platform Launcher.</b>
 * {@code org.junit.platform:junit-platform-launcher} is not on this module's test classpath:
 * {@code spring-boot-starter-test} pulls {@code org.junit.jupiter:junit-jupiter}, which brings
 * only {@code junit-jupiter-api}, {@code -params} and {@code -engine}. Surefire supplies the
 * launcher to its own forked runtime, not to compilation. Adding the dependency would mean a
 * {@code pom.xml} edit, so the count is taken the way {@code RestControllerSecurityAnnotationTest}
 * takes its own: a {@link ClassPathScanningCandidateComponentProvider} reading class files with
 * ASM and counting the methods annotated {@code @Test}. Discovery is not execution, so this test
 * neither re-runs the suite nor recurses into itself.
 *
 * <p>The scan is deliberately condition-blind and abstract-tolerant: both of the provider's
 * candidate filters are overridden so that nothing is dropped for failing a {@code @Conditional},
 * and an abstract base class that declares {@code @Test} methods is still counted once, where it
 * is declared, rather than once per concrete subclass.
 *
 * <p>Note this is the mirror image of {@code be}'s own zero-test trap, which is covered
 * elsewhere: with Docker unreachable the whole integration suite reports "Tests run: 0" under
 * BUILD SUCCESS, and {@code be/test-it} plus {@code scripts/it-report-check.py --min} is what
 * fails that. This guard covers the unit suite; that one covers the integration suite.
 */
public class TestSuiteGuardTest {

    /**
     * A floor under the discovered suite, held a little below the real count.
     *
     * <p>Computed, not guessed: 5,823 {@code @Test} methods outside the publications packages plus
     * the 1,277 the publications redesign brings with it is a merged suite of roughly 7,100, and
     * 5% under that is 6,745. The margin is deliberately small. A floor far below the truth would
     * let the suite lose several hundred tests and still pass, which is a slower version of the
     * failure this exists to catch; it is loose enough only to absorb tests being consolidated.
     *
     * <p>Raise it as real tests are added; NEVER lower it to make a build pass. A drop here is
     * either a whole directory of tests that stopped being found or a build file that stopped
     * running them, and both look identical to a green build.
     */
    private static final int FLOOR = 6745;

    @Test
    public void testSuiteIsStillDiscovered() {

        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false) {
                    /** Any class that declares at least one @Test method, condition evaluation skipped */
                    @Override
                    protected boolean isCandidateComponent(MetadataReader metadataReader) {
                        return metadataReader.getAnnotationMetadata()
                                .hasAnnotatedMethods(Test.class.getName());
                    }

                    /** Abstract base classes declare tests too; the default filter would drop them */
                    @Override
                    protected boolean isCandidateComponent(AnnotatedBeanDefinition beanDefinition) {
                        return true;
                    }
                };
        // A filter the candidate-component index cannot answer, so the provider always walks the
        // class files rather than a (currently absent) META-INF/spring.components. With no include
        // filter at all the provider considers the index able to serve the request and an index
        // that appears later would silently answer "no test classes".
        scanner.addIncludeFilter((metadataReader, metadataReaderFactory) -> true);

        Set<String> testClasses = new TreeSet<>();
        long discovered = 0;

        for (BeanDefinition candidate : scanner.findCandidateComponents("org.niord")) {
            AnnotationMetadata metadata = ((AnnotatedBeanDefinition) candidate).getMetadata();
            String className = metadata.getClassName();

            // Excluding this guard's own tests keeps the floor honest
            if (className.equals(TestSuiteGuardTest.class.getName())) {
                continue;
            }

            testClasses.add(className);
            discovered += metadata.getAnnotatedMethods(Test.class.getName()).size();
        }

        // Guard the guard: a scan that found nothing must not be mistaken for a shrunken suite
        assertTrue(testClasses.contains("org.niord.web.RestControllerSecurityAnnotationTest"),
                "sanity: the classpath scan must find the test suite, found " + testClasses.size() + " classes");

        assertTrue(discovered >= FLOOR,
                "Discovered " + discovered + " @Test methods in " + testClasses.size()
                        + " classes, expected at least " + FLOOR
                        + ". A drop here means tests stopped being found, not that they passed -- "
                        + "check the Surefire includes, the module list and that no test directory "
                        + "was moved out of the org.niord package.");
    }
}
