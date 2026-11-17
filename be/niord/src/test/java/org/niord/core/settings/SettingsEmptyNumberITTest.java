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

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.niord.test.ITTest;
import org.niord.test.Tx;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * A number setting left empty means its default (L-180; the owner's answer 14 of the phase 2 error hunt): an Integer,
 * Long, Float or Double row whose value is null reads as the setting's default, live through
 * {@link SettingsService#get} and {@link SettingsService#peek}, as the {@code @Setting} injection already read it at
 * the next start. Before, the Settings page saved the emptied value with 200, a live read gave null, and an injected
 * field took the default only after a restart. The row itself stays empty, so the Settings page shows the field empty.
 * A text setting left empty keeps its empty value.
 *
 * <p>Every write commits, so each test puts back what it changed.
 */
class SettingsEmptyNumberITTest extends ITTest {

    /** An optional, editable Integer operator setting the start-up writes a row for, read only by the map images */
    private static final String NUMBER = "mapImageSize";

    /** An optional, editable String operator setting with a default, which no test reads for its meaning */
    private static final String TEXT = "documentationUrl";

    @Autowired
    SettingsService settingsService;

    @PersistenceContext
    EntityManager em;

    @Test
    void anEmptiedNumberSettingReadsAsItsDefault() {
        Object original = rowValue(NUMBER);
        Object expected = settingsService.resolveDefault(new Setting(NUMBER));
        assertInstanceOf(Number.class, expected, "the catalog gives " + NUMBER + " a number default");
        try {
            settingsService.set(NUMBER, null);

            assertNull(rowValue(NUMBER), "the row stays empty, as the Settings page saved it");
            assertEquals(((Number) expected).intValue(), settingsService.getInteger(NUMBER),
                    "the live read gives the default");
            assertEquals(((Number) expected).intValue(), ((Number) settingsService.peek(NUMBER)).intValue(),
                    "a read past the cache gives the default");
        } finally {
            settingsService.set(NUMBER, original);
            settingsService.evictFromCache(NUMBER);
        }
    }

    @Test
    void anEmptiedTextSettingStaysEmpty() {
        Object original = rowValue(TEXT);
        assertInstanceOf(String.class, settingsService.resolveDefault(new Setting(TEXT)),
                TEXT + " has a default, so an empty read is not that default by chance");
        try {
            settingsService.set(TEXT, null);

            assertNull(settingsService.get(TEXT), "an emptied text is read as empty, as meant");
            assertNull(settingsService.peek(TEXT), "and past the cache too");
        } finally {
            settingsService.set(TEXT, original);
            settingsService.evictFromCache(TEXT);
        }
    }

    /** A row no catalog declares follows its own type: an empty Integer row reads as the reader's default */
    @Test
    void anEmptyNumberRowNoCatalogDeclaresReadsAsTheReadersDefault() {
        String key = "it-empty-number-" + System.nanoTime();
        Tx.requiringNew().run(() -> em.persist(
                new Setting(key, null, Setting.Type.Integer, "An undeclared number row", true, false, false)));
        try {
            assertEquals(7, settingsService.getInteger(new Setting(key, 7)), "the reader's default");
            assertNull(settingsService.peek(key), "no catalog default to read past the cache");
        } finally {
            Tx.requiringNew().run(() -> em.createQuery("delete from Setting where key = :k")
                    .setParameter("k", key).executeUpdate());
            settingsService.evictFromCache(key);
        }
    }

    private Object rowValue(String key) {
        return Tx.requiringNew().call(() -> em.find(Setting.class, key).getValue());
    }
}
