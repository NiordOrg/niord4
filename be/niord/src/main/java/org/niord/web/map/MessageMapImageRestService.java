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

import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.io.IOUtils;
import org.niord.core.repo.RepoCache;
import org.niord.core.repo.RepoPaths;
import org.niord.core.repo.RepositoryService;
import org.niord.core.user.Roles;
import org.niord.core.util.WebUtils;
import org.niord.core.web.NiordWebException;
import org.niord.web.map.MessageMapImageSnapshots.Snapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartHttpServletRequest;

import org.niord.core.repo.RepoStore;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import java.util.Optional;

/**
 * Returns the map thumbnail image associated with a message.
 * Can be used e.g. for a grid layout in search results.
 * <p>
 * Either a standard map image is generated and returned, or the user may
 * upload a custom map image thumbnail.
 * <p>
 * The URL to fetch an image can either be specified via a message ID or a temporary repository path.
 * The latter is used when messages is being edited.
 * <p>
 * A generated image is a cache file on the application's volume, in the message folder's place under the cache
 * tree ({@link RepoCache}), and is served from there; a custom thumbnail an editor set is a repository file, a row
 * of the store, and is reached by a redirect to the repository. The two endpoints that take a custom thumbnail
 * write it as a row of the edit session, which the save commits like any upload.
 * <p>
 * No handler runs in a transaction. The image request reads the message through
 * {@link MessageMapImageSnapshots}, in a short transaction of its own, and looks at files, fetches tiles and draws
 * with no database connection held; the uploads write their row in a transaction of the store's own.
 * <p>
 * The image request is anonymous and checks no status: it answers for every message its uid names, a draft or a
 * deleted message included, and draws the image on a miss, base-map tiles fetched included. So anyone who knows a
 * uid can see that message's geometry as a picture. This is a known and accepted exposure, not an oversight: a
 * uid is a UUID, so it cannot be guessed; the public site never calls this endpoint (its proxy forwards only the
 * repository files); and the administration application calls it signed in. Whoever changes any of those three
 * facts, or lets a uid reach an anonymous page, should add the status check at the same time.
 */
@RestController
@RequestMapping(MessageMapImageRestService.PATH)
@PermitAll
public class MessageMapImageRestService {

    /** The address the map images are served from, generated and custom thumbnails alike */
    public static final String PATH = "/rest/message-map-image";

    static final int CACHE_TIMEOUT_MINUTES = 10;

    /**
     * How long a browser may keep an image drawn with an earlier portrayal, which is served while
     * the current one is drawn in the background
     */
    static final int SUPERSEDED_CACHE_TIMEOUT_SECONDS = 60;

    /**
     * The classpath resource of the placeholder image, which is also served as the static file
     * {@code /img/map_image_placeholder.png}
     */
    static final String PLACEHOLDER_RESOURCE = "/META-INF/resources/img/map_image_placeholder.png";

    /** The response header that marks the placeholder answer, with the value {@link #PLACEHOLDER} */
    public static final String IMAGE_KIND_HEADER = "X-Niord-Map-Image";

    /** The {@link #IMAGE_KIND_HEADER} value of the placeholder answer */
    public static final String PLACEHOLDER = "placeholder";

    static final String UPLOADED_IMAGE_PREFIX = "data:image/png;base64,";

    private static final Duration CURRENT_EXPIRY = Duration.ofMinutes(CACHE_TIMEOUT_MINUTES);
    private static final Duration SUPERSEDED_EXPIRY = Duration.ofSeconds(SUPERSEDED_CACHE_TIMEOUT_SECONDS);

    private static final Logger log = LoggerFactory.getLogger(MessageMapImageRestService.class);

    private final RepositoryService repositoryService;

    private final RepoStore store;

    private final RepoCache repoCache;

    private final MessageMapImageSnapshots snapshots;

    private final MessageMapImageGenerator messageMapImageGenerator;

    private final MapImageRegenerator regenerator;

    /** The placeholder image, read once */
    private final byte[] placeholder;

    MessageMapImageRestService(RepositoryService repositoryService, RepoStore store, RepoCache repoCache,
                               MessageMapImageSnapshots snapshots, MessageMapImageGenerator messageMapImageGenerator,
                               MapImageRegenerator regenerator) {
        this.repositoryService = repositoryService;
        this.store = store;
        this.repoCache = repoCache;
        this.snapshots = snapshots;
        this.messageMapImageGenerator = messageMapImageGenerator;
        this.regenerator = regenerator;
        this.placeholder = readPlaceholder();
    }

    /** Reads the placeholder image from the classpath */
    private static byte[] readPlaceholder() {
        try (InputStream in = MessageMapImageRestService.class.getResourceAsStream(PLACEHOLDER_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("The map image placeholder " + PLACEHOLDER_RESOURCE + " is missing");
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the map image placeholder " + PLACEHOLDER_RESOURCE, e);
        }
    }

    /**
     * Returns the map thumbnail image associated with the message with the given UID: the image itself from the
     * cache tree, a redirect to a custom thumbnail in the repository, or the placeholder image when there is none
     * (a message without a geometry, an image that cannot be drawn, a failure), marked with
     * {@link #IMAGE_KIND_HEADER}. An unknown UID is a 404. The image is, in this order:
     * <ol>
     *   <li>the custom thumbnail an editor set;</li>
     *   <li>the generated image drawn with the current portrayal for the message as it is now (its time is the
     *   message's change time);</li>
     *   <li>a generated image drawn with an earlier portrayal (or named without a version) for the message as it is
     *   now, with a short expiry, while the current one is drawn in the background;</li>
     *   <li>the generated image, drawn now while the request waits.</li>
     * </ol>
     * @param uid the UID of the message
     * @return the map thumbnail image
     */
    @GetMapping(value = "/{uid}.png", produces = "image/png")
    public ResponseEntity<?> getMessageMapImage(@PathVariable("uid") String uid,
                                                HttpServletRequest request, HttpServletResponse response) {
        // While map image requests are served, as when a list view loads a page of images, the images drawn in the
        // background wait (MapImageRegenerator)
        regenerator.requestStarted();
        try {
            return mapImage(uid, request, response);
        } finally {
            regenerator.requestEnded();
        }
    }

    private ResponseEntity<?> mapImage(String uid, HttpServletRequest request, HttpServletResponse response) {
        try {
            Optional<Snapshot> found = snapshots.snapshot(uid);

            // Check if a custom map image is defined: a repository file an editor set, so it is contained in the
            // repository (one outside it, or one that has no row, is ignored)
            if (found.isPresent() && found.get().thumbnailPath() != null) {
                String imageRepoPath = RepoPaths.served(found.get().thumbnailPath());
                if (imageRepoPath != null && store.exists(imageRepoPath)) {
                    return redirect(imageRepoPath, CURRENT_EXPIRY);
                }
                // The custom thumbnail is missing: the generated image is served, which needs the geometry
                found = snapshots.snapshotWithGeometry(uid);
            }

            if (found.isEmpty()) {
                return ResponseEntity
                        .status(HttpServletResponse.SC_NOT_FOUND)
                        .body("Message map image not found for message: " + uid);
            }

            // Check for a standard auto-generated message map image file
            if (found.get().hasGeometry()) {
                ResponseEntity<?> generated = generatedImage(found.get(), request, response);
                if (generated != null) {
                    return generated;
                }
            }

        } catch (Exception ex) {
            log.warn("Error fetching map image for message: " + ex);
        }

        return placeholderImage();
    }

    /**
     * Returns the placeholder image itself, not a redirect to its static file: a redirect names the file on the
     * request's host, which has it only when the backend serves the page, so the image would not load through a
     * front that forwards {@code /rest} alone, such as the administration app's dev server. It expires as a current
     * image does.
     */
    private ResponseEntity<Object> placeholderImage() {
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .header(IMAGE_KIND_HEADER, PLACEHOLDER)
                .headers(headers -> headers.setExpires(expiryTime(CURRENT_EXPIRY).getTime()))
                .body(placeholder);
    }

    /** The generated images of a message live in the message folder's place under the cache tree */
    Path imageFolder(Snapshot snapshot) {
        return repoCache.pathFor(snapshot.repoPath());
    }

    /**
     * Returns the message's generated map image, drawing the image first when there is none of the message as it
     * is now, or null when the message has nothing to draw
     */
    private ResponseEntity<?> generatedImage(Snapshot snapshot, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        Path current = imageFolder(snapshot).resolve(messageMapImageGenerator.imageFileName());

        // The image drawn with the current portrayal for the message as it is now: it carries the message's change
        // time, so a message edited since, and a database restored to an earlier state, name another drawing
        if (MapImageFiles.modifiedMillis(current) == snapshot.updatedMillis()) {
            return cachedImage(current, CURRENT_EXPIRY, request, response);
        }

        // An image drawn with an earlier portrayal for the message as it is now is served while the current one
        // is drawn in the background, which also reads where its tiles come from
        Path superseded = superseded(current, snapshot.updatedMillis());
        if (superseded != null) {
            try {
                if (!regenerator.inFlight(current)) {
                    regenerator.refresh(snapshot.request(snapshots::tileSource), current);
                }
            } catch (RuntimeException e) {
                log.warn("Could not start drawing the map image " + current + ": " + e);
            }
            return cachedImage(superseded, SUPERSEDED_EXPIRY, request, response);
        }

        // No image shows the message as it is now: draw it while the request waits
        if (regenerator.generateNow(snapshot.request(snapshots::tileSource), current)) {
            return cachedImage(current, CURRENT_EXPIRY, request, response);
        }
        return null;
    }

    /**
     * Returns a generated image of the current one's size under another name that was drawn for the given time, or
     * null when there is none. The image under the name without a version is looked at first, which is
     * all a request needs to look at while the folders hold the images made before the version became part of the
     * name; only when it is missing or older is the folder listed, for the most recent image of another version.
     */
    private static Path superseded(Path current, long updatedMillis) throws IOException {
        Path unversioned = MapImageFiles.unversioned(current);
        if (unversioned != null && drawnMillis(unversioned) == updatedMillis) {
            return unversioned;
        }
        return freshestSuperseded(current, updatedMillis);
    }

    /**
     * Returns the generated image of the current one's size under another name that was drawn for the given time,
     * the first by name when there are several, or null when there is none. Lists the folder once.
     */
    private static Path freshestSuperseded(Path current, long updatedMillis) throws IOException {
        Path freshest = null;
        long freshestMillis = Long.MIN_VALUE;
        for (Path other : MapImageFiles.others(current)) {
            long millis = drawnMillis(other);
            if (millis == updatedMillis && millis > freshestMillis) {
                freshest = other;
                freshestMillis = millis;
            }
        }
        return freshest;
    }

    /** Returns the modification time of an image file, or {@link Long#MIN_VALUE} when there is no such file */
    private static long drawnMillis(Path image) throws IOException {
        try {
            BasicFileAttributes attributes = Files.readAttributes(image, BasicFileAttributes.class);
            return attributes.isRegularFile() ? attributes.lastModifiedTime().toMillis() : Long.MIN_VALUE;
        } catch (NoSuchFileException e) {
            return Long.MIN_VALUE;
        }
    }

    /**
     * Serves a generated image from the cache tree, with the endpoint's own expiry: ten minutes for a current
     * image and a minute for one drawn with an earlier portrayal, so that browsers ask again soon after the
     * background redraw. The headers are otherwise the repository's for a file (the security headers, a weak ETag
     * of the file's time, size and name).
     */
    private ResponseEntity<?> cachedImage(Path image, Duration expiry, HttpServletRequest request,
                                          HttpServletResponse response) throws IOException {
        return repositoryService.cachedFile(image, expiry, request, response);
    }

    /**
     * Returns a redirect to a repository file, the custom thumbnail an editor set
     **/
    private ResponseEntity<Object> redirect(String imageRepoPath, Duration expiry) {
        // Redirect to the repository streaming service. getRepoUri is root-relative ("/rest/repo/file/...");
        // build an absolute Location URL (JAX-RS absolutized "../" + uri against /rest).
        String uri = repositoryService.getRepoUri(imageRepoPath);
        HttpHeaders headers = new HttpHeaders();
        headers.setLocation(WebUtils.absoluteUrl(uri));
        headers.setExpires(expiryTime(expiry).getTime());
        return new ResponseEntity<>(headers, HttpStatus.TEMPORARY_REDIRECT);
    }

    /** Returns the time the given duration from now **/
    private static Date expiryTime(Duration expiry) {
        return new Date(System.currentTimeMillis() + expiry.toMillis());
    }

    /**
     * Updates the map image with a custom image
     */
    @PutMapping(value = "/{*folder}", consumes = "application/json;charset=UTF-8", produces = "text/plain")
    @RolesAllowed(Roles.EDITOR)
    public String updateMessageMapImage(@PathVariable("folder") String path, @RequestBody String image) throws Exception {

        // The multi-segment folder path arrives as a Spring {*folder} capture-the-rest variable (leading '/').
        path = WebUtils.trimLeadingSlash(path);

        // Validate that the path is a temporary repository folder path
        String folder = repositoryService.validateTempRepoPath(path);

        if (!image.toLowerCase().startsWith(UPLOADED_IMAGE_PREFIX)) {
            throw new NiordWebException(400);
        }

        // Decode the base-64 encoded image data
        image = image.substring(UPLOADED_IMAGE_PREFIX.length());
        byte[] data = Base64.getDecoder().decode(image);

        return storeCustomThumbnail(folder, data);
    }

    /**
     * Called to upload a custom message map image via a multipart form-data request
     *
     * @param request the multi-part form-data request
     * @return a status
     */
    @PostMapping(value = "/{*folder}", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE, produces = "text/plain")
    @RolesAllowed(Roles.EDITOR)
    public String uploadMessageMapImage(@PathVariable("folder") String path, MultipartHttpServletRequest request) throws Exception {

        // The multi-segment folder path arrives as a Spring {*folder} capture-the-rest variable (leading '/').
        path = WebUtils.trimLeadingSlash(path);

        // Extract the uploaded files keyed by their original file name
        final Map<String, InputStream> inputFiles = WebUtils.getMultipartFiles(request);

        // Validate that the path is a temporary repository folder path
        String folder = repositoryService.validateTempRepoPath(path);

        // Get hold of the first uploaded publication file
        InputStream imageStream = inputFiles.values().stream()
                .findFirst()
                .orElseThrow(() -> new NiordWebException("No uploaded message image file", 400));

        byte[] data;
        try {
            data = IOUtils.toByteArray(imageStream);
        } catch (IOException e) {
            log.error("Error reading the uploaded message image", e);
            throw new NiordWebException("Error reading the uploaded message image", 400);
        }
        return storeCustomThumbnail(folder, data);
    }

    /**
     * Scales the uploaded image to the custom thumbnail and writes it as a row of the edit session, which the save
     * commits with the message's other files. The thumbnail is the only copy of itself, so it is a repository file
     * and never a cache file.
     *
     * @param folder the validated temp folder
     * @param data the uploaded image
     * @return the thumbnail's repository path
     */
    private String storeCustomThumbnail(String folder, byte[] data) throws IOException {
        String imageName = String.format("custom_thumb_%d.png", messageMapImageGenerator.getMapImageSize());
        String imageRepoPath = folder + "/" + imageName;
        byte[] png = messageMapImageGenerator.scaleCustomThumbnail(data);
        if (png == null) {
            log.error("Failed generating image thumbnail from uploaded image: not an image");
            throw new NiordWebException("The uploaded data is not an image", 400);
        }
        Instant now = Instant.now();
        store.write(imageRepoPath, png, now);
        log.info("Generated image thumbnail from uploaded image: " + imageRepoPath);
        return imageRepoPath;
    }
}
