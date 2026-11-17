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
package org.niord.core.settings;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Service;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Whether the code that declares a setting runs on this node ({@link SettingsService#declaringCodeRuns},
 * docs/code-data-config-plan.md §5.3): start-up adds a key only then, so a node without a conditional bean, such as
 * SECOM switched off, adds none of its settings. A Spring component runs when the context has a bean of it; any other
 * declaring class always.
 */
class SettingsLivenessTest {

    @Service
    static class AService {
    }

    @Configuration
    static class AConfiguration {
    }

    private final ListableBeanFactory beanFactory = mock(ListableBeanFactory.class);

    private final ClassLoader cl = getClass().getClassLoader();

    @Test
    void aServiceWithoutABeanDoesNotRun() {
        assertFalse(runs(AService.class.getName()));
    }

    @Test
    void aServiceWithABeanRuns() {
        when(beanFactory.getBeanNamesForType(AService.class, true, false)).thenReturn(new String[] {"aService"});

        assertTrue(runs(AService.class.getName()));
    }

    @Test
    void anAbsentConfigurationDoesNotRun() {
        when(beanFactory.getBeanNamesForType(AConfiguration.class, true, false)).thenReturn(new String[0]);

        assertFalse(runs(AConfiguration.class.getName()), "e.g. SecomConfiguration with SECOM switched off");
    }

    @Test
    void aHolderOfConstantsAlwaysRuns() {
        assertTrue(runs(FrontendSettings.class.getName()), "no Spring component: nothing switches it off");
    }

    @Test
    void aClassThatDoesNotLoadDoesNotRun() {
        assertFalse(runs("org.niord.no.such.Declaration"));
    }

    @Test
    void anEntryWithoutDeclaredInRuns() {
        assertTrue(runs(), "a catalog from before declaredIn: added as before");
    }

    @Test
    void aKeyRunsWhenAnyOfItsDeclaringClassesRuns() {
        when(beanFactory.getBeanNamesForType(AService.class, true, false)).thenReturn(new String[] {"aService"});

        assertTrue(runs(AConfiguration.class.getName(), AService.class.getName()));
    }

    private boolean runs(String... declaredIn) {
        SettingsCatalog.Entry entry = new SettingsCatalog.Entry("fixtureKey", Setting.Type.String, "", null,
                Setting.Scope.operator, false, true, false, true, "4.0.74", "A fixture", List.of(declaredIn));
        return SettingsService.declaringCodeRuns(entry, cl, beanFactory);
    }
}
