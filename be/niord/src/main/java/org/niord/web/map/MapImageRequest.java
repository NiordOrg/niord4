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
package org.niord.web.map;

import org.niord.model.geojson.FeatureCollectionVo;

import java.util.function.Supplier;

/**
 * Everything the renderer needs to draw the map image of one message.
 * <p>
 * It is read from the database in a short transaction and then used without one, so the tile
 * fetches and the drawing never hold a database connection. Nothing in it is a managed entity.
 *
 * @param featureCollections the message's geometry, as the message's own GeoJSON
 * @param mainType           the message's main type ({@code NW} or {@code NM}), selecting the style
 * @param messageType        the message's type (e.g. {@code TEMPORARY_NOTICE}), or null; the portrayal
 *                           definition may select a style on it
 * @param updatedMillis      when the message last changed; the image file carries it as its modification
 *                           time, which is how the endpoint tells a current image from an outdated one
 * @param imageSize          the width and height of the image in pixels: the {@code mapImageSize} the request read
 *                           once and named the image file by, so that the drawing agrees with the name when the
 *                           setting is saved while the request runs
 * @param tiles             where the background tiles come from, resolved from the live settings by the
 *                           thread that draws the image, when it has something to draw: a request that is
 *                           answered with an older image while the current one is drawn in the background does
 *                           not read the settings itself
 */
public record MapImageRequest(
        FeatureCollectionVo[] featureCollections,
        String mainType,
        String messageType,
        long updatedMillis,
        int imageSize,
        Supplier<TileSource> tiles) {
}
