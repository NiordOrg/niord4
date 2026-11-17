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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link MessageMapImageSnapshots#tileSource()}: the tile source the drawing reads is the settings' one — the
 * tile URL template, the user agent and the configured tile cache folder, which lies on the application's volume
 * (the repository files are rows of the database, so no folder of the volume is served to anybody).
 */
class MessageMapImageSnapshotsTest {

    @TempDir
    Path home;

    @Test
    void theConfiguredCacheIsUsed() {
        Path configured = home.resolve("tile-cache");
        MapTileSettings tileSettings = mock(MapTileSettings.class);
        when(tileSettings.osmUrlTemplate()).thenReturn("https://tile.example.org/{z}/{x}/{y}.png");
        when(tileSettings.userAgent()).thenReturn("Niord/test (+ops@example.org)");
        when(tileSettings.tileCacheDir()).thenReturn(configured);
        MessageMapImageSnapshots snapshots = new MessageMapImageSnapshots();
        snapshots.tileSettings = tileSettings;

        TileSource source = snapshots.tileSource();

        assertEquals(configured, source.cacheDir());
        assertEquals("https://tile.example.org/{z}/{x}/{y}.png", source.urlTemplate());
        assertEquals("Niord/test (+ops@example.org)", source.userAgent());
    }
}
