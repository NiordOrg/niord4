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

package org.niord.core.repo;

import org.apache.commons.io.FilenameUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.spi.IIORegistry;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.stream.ImageInputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ThumbnailService#createThumbnailFile(Path, java.time.Instant, Path, String, IconSize)} — the file-based
 * core of the thumbnail generation, which {@link ThumbnailService#createThumbnail(RepoFileInfo, String, IconSize)}
 * runs on a spool of the repository row.
 *
 * <p>The service is a CDI {@code @ApplicationScoped} bean whose collaborators are normally injected
 * ({@code log}, {@code vipsCmd}, {@code fileTypes}). To exercise the pure image-scaling logic WITHOUT
 * Quarkus, the bean is instantiated directly and its package-private fields are populated reflectively:
 * <ul>
 *     <li>{@code vipsCmd = ""} forces the plain-Java branch (the {@code vips} external process is never
 *         invoked, so the test is deterministic and host-independent),</li>
 *     <li>{@code log} is a real SLF4J logger,</li>
 *     <li>{@code fileTypes} is a real {@link FileTypes} with {@code init()} called to populate its
 *         mime maps (the production wiring drives the no-arg {@code init} from an
 *         {@code ApplicationReadyEvent}),</li>
 *     <li>{@code vipsFileTypes} is left empty.</li>
 * </ul>
 *
 * <p>The service names a thumbnail {@code <base>_thumb_<size>.<ext>} in the cache tree
 * ({@link RepoCache#thumbnailFor}); these tests resolve that name next to the source file and hand it to the
 * core. They pin the exact output dimensions (aspect-ratio preserving, never scaled up), the
 * verbatim-copy fast path for already-small images, the mtime-based caching, the failure for
 * a non-decodable image (which the service answers with no thumbnail), and that the {@link IconSize} cap and
 * filename suffix track each other. They
 * also pin the pixel cap ({@code maxThumbnailSourcePixels}, read from the header before decoding, which a
 * test image reader that records its decodes proves) and the subsampled decode of a large image, which keeps
 * the exact thumbnail size.
 *
 * <p>The {@code vips} branch and {@link ThumbnailService#getThumbnail(RepoFileInfo, IconSize)} (which depends on
 * the {@code @PostConstruct}-initialised {@code processPool}) are intentionally not covered here — see
 * the class-level scenario notes; they require either an external binary or CDI lifecycle.
 */
public class ThumbnailServiceCreateThumbnailTest {

    @TempDir
    Path tmp;

    private ThumbnailService service;

    @BeforeEach
    public void setUp() throws Exception {
        FileTypes fileTypes = new FileTypes();
        // PORT: init() is now a no-arg @EventListener(ApplicationReadyEvent.class) (was @Observes
        // StartupEvent / init(StartupEvent)); call once to populate the lookup maps.
        fileTypes.init();

        // PORT: fileTypes is now a constructor-injected `private final` field (was an @Inject field);
        // pass the same real FileTypes instance into the constructor instead of reflectively setting it.
        // The file-based core under test reads neither the store nor the cache.
        service = new ThumbnailService(fileTypes, null, null);
        setField(service, "log", LoggerFactory.getLogger("ThumbnailServiceCreateThumbnailTest"));
        // Force the plain-Java branch; never shell out to the (possibly absent) vips binary.
        setField(service, "vipsCmd", "");
        // vipsFileTypes is already an empty HashSet on a fresh instance; leave it empty.
    }

    // ----------------------------------------------------------------------
    // Down-scaling: dimensions & filename
    // ----------------------------------------------------------------------

    @Test
    public void squareImageIsScaledDownToTheSizeCap() throws Exception {
        // 200x200 -> d = 64/200 = 0.32 -> 64x64
        Path src = writePng("square.png", 200, 200);

        Path thumb = thumbnail(src, IconSize.SIZE_64);

        assertNotNull(thumb, "a thumbnail must be produced");
        assertEquals("square_thumb_64.png", thumb.getFileName().toString());
        assertTrue(Files.isRegularFile(thumb), "thumbnail file must exist on disk");

        BufferedImage out = ImageIO.read(thumb.toFile());
        assertNotNull(out, "thumbnail must be a readable image");
        assertEquals(64, out.getWidth());
        assertEquals(64, out.getHeight());
    }

    @Test
    public void landscapeImagePreservesAspectRatio() throws Exception {
        // 200x100 -> dx=64/200=0.32, dy=64/100=0.64, d=min=0.32 -> 64x32
        Path src = writePng("wide.png", 200, 100);

        Path thumb = thumbnail(src, IconSize.SIZE_64);

        assertNotNull(thumb);
        BufferedImage out = ImageIO.read(thumb.toFile());
        assertEquals(64, out.getWidth());
        assertEquals(32, out.getHeight());
    }

    // ----------------------------------------------------------------------
    // Never scale up: verbatim copy for images smaller than the cap
    // ----------------------------------------------------------------------

    @Test
    public void imageSmallerThanCapIsCopiedVerbatim() throws Exception {
        // 40x40 < 64 in both dimensions -> FileUtils.copyFile, bytes identical to the source.
        Path src = writePng("small.png", 40, 40);
        byte[] srcBytes = Files.readAllBytes(src);

        Path thumb = thumbnail(src, IconSize.SIZE_64);

        assertNotNull(thumb);
        assertEquals("small_thumb_64.png", thumb.getFileName().toString());

        // Byte-for-byte copy (not re-encoded).
        assertArrayEquals(srcBytes, Files.readAllBytes(thumb));

        BufferedImage out = ImageIO.read(thumb.toFile());
        assertEquals(40, out.getWidth());
        assertEquals(40, out.getHeight());
    }

    // ----------------------------------------------------------------------
    // mtime-based caching
    // ----------------------------------------------------------------------

    @Test
    public void existingThumbnailNotOlderThanSourceIsReturnedUnchanged() throws Exception {
        Path src = writePng("cached.png", 200, 200);

        // Pre-create a thumbnail whose mtime is NOT older than the source. Use sentinel bytes that are
        // intentionally NOT a valid image so any regeneration would be detectable.
        Path thumb = src.getParent().resolve("cached_thumb_64.png");
        byte[] sentinel = "not-a-real-png-do-not-overwrite".getBytes("UTF-8");
        Files.write(thumb, sentinel);

        long srcMillis = Files.getLastModifiedTime(src).toMillis();
        FileTime thumbTime = FileTime.fromMillis(srcMillis + 5_000);
        Files.setLastModifiedTime(thumb, thumbTime);

        Path result = thumbnail(src, IconSize.SIZE_64);

        assertEquals(thumb, result, "cached thumbnail path must be returned");
        // Content untouched.
        assertArrayEquals(sentinel, Files.readAllBytes(thumb));
        // mtime untouched.
        assertEquals(thumbTime.toMillis(), Files.getLastModifiedTime(thumb).toMillis());
    }

    @Test
    public void existingThumbnailOlderThanSourceIsRegenerated() throws Exception {
        Path src = writePng("stale.png", 200, 200);

        // Pre-create a stale, non-image thumbnail older than the source.
        Path thumb = src.getParent().resolve("stale_thumb_64.png");
        byte[] sentinel = "stale-sentinel".getBytes("UTF-8");
        Files.write(thumb, sentinel);

        long srcMillis = Files.getLastModifiedTime(src).toMillis();
        Files.setLastModifiedTime(thumb, FileTime.fromMillis(srcMillis - 60_000));

        Path result = thumbnail(src, IconSize.SIZE_64);

        assertEquals(thumb, result);
        // Sentinel was overwritten with a freshly generated, valid 64x64 PNG.
        assertFalse(java.util.Arrays.equals(sentinel, Files.readAllBytes(thumb)),
                "stale sentinel must be overwritten");
        BufferedImage out = ImageIO.read(thumb.toFile());
        assertNotNull(out, "regenerated thumbnail must be a readable image");
        assertEquals(64, out.getWidth());
        assertEquals(64, out.getHeight());
    }

    // ----------------------------------------------------------------------
    // Non-decodable input
    // ----------------------------------------------------------------------

    @Test
    public void nonDecodableImageReturnsNullAndWritesNoThumbnail() throws Exception {
        // A .png file that actually contains plain text: no image reader takes it -> IOException -> no
        // thumbnail (the service answers null).
        Path src = tmp.resolve("broken.png");
        Files.write(src, "this is not an image".getBytes("UTF-8"));

        Path result = thumbnail(src, IconSize.SIZE_64);

        assertNull(result, "non-decodable image must yield null");
        Path thumb = src.getParent().resolve("broken_thumb_64.png");
        assertFalse(Files.exists(thumb),
                "no thumbnail must be written for a non-decodable image");
    }

    // ----------------------------------------------------------------------
    // IconSize is honoured in both the dimension cap and the filename suffix
    // ----------------------------------------------------------------------

    @Test
    public void size32CapsDimensionsAndNamesFile() throws Exception {
        // 200x200 -> d = 32/200 = 0.16 -> 32x32, file suffix _32
        Path src = writePng("icon32.png", 200, 200);

        Path thumb = thumbnail(src, IconSize.SIZE_32);

        assertNotNull(thumb);
        assertEquals("icon32_thumb_32.png", thumb.getFileName().toString());
        BufferedImage out = ImageIO.read(thumb.toFile());
        assertEquals(32, out.getWidth());
        assertEquals(32, out.getHeight());
    }

    @Test
    public void size128CapsDimensionsAndNamesFile() throws Exception {
        // 200x200 -> d = 128/200 = 0.64 -> 128x128, file suffix _128
        Path src = writePng("icon128.png", 200, 200);

        Path thumb = thumbnail(src, IconSize.SIZE_128);

        assertNotNull(thumb);
        assertEquals("icon128_thumb_128.png", thumb.getFileName().toString());
        BufferedImage out = ImageIO.read(thumb.toFile());
        assertEquals(128, out.getWidth());
        assertEquals(128, out.getHeight());
    }

    // ----------------------------------------------------------------------
    // Pixel cap: an image is measured from its header and refused above the
    // cap before it is decoded; createThumbnail then returns null, which the
    // endpoint answers with the file-type icon.
    // ----------------------------------------------------------------------

    @Test
    public void imageAboveThePixelCapReturnsNullAndWritesNoThumbnail() throws Exception {
        Path src = writePng("large.png", 200, 200);
        service.maxThumbnailSourcePixels = 200L * 200L - 1;

        Path result = thumbnail(src, IconSize.SIZE_64);

        assertNull(result, "an image above the pixel cap must get no thumbnail");
        assertFalse(Files.exists(src.getParent().resolve("large_thumb_64.png")),
                "no thumbnail may be written for an image above the pixel cap");
    }

    @Test
    public void imageAtThePixelCapIsStillScaled() throws Exception {
        Path src = writePng("atcap.png", 200, 100);
        service.maxThumbnailSourcePixels = 200L * 100L;

        Path thumb = thumbnail(src, IconSize.SIZE_64);

        assertNotNull(thumb, "an image exactly at the pixel cap is thumbnailed");
        BufferedImage out = ImageIO.read(thumb.toFile());
        assertEquals(64, out.getWidth());
        assertEquals(32, out.getHeight());
    }

    @Test
    public void pngHeaderClaimingHugeDimensionsGetsNoThumbnail() throws Exception {
        // A tiny PNG whose header claims 20000 x 20000 pixels (400 megapixels, over the default cap of 100):
        // the real PNG reader reports that size from the header alone, and the cap refuses it. (A decode
        // would fail on the missing image data too, so the order of the two is pinned by the next test.)
        Path src = tmp.resolve("bomb.png");
        Files.write(src, pngClaiming(20_000, 20_000));
        assertTrue(Files.size(src) < 1024, "precondition: the file itself is tiny");

        Path result = thumbnail(src, IconSize.SIZE_64);

        assertNull(result, "an image whose header claims too many pixels must get no thumbnail");
        assertFalse(Files.exists(src.getParent().resolve("bomb_thumb_64.png")));
    }

    @Test
    public void headerAboveThePixelCapIsRefusedBeforeAnyPixelIsDecoded() throws Exception {
        // A test reader declares 20000 x 20000 pixels in its "header" and records whether its pixels are
        // ever decoded. A decode would succeed (it returns a 1 x 1 image), so only a check made from the
        // header, before decoding, keeps the thumbnail from being made.
        DeclaredSizeReaderSpi spi = new DeclaredSizeReaderSpi(20_000, 20_000);
        IIORegistry.getDefaultInstance().registerServiceProvider(spi, ImageReaderSpi.class);
        try {
            Path src = tmp.resolve("declared.png");
            Files.write(src, DeclaredSizeReaderSpi.MAGIC);

            Path result = thumbnail(src, IconSize.SIZE_64);

            assertTrue(spi.headerRead.get(), "precondition: the test reader handled the file");
            assertFalse(spi.decoded.get(), "no pixel may be decoded of an image above the pixel cap");
            assertNull(result, "an image whose header declares too many pixels must get no thumbnail");
            assertFalse(Files.exists(src.getParent().resolve("declared_thumb_64.png")));
        } finally {
            IIORegistry.getDefaultInstance().deregisterServiceProvider(spi, ImageReaderSpi.class);
        }
    }

    @Test
    public void largeImageIsDecodedSubsampledAndKeepsTheExactThumbnailSize() throws Exception {
        // 1500 x 700 with a 64 box is decoded with subsampling (1500 / (64 * 4) = 5) and must still come
        // out 64 x 30, the size the whole image would have given (d = 64/1500, 700 * d = 29.9 -> 30).
        Path src = writePng("wide-large.png", 1500, 700);

        Path thumb = thumbnail(src, IconSize.SIZE_64);

        assertNotNull(thumb);
        BufferedImage out = ImageIO.read(thumb.toFile());
        assertEquals(64, out.getWidth());
        assertEquals(30, out.getHeight());
        // The source is solid red, so the subsampled thumbnail is red all over, edges included
        assertEquals(Color.RED.getRGB(), out.getRGB(0, 0));
        assertEquals(Color.RED.getRGB(), out.getRGB(63, 29));
    }

    // ----------------------------------------------------------------------
    // helpers
    // ----------------------------------------------------------------------

    /**
     * An image reader for files that start with {@link #MAGIC}: it reports the given size as its header
     * and decodes any such file to a 1 x 1 image, recording that it did.
     */
    private static final class DeclaredSizeReaderSpi extends ImageReaderSpi {

        static final byte[] MAGIC = "NIORD-DECLARED-SIZE-IMAGE".getBytes(StandardCharsets.US_ASCII);

        final AtomicBoolean headerRead = new AtomicBoolean();
        final AtomicBoolean decoded = new AtomicBoolean();
        private final int width;
        private final int height;

        DeclaredSizeReaderSpi(int width, int height) {
            this.width = width;
            this.height = height;
            this.vendorName = "niord-test";
            this.version = "1";
            this.names = new String[]{"niord-declared-size"};
            this.pluginClassName = DeclaredSizeReaderSpi.class.getName();
            this.inputTypes = new Class<?>[]{ImageInputStream.class};
        }

        @Override
        public boolean canDecodeInput(Object source) throws IOException {
            if (!(source instanceof ImageInputStream in)) {
                return false;
            }
            byte[] head = new byte[MAGIC.length];
            in.mark();
            try {
                in.readFully(head);
                return Arrays.equals(head, MAGIC);
            } catch (EOFException e) {
                return false;
            } finally {
                in.reset();
            }
        }

        @Override
        public String getDescription(Locale locale) {
            return "Test reader of a declared image size";
        }

        @Override
        public ImageReader createReaderInstance(Object extension) {
            return new ImageReader(this) {
                @Override
                public int getNumImages(boolean allowSearch) {
                    return 1;
                }

                @Override
                public int getWidth(int imageIndex) {
                    headerRead.set(true);
                    return width;
                }

                @Override
                public int getHeight(int imageIndex) {
                    headerRead.set(true);
                    return height;
                }

                @Override
                public Iterator<ImageTypeSpecifier> getImageTypes(int imageIndex) {
                    return List.of(ImageTypeSpecifier.createFromBufferedImageType(BufferedImage.TYPE_INT_RGB))
                            .iterator();
                }

                @Override
                public IIOMetadata getStreamMetadata() {
                    return null;
                }

                @Override
                public IIOMetadata getImageMetadata(int imageIndex) {
                    return null;
                }

                @Override
                public BufferedImage read(int imageIndex, ImageReadParam param) {
                    decoded.set(true);
                    return new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
                }
            };
        }
    }

    /**
     * A syntactically valid PNG whose IHDR claims the given dimensions (8-bit RGB) but whose image data
     * is a few bytes: the shape of a decompression bomb, without the bomb.
     */
    private static byte[] pngClaiming(int width, int height) throws Exception {
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        png.write(new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'});

        ByteBuffer ihdr = ByteBuffer.allocate(13);
        ihdr.putInt(width).putInt(height)
                .put((byte) 8)   // bit depth
                .put((byte) 2)   // colour type RGB
                .put((byte) 0)   // compression
                .put((byte) 0)   // filter
                .put((byte) 0);  // interlace
        writeChunk(png, "IHDR", ihdr.array());

        Deflater deflater = new Deflater();
        deflater.setInput(new byte[16]);
        deflater.finish();
        byte[] buffer = new byte[64];
        int length = deflater.deflate(buffer);
        deflater.end();
        writeChunk(png, "IDAT", Arrays.copyOf(buffer, length));

        writeChunk(png, "IEND", new byte[0]);
        return png.toByteArray();
    }

    private static void writeChunk(ByteArrayOutputStream out, String type, byte[] data) throws Exception {
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        out.write(ByteBuffer.allocate(4).putInt(data.length).array());
        out.write(typeBytes);
        out.write(data);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        out.write(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
    }

    // ----------------------------------------------------------------------
    // The image's own refusal against the drawing's failure. createThumbnail
    // remembers the first with a marker beside the thumbnail's place, so the
    // row is not read and decoded on every request; the second is tried again
    // on the next request, so a vips outage or a full volume marks nothing.
    // The two are told apart by the exception the core throws.
    // ----------------------------------------------------------------------

    @Test
    public void aFileNoReaderDecodesIsTheImagesRefusal() throws Exception {
        Path src = tmp.resolve("notes.png");
        Files.writeString(src, "not an image at all");

        ThumbnailService.ImageRefusedException refused = assertThrows(ThumbnailService.ImageRefusedException.class,
                () -> draw(src), "a file without an image reader is the image's own refusal");
        assertTrue(refused.getMessage().contains("No image reader"), refused.getMessage());
        assertFalse(Files.exists(thumbOf(src)), "nothing is drawn");
    }

    @Test
    public void anImageWhosePixelsCannotBeDecodedIsTheImagesRefusal() throws Exception {
        // A PNG cut short after its header: the reader is found and reports the size, the decode fails
        Path whole = writePng("whole.png", 120, 120);
        Path src = tmp.resolve("truncated.png");
        Files.write(src, Arrays.copyOf(Files.readAllBytes(whole), 40));

        assertThrows(ThumbnailService.ImageRefusedException.class, () -> draw(src),
                "an image whose pixels cannot be read is the image's own refusal");
        assertFalse(Files.exists(thumbOf(src)), "nothing is drawn");
    }

    @Test
    public void anImageAboveThePixelCapIsTheImagesRefusal() throws Exception {
        Path src = writePng("large.png", 200, 200);
        service.maxThumbnailSourcePixels = 200L * 200L - 1;

        IOException e = assertThrows(IOException.class, () -> draw(src));
        assertTrue(e instanceof ThumbnailService.ImageRefusedException,
                "above the pixel cap is the image's refusal, remembered: " + e.getClass().getSimpleName());
    }

    @Test
    public void aThumbnailThatCannotBeWrittenIsTheDrawingsFailureNotTheImages() throws Exception {
        // The thumbnail's folder is taken by a plain file, so the drawing cannot create it: the deployment's
        // failure (a full or wrong volume looks the same), which must not be remembered against the image
        Path src = writePng("fine.png", 200, 200);
        Path blocker = tmp.resolve("blocked");
        Files.writeString(blocker, "a file where the cache folder should be");
        Path thumb = blocker.resolve("fine_thumb_64.png");

        IOException e = assertThrows(IOException.class,
                () -> service.createThumbnailFile(src, Files.getLastModifiedTime(src).toInstant(), thumb, "image/png",
                        IconSize.SIZE_64));
        assertFalse(e instanceof ThumbnailService.ImageRefusedException,
                "a write that fails is not the image's refusal: " + e);
    }

    /** Draws the thumbnail of the file beside it, letting the core's exception through */
    private Path draw(Path src) throws IOException {
        return service.createThumbnailFile(src, Files.getLastModifiedTime(src).toInstant(), thumbOf(src), "image/png",
                IconSize.SIZE_64);
    }

    /** The thumbnail's place beside the file, under the service's name */
    private Path thumbOf(Path src) {
        String name = src.getFileName().toString();
        return src.resolveSibling(FilenameUtils.getBaseName(name) + "_thumb_64." + FilenameUtils.getExtension(name));
    }

    /**
     * The core's answer for a file: the thumbnail under the service's name next to the file, or null when it
     * cannot be drawn, as {@link ThumbnailService#createThumbnail(RepoFileInfo, String, IconSize)} answers.
     */
    private Path thumbnail(Path src, IconSize size) {
        String name = src.getFileName().toString();
        Path thumb = src.resolveSibling(FilenameUtils.getBaseName(name) + "_thumb_" + size.getSize()
                + "." + FilenameUtils.getExtension(name));
        try {
            return service.createThumbnailFile(src, Files.getLastModifiedTime(src).toInstant(), thumb, "image/png", size);
        } catch (IOException e) {
            return null;
        }
    }

    /** Writes an opaque RGB PNG of the given dimensions into the temp folder and returns its path. */
    private Path writePng(String name, int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, w, h);
        g.dispose();
        Path file = tmp.resolve(name);
        ImageIO.write(img, "png", file.toFile());
        return file;
    }

    private static void setField(Object o, String name, Object v) throws Exception {
        Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(o, v);
    }
}
