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

import org.niord.core.util.GraphicsUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Stores an image an editor uploads as a message's custom map thumbnail: always as a PNG of the map image size,
 * the image scaled down to fit and centred on a transparent square, never scaled up.
 */
public final class CustomThumbnailScaler {

    private static final Logger log = LoggerFactory.getLogger(CustomThumbnailScaler.class);

    private CustomThumbnailScaler() {
    }

    /**
     * Validates that the given bytes are an image and returns it as the thumbnail's PNG. An image of exactly
     * the given size is re-encoded to PNG, so the stored file always matches its {@code .png} name and
     * {@code image/png} content type whatever format was uploaded; any other image is scaled down to fit the
     * size, keeping its aspect, and centred.
     *
     * @param imageData the uploaded bytes
     * @param size the width and height of the thumbnail in pixels
     * @return the thumbnail as a PNG; null when the bytes are not an image
     */
    public static byte[] scale(byte[] imageData, int size) throws IOException {

        // Check that we can read the image
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageData));
        if (image == null) {
            return null;
        }

        ByteArrayOutputStream png = new ByteArrayOutputStream();
        if (image.getWidth() == size && image.getHeight() == size) {
            // Re-encode to PNG so the stored file always matches its ".png" name / "image/png" content
            // type, regardless of the uploaded image format (the scaling branch below already re-encodes).
            if (!ImageIO.write(image, "png", png)) {
                // No PNG writer takes the image's colour model (a CMYK TIFF): no thumbnail, rather than an empty file
                return null;
            }
            log.info("Update message map image");

        } else {
            // Scale down
            BufferedImage destImage = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2 = destImage.createGraphics();
            GraphicsUtils.antialias(g2);
            float scale = Math.min((float) size / image.getWidth(), (float) size / image.getHeight());

            // We never scale up
            if (scale > 1.0f) {
                scale = 1.0f;
            }

            float dx = (size - scale * image.getWidth()) / 2.0f;
            float dy = (size - scale * image.getHeight()) / 2.0f;
            g2.drawImage(image,

                    // Destination coordinates:
                    Math.round(dx),
                    Math.round(dy),
                    Math.round(dx + scale * image.getWidth()),
                    Math.round(dy + scale * image.getHeight()),

                    // Source coordinates:
                    0,
                    0,
                    image.getWidth(),
                    image.getHeight(),
                    null);

            g2.dispose();

            // Encode the scaled image as a PNG
            if (!ImageIO.write(destImage, "png", png)) {
                return null;
            }
            image.flush();
            log.info("Update scaled message map image");
        }

        return png.toByteArray();
    }
}
