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
package org.niord.web.map;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.niord.core.settings.Setting;
import org.niord.core.settings.SettingsService;
import org.niord.test.ITTest;
import org.niord.test.Tx;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tile cache folder setting ({@code mapTileCachePath}) against the database. Start-up adds its row when the
 * database lacks it; the map image requests read it through {@link MessageMapImageSnapshots#tileSource()} in a
 * read-only transaction, and a read never writes: without a row the folder resolves to the default in memory.
 *
 * <p>Each test removes the row and the system property of the same name (which wins over the row) before, and puts
 * back what was there after, so the shared context and database are left as they were.
 */
class MessageMapImageSnapshotsITTest extends ITTest {

    private static final String KEY = MapTileSettings.MAP_TILE_CACHE_PATH.getKey();

    @Autowired
    MessageMapImageSnapshots snapshots;

    @Autowired
    SettingsService settingsService;

    @PersistenceContext
    EntityManager em;

    /** The system property that was set before the test, or null */
    private String previousProperty;

    /** The value of the row that existed before the test, or null when there was none */
    private Object previousValue;

    @BeforeEach
    void removeTheSetting() {
        previousProperty = System.getProperty(KEY);
        System.clearProperty(KEY);
        previousValue = Tx.requiringNew().call(() -> {
            Setting row = em.find(Setting.class, KEY);
            if (row == null) {
                return null;
            }
            Object value = row.getValue();
            em.remove(row);
            return value;
        });
        settingsService.evictFromCache(KEY);
    }

    @AfterEach
    void restoreTheSetting() {
        if (previousProperty != null) {
            System.setProperty(KEY, previousProperty);
        }
        Tx.requiringNew().run(() -> {
            Setting row = em.find(Setting.class, KEY);
            if (row != null) {
                em.remove(row);
            }
        });
        if (previousValue != null) {
            Tx.requiringNew().run(() -> em.persist(new Setting(MapTileSettings.MAP_TILE_CACHE_PATH).value(previousValue)));
        }
        settingsService.evictFromCache(KEY);
    }

    private Setting storedRow() {
        return Tx.requiringNew().call(() -> em.find(Setting.class, KEY));
    }

    @Test
    void withoutARowTheTileSourceResolvesTheDefaultFolderAndWritesNothing() {
        assertNull(storedRow(), "precondition: no row");

        Path cacheDir = snapshots.tileSource().cacheDir();

        // niord.home/tile-cache, or niord-tile-cache in the temporary folder when niord.home does not resolve
        assertTrue(cacheDir.getFileName().toString().endsWith(MapTileSettings.TILE_CACHE_FOLDER),
                "the tile cache is the default folder: " + cacheDir);
        assertNull(storedRow(), "a read never writes the setting");
    }

    @Test
    void theTileSourceReadsTheStoredFolder() {
        Path folder = Path.of(System.getProperty("java.io.tmpdir"), "it-map-tiles").toAbsolutePath();
        Tx.requiringNew().run(() -> em.persist(new Setting(MapTileSettings.MAP_TILE_CACHE_PATH)
                .value(folder.toString().replace('\\', '/'))));
        settingsService.evictFromCache(KEY);

        Path cacheDir = snapshots.tileSource().cacheDir();

        assertEquals(folder, cacheDir.toAbsolutePath(), "the stored folder");
        assertNotNull(storedRow(), "the row is still there");
    }
}
