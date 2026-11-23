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

import jakarta.inject.Inject;
import org.apache.commons.lang3.StringUtils;
import org.niord.core.message.Message;
import org.niord.core.message.MessageQueryService;
import org.niord.core.settings.SettingsService;
import org.niord.model.geojson.FeatureCollectionVo;
import org.niord.model.message.MainType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Reads what the map image endpoint needs from the database, each read in a short read-only transaction of its
 * own, so that the endpoint looks at files, fetches tiles and draws with no database connection held.
 *
 * <p>A bean of its own because Spring's transactions are proxy-based: a transactional method the endpoint called
 * on itself would run without a transaction.
 */
@Service
@Transactional(readOnly = true)
public class MessageMapImageSnapshots {

    private static final Logger log = LoggerFactory.getLogger(MessageMapImageSnapshots.class);

    @Inject
    MessageQueryService messageQueryService;

    @Inject
    MapTileSettings tileSettings;

    /**
     * What the map image endpoint knows about a message, read in one transaction. It holds no entity.
     *
     * @param uid           the message's UID
     * @param repoPath      the message's folder, relative to the repository root
     * @param thumbnailPath the custom thumbnail an editor set, relative to the repository root, or null
     * @param updatedMillis when the message last changed
     * @param mainType      the message's main type ({@code NW} or {@code NM})
     * @param type          the message's type (e.g. {@code TEMPORARY_NOTICE}), or null
     * @param geometry      the message's geometry; null when it was not read, which is when the message names a
     *                      custom thumbnail (see {@link #snapshot})
     */
    public record Snapshot(String uid, String repoPath, String thumbnailPath, long updatedMillis, String mainType,
                           String type, FeatureCollectionVo[] geometry) {

        /** Returns whether the snapshot holds geometry to draw */
        public boolean hasGeometry() {
            return geometry != null && geometry.length > 0;
        }

        /**
         * Returns the renderer's request for the message's map image.
         * @param imageSize the size of the image, which the image file is named by
         * @param tiles resolves where the background tiles come from, on the thread that draws the image
         * @return the request
         */
        public MapImageRequest request(int imageSize, Supplier<TileSource> tiles) {
            return new MapImageRequest(geometry, mainType, type, updatedMillis, imageSize, tiles);
        }
    }

    /**
     * Reads the message with the given UID. The geometry is left out when the message names a custom thumbnail,
     * which is then served instead; {@link #snapshotWithGeometry} reads it when the thumbnail turns out to be
     * missing.
     *
     * @param uid the message's UID
     * @return the snapshot, or empty when there is no such message
     */
    public Optional<Snapshot> snapshot(String uid) {
        return read(uid, false);
    }

    /**
     * Reads the message with the given UID, its geometry included.
     * @param uid the message's UID
     * @return the snapshot, or empty when there is no such message
     */
    public Optional<Snapshot> snapshotWithGeometry(String uid) {
        return read(uid, true);
    }

    private Optional<Snapshot> read(String uid, boolean withGeometry) {
        Message message = messageQueryService.findByUid(uid);
        if (message == null) {
            return Optional.empty();
        }
        String thumbnailPath = StringUtils.isNotBlank(message.getThumbnailPath()) ? message.getThumbnailPath() : null;
        FeatureCollectionVo[] geometry = withGeometry || thumbnailPath == null ? message.toGeoJson() : null;
        MainType mainType = message.getMainType() != null
                ? message.getMainType()
                : message.getType() != null ? message.getType().getMainType() : null;
        return Optional.of(new Snapshot(
                message.getUid(),
                message.getRepoPath(),
                thumbnailPath,
                message.getUpdated().getTime(),
                mainType != null ? mainType.name() : null,
                message.getType() != null ? message.getType().name() : null,
                geometry));
    }

    /**
     * Returns where the background tiles of a map image come from, read from the live settings. Only the thread
     * that draws an image reads it (see {@link MapImageRequest#tiles()}), so serving an existing image reads no
     * settings, and neither does a request answered with an older image while the current one is drawn in the
     * background. The read-only transaction stores nothing: no settings read writes a row (a setting the database
     * lacks resolves in memory, see {@link org.niord.core.settings.SettingsService#get}).
     *
     * <p>The tile cache is a folder on the application's volume, which no repository endpoint serves: the
     * repository's files are rows of the store, so the cached tiles reach users only inside a map image.
     *
     * @return the tile source
     */
    public TileSource tileSource() {
        return new TileSource(tileSettings.osmUrlTemplate(), tileSettings.userAgent(), tileSettings.tileCacheDir());
    }
}
