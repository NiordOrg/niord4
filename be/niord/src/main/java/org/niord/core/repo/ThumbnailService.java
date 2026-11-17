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

import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.niord.core.settings.annotation.Setting;
import org.niord.core.util.MdcContext;
import org.slf4j.Logger;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.nio.file.StandardCopyOption;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Creates thumbnails
 * <p>
 *     An ExecutorService is used to limit load on the system: two threads and a bounded queue. A request that
 *     finds the queue full gets no thumbnail, which the caller answers with the file-type icon.
 * </p>
 * <p>
 *     The plain-Java path reads an image's dimensions from its header and refuses one above
 *     {@link #DEFAULT_MAX_THUMBNAIL_SOURCE_PIXELS} pixels before decoding it, and decodes a large image
 *     subsampled, so neither a file that declares huge dimensions nor a genuinely large image makes
 *     the thumbnail endpoint allocate a raster of the whole image.
 * </p>
 */
@Service
public class ThumbnailService {

    private static final int EXECUTOR_POOL_SIZE = 2;

    /** Thumbnail jobs that may wait for one of the pool's threads; more are refused */
    private static final int EXECUTOR_QUEUE_CAPACITY = 100;

    /**
     * The largest image, in pixels, the plain-Java path makes a thumbnail of: 100 megapixels, which covers
     * camera photos and scanned charts.
     */
    static final long DEFAULT_MAX_THUMBNAIL_SOURCE_PIXELS = 100_000_000L;

    /** A large image is decoded subsampled to about this many times the thumbnail size */
    private static final int SUBSAMPLED_SIZE_FACTOR = 4;

    org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ThumbnailService.class);

    /** The pixel cap of the plain-Java path; package-private so a test can lower it */
    long maxThumbnailSourcePixels = DEFAULT_MAX_THUMBNAIL_SOURCE_PIXELS;

        @Setting(value = "vipsPath", description = "Path to vips command on Linux. Use for faster thumbnail generation."
            + " Read at start-up.")
    String vipsCmd;

    Set<String> vipsFileTypes = new HashSet<>();

    private final FileTypes fileTypes;

    private final RepoStore store;

    private final RepoCache cache;

    ThumbnailService(FileTypes fileTypes, RepoStore store, RepoCache cache) {
        this.fileTypes = fileTypes;
        this.store = store;
        this.cache = cache;
    }

    private ExecutorService processPool;

    @PostConstruct
    private void init() {
        // A fixed pool, as Executors.newFixedThreadPool, but with a bounded queue that refuses the excess
        processPool = new ThreadPoolExecutor(
                EXECUTOR_POOL_SIZE, EXECUTOR_POOL_SIZE,
                0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(EXECUTOR_QUEUE_CAPACITY),
                new ThreadPoolExecutor.AbortPolicy());

        // Enlist image types supported by vips (avoid gif)
        vipsFileTypes.add("image/bmp");
        vipsFileTypes.add("image/jpeg");
        vipsFileTypes.add("image/jpg");
        vipsFileTypes.add("image/tiff");
        vipsFileTypes.add("image/tif");
        vipsFileTypes.add("image/png");
    }

    @PreDestroy
    private void closeDown() {
        if (processPool != null && !processPool.isShutdown()) {
            processPool.shutdown();
            processPool = null;
        }
    }

    /**
     * Returns or creates the a thumbnail for the given file if it is an image.
     * Otherwise, null is returned
     *
     * @param file the file to create a thumbnail for
     * @param size the size of the thumbnail
     * @return the thumbnail file or null if none was found or created
     */
    public Path getThumbnail(final RepoFileInfo file, final IconSize size) {

        final String type = fileTypes.getContentType(Paths.get(file.name()));
        if (type == null || !type.startsWith("image")) {
            log.debug("File not an image: " + file.path());
            return null;
        }

        // Submit the thumbnail creation job to the process pool.
        // Please note, this is used as a way of restricting load on the system,
        // not per se as a way of executing the task asynchronously.
        //
        // processPool is a plain java.util.concurrent pool, so Spring's MDC TaskDecorator never
        // sees it. The submit is blocking and the caller is a request thread, so without the wrap
        // the thumbnail work — and anything it logs — would silently lose the request it belongs to.
        try {
            Callable<Path> task = () -> createThumbnail(file, type, size);
            return processPool.submit(MdcContext.wrap(task)).get();
        } catch (InterruptedException | ExecutionException e) {
            log.debug("Error creating thumbnail");
        } catch (RejectedExecutionException e) {
            log.debug("Thumbnail queue full, no thumbnail for " + file);
        }

        return null;
    }

    /**
     * Returns or creates the a thumbnail for the given file if it is an image.
     * Otherwise, null is returned
     *
     * @param file the file to create a thumbnail for
     * @param type the type of image
     * @param size the size of the thumbnail
     * @return the thumbnail file or null if none was found or created
     */
    public Path createThumbnail(RepoFileInfo file, String type, IconSize size) {

        try {
            // The thumbnail is a cached file on the volume, named by the row's content, so it is the row's as long
            // as it exists; a refused image (above the pixel cap, undecodable) left a marker, so it is not read again
            Path thumbFile = cache.thumbnailFor(file, size);
            if (Files.isRegularFile(thumbFile)) {
                return thumbFile;
            }
            Path refused = refusedMarker(thumbFile);
            if (Files.isRegularFile(refused)) {
                return null;
            }

            // A thumbnail the import moved into the cache, of the days the repository was on disk: adopted when it
            // was drawn for the row's content, that is at or after the row's time
            Path legacy = cache.legacyThumbnailFor(file, size);
            if (Files.isRegularFile(legacy) && isFresh(legacy, file.updated())) {
                Files.createDirectories(thumbFile.getParent());
                Files.move(legacy, thumbFile, StandardCopyOption.REPLACE_EXISTING);
                return thumbFile;
            }

            // The generators need a file (vips is a process; ImageIO wants a stream): spool the row, and delete
            // the spool whatever happens. A spool that fails is the database's or the disk's, and is tried again
            Path spool = store.spool(file);
            try {
                return drawThumbnail(spool, file.updated(), thumbFile, type, size);
            } catch (ImageRefusedException e) {
                // The image itself is refused (above the pixel cap, or no reader decodes it): remembered, so the
                // row is not read and decoded on every request. Any other failure of the drawing -- vips missing or
                // failing, a full volume, an interrupted thread -- is the deployment's, and is tried again
                Files.createDirectories(refused.getParent());
                Files.write(refused, new byte[0]);
                return null;
            } finally {
                Files.deleteIfExists(spool);
            }

        } catch (IOException e) {
            // Alas, no thumbnail
            return null;
        }
    }

    /** The marker beside a thumbnail's place that says its image was refused */
    private static Path refusedMarker(Path thumbFile) {
        return thumbFile.resolveSibling(thumbFile.getFileName() + ".refused");
    }

    /**
     * Draws the thumbnail of an image file into the given file, unless that file exists and is at least as new
     * as the image's time. The thumbnail gets the image's time as its own, so the check holds next time.
     *
     * @param file the image file
     * @param fileTime the image's time: its row's {@code updated}, or a file's modification time
     * @param thumbFile the thumbnail file to write
     * @param type the content type of the image
     * @param size the size of the thumbnail
     * @return the thumbnail file
     * @throws IOException when the thumbnail cannot be drawn or written; an {@link ImageRefusedException} when the
     *         image itself is refused (no reader decodes it; above the pixel cap, an {@link ImageTooLargeException})
     */
    Path createThumbnailFile(Path file, Instant fileTime, Path thumbFile, String type, IconSize size)
            throws IOException {
        return isFresh(thumbFile, fileTime) ? thumbFile : drawThumbnail(file, fileTime, thumbFile, type, size);
    }

    /**
     * Whether a thumbnail exists and is at least as new as its source's time: a file written again (a save that
     * replaced it) is drawn again on the next request
     */
    private static boolean isFresh(Path thumbFile, Instant fileTime) throws IOException {
        return Files.isRegularFile(thumbFile)
                && Files.getLastModifiedTime(thumbFile).toMillis() >= fileTime.toEpochMilli();
    }

    /** Draws the thumbnail with vips or Java, and stamps it with the source's time */
    private Path drawThumbnail(Path file, Instant fileTime, Path thumbFile, String type, IconSize size)
            throws IOException {
        Path folder = thumbFile.getParent();
        if (folder != null) {
            Files.createDirectories(folder);
        }
        if (StringUtils.isNotBlank(vipsCmd) && vipsFileTypes.contains(type.toLowerCase())) {
            createThumbnailUsingVips(file, thumbFile, size);
        } else {
            createThumbnailUsingJava(file, thumbFile, size);
        }
        // Stamped with the later of the source's time and now: fresh against the source, and aged from the drawing
        // for the temp cache's sweep, so an edit session's copies of old files are not drawn again every hour
        Instant now = Instant.now();
        Files.setLastModifiedTime(thumbFile, FileTime.from(now.isAfter(fileTime) ? now : fileTime));
        return thumbFile;
    }

    /**
     * Deletes the cached thumbnails of a repository file, in every size: the file was deleted, and a thumbnail
     * of a file that is gone would be served until its row came back with an older time.
     *
     * @param repoPath the repository path of the file
     */
    public void deleteThumbnails(String repoPath) {
        for (Path thumbFile : cache.thumbnailsOf(repoPath)) {
            try {
                Files.deleteIfExists(thumbFile);
            } catch (IOException e) {
                log.debug("Could not delete the thumbnail " + thumbFile + ": " + e);
            }
        }
    }

    /**
     * Creates a thumbnail for the image file using libvips
     *
     * @param file      the image file
     * @param thumbFile the resulting thumbnail file
     * @param size      the size of the thumbnail
     */
    private void createThumbnailUsingVips(Path file, Path thumbFile, IconSize size) throws IOException {

        // Example command: vipsthumbnail -s 64 -p bilinear -o thumb.png image2.jpg
        final Process proc = new ProcessBuilder(
                vipsCmd,
                "-s", String.valueOf(size.getSize()),
                "-p", "bilinear",
                "-o", thumbFile.toString(),
                file.toString())
                .directory(new File(System.getProperty("user.dir")))
                .redirectErrorStream(true)
                .start();

        try {
            // What vips says goes to the log, read before the wait: a process whose output nobody reads blocks on
            // a full pipe
            try (BufferedReader br = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    log.debug(line);
                }
            }
            int exitValue = proc.waitFor();
            log.debug("Exit Value is " + exitValue);
            if (exitValue != 0) {
                throw new IOException("vips exited " + exitValue + " for " + file);
            }
        } catch (InterruptedException e) {
            // The pool is shutting down: vips does not outlive it, and the flag stays set so the worker's loop
            // sees the interrupt too
            proc.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while vips drew the thumbnail of " + file, e);
        } catch (IOException e) {
            log.error("Error creating thumbnail for image " + file, e);
            throw e;
        }
    }

    /**
     * Creates a thumbnail for the image file using plain old java
     * @param file the image file
     * @param thumbFile the resulting thumbnail file
     * @param size the size of the thumbnail
     */
    private void createThumbnailUsingJava(Path file, Path thumbFile, IconSize size) throws IOException {

        ImageReader reader = null;
        try (ImageInputStream in = ImageIO.createImageInputStream(file.toFile())) {
            Iterator<ImageReader> readers = (in != null) ? ImageIO.getImageReaders(in) : null;
            if (readers == null || !readers.hasNext()) {
                throw new ImageRefusedException("No image reader for " + file);
            }
            reader = readers.next();
            reader.setInput(in, true, true);

            // The dimensions come from the header alone. A small file can declare an image far larger than
            // it holds, and decoding it would allocate the whole raster, so refuse one above the pixel cap
            // before anything is decoded. createThumbnail turns the refusal into the file-type icon.
            int w;
            int h;
            int subsampling;
            BufferedImage image;
            try {
                w = reader.getWidth(0);
                h = reader.getHeight(0);
                if ((long) w * (long) h > maxThumbnailSourcePixels) {
                    throw new ImageTooLargeException("Image " + file + " is " + w + "x" + h
                            + " pixels, above the thumbnail limit of " + maxThumbnailSourcePixels + " pixels");
                }

                // Decode only every n-th pixel of a large image, so the decoded raster stays a few times the
                // thumbnail box whatever the size of the source. An image up to that size is decoded whole.
                ImageReadParam param = reader.getDefaultReadParam();
                subsampling = Math.max(1, Math.max(w, h) / (size.getSize() * SUBSAMPLED_SIZE_FACTOR));
                if (subsampling > 1) {
                    param.setSourceSubsampling(subsampling, subsampling, 0, 0);
                }
                image = reader.read(0, param);
            } catch (ImageRefusedException e) {
                throw e;
            } catch (IOException | RuntimeException e) {
                // The header or the pixels cannot be read: the image's own fault, which a retry would repeat
                throw new ImageRefusedException("Image " + file + " cannot be decoded: " + e);
            }
            if (image == null) {
                throw new ImageRefusedException("Image " + file + " cannot be decoded");
            }

            // Never scale up
            if (w <= size.getSize() && h <= size.getSize()) {
                FileUtils.copyFile(file.toFile(), thumbFile.toFile());

            } else {
                // Compute the scale factor
                double dx = (double)size.getSize() / (double)w;
                double dy = (double)size.getSize() / (double)h;
                double d = Math.min(dx, dy);
                int thumbWidth = (int)Math.round(w * d);
                int thumbHeight = (int)Math.round(h * d);

                // Create the thumbnail. A subsampled image is scaled to the thumbnail's exact dimensions,
                // so the thumbnail comes out the size it would have had from the whole image.
                BufferedImage thumbImage = new BufferedImage(
                        thumbWidth,
                        thumbHeight,
                        BufferedImage.TYPE_INT_RGB);
                Graphics2D g2d = thumbImage.createGraphics();
                AffineTransform at = (subsampling > 1)
                        ? AffineTransform.getScaleInstance(
                                (double) thumbWidth / image.getWidth(), (double) thumbHeight / image.getHeight())
                        : AffineTransform.getScaleInstance(d, d);
                g2d.drawRenderedImage(image, at);
                g2d.dispose();

                // Save the thumbnail
                ImageIO.write(
                        thumbImage,
                        FilenameUtils.getExtension(thumbFile.getFileName().toString()),
                        thumbFile.toFile());

                // Releas resources
                thumbImage.flush();
            }
            image.flush();

        } catch (ImageRefusedException e) {
            // The image's own refusal, which createThumbnail remembers with a marker: one line, no stack trace
            log.warn(e.getMessage());
            throw e;
        } catch (Exception e) {
            // The drawing's failure (the scaling, the write), which is tried again on the next request
            log.error("Error creating thumbnail for image " + file, e);
            throw new IOException(e);
        } finally {
            if (reader != null) {
                reader.dispose();
            }
        }
    }

    /**
     * The image itself is refused -- no reader decodes it, or it is above the pixel cap -- which a retry would only
     * repeat, so {@link #createThumbnail} remembers it with a marker. Any other failure of the drawing is not one
     * of these, and is tried again on the next request.
     */
    static class ImageRefusedException extends IOException {
        ImageRefusedException(String message) {
            super(message);
        }
    }

    /** An image whose header declares more pixels than the thumbnail limit */
    static class ImageTooLargeException extends ImageRefusedException {
        ImageTooLargeException(String message) {
            super(message);
        }
    }
}
