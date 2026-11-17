/*
 * Copyright 2016 Danish Maritime Authority.
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
import org.niord.core.dictionary.DictionaryService;
import org.niord.core.message.MessageLuceneIndex;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PG5 startup-ordering contract.
 *
 * <p>The settings add-missing step ({@link SettingsService#init()}) and the settings <em>readers</em>
 * ({@code DictionaryService.init} → {@code NiordApp.getLanguages}, {@code MessageLuceneIndex.init}) are all
 * {@code @EventListener(ApplicationReadyEvent.class)}. With no ordering, Spring's invocation order is undefined.
 * Until C3b a reader that won the race created a not-yet-loaded key (e.g. {@code modelLanguages}) by insert-on-read,
 * and the loader then skipped it, booting the app English-only (PG5). A read no longer writes, but the readers
 * still expect the added rows.
 *
 * <p>The add-missing step has {@code @Order(Ordered.HIGHEST_PRECEDENCE)}, so it runs before every other
 * {@code ApplicationReadyEvent} listener, and the re-injection of the {@code @Setting} fields that were resolved
 * without the database ({@code reinjectDeferredSettings}) comes right after it, at
 * {@code HIGHEST_PRECEDENCE + 1}. Spring invokes listeners in ascending order value, so this test pins both before
 * every settings-reading listener. Removing an annotation, or giving a reader an equal/earlier order, fails it.
 *
 * <p>Pure reflection unit test (no container); reads only the standard Spring {@code @EventListener}/
 * {@code @Order} annotations, so it survives a future framework migration.
 */
public class SettingsServiceStartupOrderTest {

    /** The order value Spring would use for a listener method: its {@code @Order} value, else LOWEST. */
    private static int effectiveOrder(Class<?> type, String method) throws NoSuchMethodException {
        Method m = type.getDeclaredMethod(method);
        assertNotNull(m.getAnnotation(EventListener.class),
                type.getSimpleName() + "#" + method
                        + " must be an @EventListener (the ordering contract only applies to listeners)");
        Order order = m.getAnnotation(Order.class);
        return order != null ? order.value() : Ordered.LOWEST_PRECEDENCE;
    }

    @Test
    public void settingsLoaderRunsBeforeEverySettingsReadingListener() throws Exception {
        int loader = effectiveOrder(SettingsService.class, "init");
        assertEquals(Ordered.HIGHEST_PRECEDENCE, loader,
                "SettingsService.init must carry @Order(Ordered.HIGHEST_PRECEDENCE) so the missing settings are "
                        + "added before any setting is read (PG5)");
        int reinject = effectiveOrder(SettingsService.class, "reinjectDeferredSettings");
        assertEquals(Ordered.HIGHEST_PRECEDENCE + 1, reinject,
                "SettingsService.reinjectDeferredSettings runs right after the add-missing step");

        // Spring fires ApplicationReadyEvent listeners in ascending order value, so the loader's value must
        // be strictly smaller than every reader's — guaranteeing it runs first.
        int dictionary = effectiveOrder(DictionaryService.class, "init");
        int luceneIndex = effectiveOrder(MessageLuceneIndex.class, "init");
        assertTrue(loader < dictionary,
                "DictionaryService.init reads NiordApp.getLanguages() and must run AFTER the settings "
                        + "loader (loader order " + loader + " must be < " + dictionary + ")");
        assertTrue(loader < luceneIndex,
                "MessageLuceneIndex.init must run AFTER the settings loader "
                        + "(loader order " + loader + " must be < " + luceneIndex + ")");
        assertTrue(reinject < dictionary && reinject < luceneIndex,
                "the re-injection runs before the settings readers, so they see the database's values");
    }
}
