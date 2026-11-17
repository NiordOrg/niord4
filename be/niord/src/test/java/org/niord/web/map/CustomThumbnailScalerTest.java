/*
 * Copyright 2024 Danish Maritime Authority.
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

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tier-1 unit tests for {@link CustomThumbnailScaler#scale(byte[], int)}, to which
 * {@link MessageMapImageGenerator#scaleCustomThumbnail(byte[])} delegates —
 * the validate / scale / encode path behind the custom-thumbnail upload endpoints
 * ({@code PUT} base-64 data-URI and {@code POST} multipart, in {@link MessageMapImageRestService}). The REST
 * layer only ever asserts the <em>authorization</em> boundaries of those endpoints
 * (see {@code MessageMapImageRestServiceITTest}); the image-processing core they delegate to was never
 * exercised. This class covers that core directly.
 *
 * <p>The class-under-test is a static method that takes the target size as an argument and returns the PNG
 * it made, which the endpoint writes to the repository store. No container, no network, no file. The last test
 * checks that the generator hands its {@code mapImageSize} to it.
 *
 * <p><b>Behaviour under test.</b> The method:
 * <ul>
 *   <li>returns {@code null} when the bytes are not a decodable image;</li>
 *   <li>when the image is already exactly {@code mapImageSize x mapImageSize}, re-encodes it to PNG so the
 *       stored {@code .png} row always matches its content type (MAP3) — see
 *       {@code thumbnailOfExactSizeIsReEncodedToPng};</li>
 *   <li>otherwise scales the image <em>down</em> to fit {@code mapImageSize}, never up, preserving aspect
 *       ratio and centring the result on a transparent {@code mapImageSize}-square canvas.</li>
 * </ul>
 */
public class CustomThumbnailScalerTest {

    /** Map-image edge length used throughout; keeps the math clean and the rendered fixtures small/fast. */
    private static final int SIZE = 100;

    // --------------------------------------------------------------------------------------------------- //
    // Invalid input: not a decodable image.
    // --------------------------------------------------------------------------------------------------- //

    /** Bytes that {@link javax.imageio.ImageIO#read} cannot decode yield {@code null}: nothing to store. */
    @Test
    public void nonImageBytesYieldNull() throws Exception {
        assertNull(CustomThumbnailScaler.scale("this is not an image".getBytes(StandardCharsets.UTF_8), SIZE),
                "Undecodable bytes must yield no image");
    }

    /** An empty buffer is likewise undecodable -> null. */
    @Test
    public void emptyByteArrayYieldsNull() throws Exception {
        assertNull(CustomThumbnailScaler.scale(new byte[0], SIZE));
    }

    // --------------------------------------------------------------------------------------------------- //
    // Exact-size input: re-encoded to PNG.
    // --------------------------------------------------------------------------------------------------- //

    /**
     * When the uploaded image already has exactly the target dimensions, the generator re-encodes it to PNG
     * (MAP3). The result is therefore a valid PNG of the correct size (no longer byte-identical to a PNG
     * upload).
     */
    @Test
    public void thumbnailOfExactSizeIsReEncodedToPng() throws Exception {
        byte[] input = MapImageTestSupport.solidPng(SIZE, SIZE, Color.RED);

        byte[] written = CustomThumbnailScaler.scale(input, SIZE);

        assertNotNull(written);
        assertPngSignature(written);

        BufferedImage img = MapImageTestSupport.decode(written);
        assertEquals(SIZE, img.getWidth());
        assertEquals(SIZE, img.getHeight());
        // The interior colour survives the lossless PNG re-encode.
        assertOpaqueRed(img, SIZE / 2, SIZE / 2);
    }

    /**
     * MAP3: the exact-size fast path used to keep whatever bytes it was given, under a {@code .png} name,
     * even when those bytes were actually a JPEG — producing a file whose content disagreed with its
     * {@code .png} / {@code image/png} extension. It now re-encodes to a real PNG. This test scales an
     * exact-size JPEG and asserts the result is a PNG (correct signature, decodes at the target size),
     * not the original JPEG bytes.
     */
    @Test
    public void exactSizeNonPngIsReEncodedToPng_MAP3() throws Exception {
        byte[] jpeg = MapImageTestSupport.jpeg(SIZE, SIZE, Color.RED);
        // Sanity: it really is a JPEG (SOI marker) and not a PNG, and it decodes at the exact target size.
        assertEquals((byte) 0xFF, jpeg[0]);
        assertEquals((byte) 0xD8, jpeg[1]);
        assertEquals(SIZE, MapImageTestSupport.decode(jpeg).getWidth());

        byte[] written = CustomThumbnailScaler.scale(jpeg, SIZE);

        assertNotNull(written);
        assertFalse(Arrays.equals(jpeg, written),
                "The uploaded JPEG must be re-encoded, not kept verbatim under the .png name");
        assertPngSignature(written);
        assertEquals(SIZE, MapImageTestSupport.decode(written).getWidth(),
                "The re-encoded PNG decodes at the target size");
    }

    // --------------------------------------------------------------------------------------------------- //
    // Larger input: scaled down to the target square.
    // --------------------------------------------------------------------------------------------------- //

    /**
     * A square image larger than the target is scaled down to exactly {@code mapImageSize} and, because the
     * aspect ratio matches the square canvas, fills it completely. The output is a freshly-encoded PNG (its
     * bytes differ from the input), is fully opaque, and preserves the source colour.
     */
    @Test
    public void largerSquareImageIsScaledDownToFillTarget() throws Exception {
        byte[] input = MapImageTestSupport.solidPng(2 * SIZE, 2 * SIZE, Color.RED);

        byte[] written = CustomThumbnailScaler.scale(input, SIZE);

        assertNotNull(written);
        BufferedImage out = MapImageTestSupport.decode(written);
        assertEquals(SIZE, out.getWidth());
        assertEquals(SIZE, out.getHeight());

        // scale = 0.5, dx = dy = 0 -> the scaled image covers the whole canvas, opaquely.
        assertOpaqueRed(out, SIZE / 2, SIZE / 2);
        assertOpaqueRed(out, 3, 3);
        assertOpaqueRed(out, SIZE - 4, SIZE - 4);

        assertFalse(Arrays.equals(input, written), "Scaled output is a fresh PNG, not the original bytes");
    }

    /**
     * A landscape image (wider than tall) is scaled to fit the width and vertically centred, leaving
     * transparent letterbox bands top and bottom. This proves aspect-ratio preservation and centring along
     * the constrained axis.
     */
    @Test
    public void wideImageIsScaledAndVerticallyLetterboxed() throws Exception {
        // 200x100 -> scale 0.5 -> drawn region rows [25,75), columns [0,100): 25px transparent band top & bottom.
        byte[] input = MapImageTestSupport.solidPng(2 * SIZE, SIZE, Color.RED);

        BufferedImage out = MapImageTestSupport.decode(CustomThumbnailScaler.scale(input, SIZE));
        assertEquals(SIZE, out.getWidth());
        assertEquals(SIZE, out.getHeight());

        assertEquals(0, MapImageTestSupport.alpha(out, SIZE / 2, 3), "Top band must be transparent");
        assertEquals(0, MapImageTestSupport.alpha(out, SIZE / 2, SIZE - 4), "Bottom band must be transparent");
        assertOpaqueRed(out, SIZE / 2, SIZE / 2);
    }

    /**
     * A portrait image (taller than wide) is scaled to fit the height and horizontally centred, leaving
     * transparent pillarbox bands left and right.
     */
    @Test
    public void tallImageIsScaledAndHorizontallyPillarboxed() throws Exception {
        // 100x200 -> scale 0.5 -> drawn region columns [25,75), rows [0,100): 25px transparent band left & right.
        byte[] input = MapImageTestSupport.solidPng(SIZE, 2 * SIZE, Color.RED);

        BufferedImage out = MapImageTestSupport.decode(CustomThumbnailScaler.scale(input, SIZE));

        assertEquals(0, MapImageTestSupport.alpha(out, 3, SIZE / 2), "Left band must be transparent");
        assertEquals(0, MapImageTestSupport.alpha(out, SIZE - 4, SIZE / 2), "Right band must be transparent");
        assertOpaqueRed(out, SIZE / 2, SIZE / 2);
    }

    /**
     * Boundary case: when exactly one dimension already equals the target, {@code scale} works out to exactly
     * {@code 1.0} (so the {@code scale > 1.0} clamp does not fire) and the image is drawn at native size,
     * centred along the shorter axis. A 100×60 source fills the full width and is letterboxed top/bottom.
     */
    @Test
    public void imageMatchingTargetWidthIsDrawnAtNativeScale() throws Exception {
        // width == SIZE => scale = min(100/100, 100/60) = 1.0 exactly; dy = (100 - 60)/2 = 20.
        byte[] input = MapImageTestSupport.solidPng(SIZE, 60, Color.RED);

        BufferedImage out = MapImageTestSupport.decode(CustomThumbnailScaler.scale(input, SIZE));
        assertEquals(SIZE, out.getWidth());
        assertEquals(SIZE, out.getHeight());

        assertEquals(0, MapImageTestSupport.alpha(out, SIZE / 2, 3), "top band transparent");
        assertEquals(0, MapImageTestSupport.alpha(out, SIZE / 2, SIZE - 4), "bottom band transparent");
        assertOpaqueRed(out, 3, SIZE / 2);          // full-width content at the centre row
        assertOpaqueRed(out, SIZE - 4, SIZE / 2);
        assertOpaqueRed(out, SIZE / 2, SIZE / 2);
    }

    // --------------------------------------------------------------------------------------------------- //
    // Smaller input: never upscaled.
    // --------------------------------------------------------------------------------------------------- //

    /**
     * An image smaller than the target is <em>not</em> enlarged (the scale is clamped to 1.0). It is drawn at
     * its native size, centred, with transparent padding all around. We verify the rendered content spans
     * only the original 40px (centred at 30..70), not the full 100px canvas — the decisive proof that no
     * upscaling occurred.
     */
    @Test
    public void smallImageIsCentredAndNotUpscaled() throws Exception {
        // 40x40, scale clamps to 1.0, dx = dy = 30 -> content occupies the square [30,70).
        byte[] input = MapImageTestSupport.solidPng(40, 40, Color.RED);

        BufferedImage out = MapImageTestSupport.decode(CustomThumbnailScaler.scale(input, SIZE));
        assertEquals(SIZE, out.getWidth());
        assertEquals(SIZE, out.getHeight());

        // Centre is opaque content...
        assertOpaqueRed(out, SIZE / 2, SIZE / 2);
        assertOpaqueRed(out, 35, 35); // still inside the 30..70 content box
        // ...but everything outside the native 40px box is transparent padding (NOT scaled up to fill).
        assertEquals(0, MapImageTestSupport.alpha(out, 5, 5), "Corner must be transparent padding");
        assertEquals(0, MapImageTestSupport.alpha(out, 80, 80),
                "Just outside the native content box must be transparent");
    }

    // --------------------------------------------------------------------------------------------------- //
    // The generator's upload overload
    // --------------------------------------------------------------------------------------------------- //

    /** The generator's {@code byte[]} method is this scaler at the generator's map-image size. */
    @Test
    public void theGeneratorScalesAnUploadToItsMapImageSize() throws Exception {
        MessageMapImageGenerator generator = new MessageMapImageGenerator();
        generator.mapImageSize = 64;

        byte[] written = generator.scaleCustomThumbnail(MapImageTestSupport.solidPng(128, 128, Color.RED));

        assertNotNull(written);
        BufferedImage out = MapImageTestSupport.decode(written);
        assertEquals(64, out.getWidth());
        assertEquals(64, out.getHeight());
        assertOpaqueRed(out, 32, 32);
    }

    // --------------------------------------------------------------------------------------------------- //
    // Helpers
    // --------------------------------------------------------------------------------------------------- //

    /** Asserts the bytes begin with the 8-byte PNG file signature (89 50 4E 47 0D 0A 1A 0A). */
    private static void assertPngSignature(byte[] bytes) {
        byte[] sig = { (byte) 0x89, 'P', 'N', 'G', (byte) 0x0D, (byte) 0x0A, (byte) 0x1A, (byte) 0x0A };
        assertTrue(bytes.length >= sig.length, "file too short to be a PNG");
        for (int i = 0; i < sig.length; i++) {
            assertEquals(sig[i], bytes[i], "PNG signature byte " + i);
        }
    }

    private static void assertOpaqueRed(BufferedImage img, int x, int y) {
        assertEquals(255, MapImageTestSupport.alpha(img, x, y), "alpha at (" + x + "," + y + ")");
        Color c = MapImageTestSupport.rgb(img, x, y);
        // Interior of a solid-red source stays pure red through lossless PNG; tolerances are widened a little
        // so a sampled near-edge pixel under bicubic scaling cannot flake the assertion.
        assertTrue(c.getRed() >= 240 && c.getGreen() <= 30 && c.getBlue() <= 30,
                "expected red at (" + x + "," + y + ") but was " + c);
    }
}
