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

import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.lang3.StringUtils;
import org.niord.core.settings.annotation.Setting;
import org.niord.core.user.Roles;
import org.niord.core.util.WebUtils;
import org.niord.core.web.NiordWebException;
import org.niord.core.web.ServedContentHeaders;
import org.slf4j.Logger;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.apache.commons.io.FilenameUtils;

import java.io.InputStream;
import java.util.regex.Pattern;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static org.niord.core.settings.Setting.Type;

/**
 * The repository: streams its files and takes uploads into it.
 * <p>
 * The files are rows of the store ({@link RepoStore}); a repository path is the path a file had under the
 * repository root on disk, which is also the path in its URL and in the rows that name it, so the move into
 * the database changed no URL. Thumbnails are a cache on the volume ({@link RepoCache}), made on a miss.
 * <p>
 * The repository is public in as much as everybody can download every file under the served roots
 * ({@code messages/}, {@code publications/}, {@code temp/}; the archive of superseded publication editions,
 * {@code archive/}, answers like a missing file). Registered users with the "editor" role can upload files to a
 * sub-root of the repository, the temp root, and only there: both upload endpoints are confined to it. A temp
 * row expires a day after it was made and the hourly sweep deletes it. Files reach the committed folders only
 * when a message or publication is saved, through {@link #updateRepoFolderFromTempEditFolder}.
 * <p>
 * Every path a request names is normalised through {@link #resolveInRepo} or {@link #validateTempRepoPath},
 * which collapse {@code .} and {@code ..} and refuse a path that leaves the repository (respectively the temp
 * root). The checks are lexical, on strings: a path need not exist.
 */
@RestController
@RequestMapping("/rest/repo")
@PermitAll
@SuppressWarnings("unused")
public class RepositoryService {


    @Setting(value="repoCacheTimeout", defaultValue="5", description="Cache timeout of repo files in minutes.", type=Type.Integer)
    volatile Integer cacheTimeout;

    private static final Logger log = org.slf4j.LoggerFactory.getLogger(RepositoryService.class);

    private final RepoStore store;

    private final RepoCache cache;

    private final FileTypes fileTypes;

    private final ThumbnailService thumbnailService;

    RepositoryService(RepoStore store, RepoCache cache, FileTypes fileTypes, ThumbnailService thumbnailService) {
        this.store = store;
        this.cache = cache;
        this.fileTypes = fileTypes;
        this.thumbnailService = thumbnailService;
    }

    /**
     * Creates a URI from the repository path
     * @param repoPath the repository path
     * @return the URI for the file
     */
    public String getRepoUri(String repoPath) {
        return RepoPaths.FILE_URL_PREFIX + WebUtils.encodeURI(repoPath);
    }

    /**
     * Streams the file specified by the path
     * @param filePath the path
     * @param request the servlet request
     * @return the response
     */
    @GetMapping("/file/{*file}")
    public ResponseEntity<?> streamFile(@PathVariable("file") String filePath,
                                        HttpServletRequest request, HttpServletResponse response) throws IOException {

        String path = WebUtils.trimLeadingSlash(filePath);
        Optional<RepoFileInfo> file = servedFile(path);

        // A path outside the repository, or outside the served roots, answers exactly like a missing file
        if (file.isEmpty()) {
            log.warn("Failed streaming file: " + path);
            return fileNotFound();
        }
        RepoFileInfo info = file.get();

        // Set expiry to cacheTimeout minutes
        Date expirationDate = new Date(System.currentTimeMillis() + 1000L * 60L * cacheTimeout);

        // FileTypes.getContentType returns null for an unknown extension; JAX-RS defaulted such a file to
        // application/octet-stream, so reproduce that (MediaType.parseMediaType(null) would otherwise throw).
        MediaType mediaType = mediaTypeOf(info.name());

        // Check for an ETag match — a WEAK validator of the form W/"<updatedMillis>_<size>", matching
        // the wire format the JAX-RS EntityTag(value, true) produced (pinned by RepositoryRestServiceITTest).
        // The 304 carries the same security headers as the 200, since a cache replaces the stored ones with them.
        String etag = info.etag();
        if (new ServletWebRequest(request, response).checkNotModified(etag)) {
            // ETag match -> checkNotModified already set 304 + the ETag header; add the Expires header.
            log.trace("File unchanged. Return code 304");
            HttpHeaders headers = fileSecurityHeaders(mediaType, info.name());
            headers.setExpires(expirationDate.getTime());
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).headers(headers).build();
        }

        HttpHeaders headers = fileSecurityHeaders(mediaType, info.name());
        headers.setExpires(expirationDate.getTime());
        if ("HEAD".equals(request.getMethod())) {
            // The headers alone: Spring would run this handler for a HEAD and drop the body, after reading it all
            return ResponseEntity.ok()
                    .headers(headers)
                    .eTag(etag)
                    .contentType(mediaType)
                    .contentLength(info.size())
                    .build();
        }
        log.trace("Streaming file: " + info.path());
        return ResponseEntity.ok()
                .headers(headers)
                .eTag(etag)
                .contentType(mediaType)
                .body(new RepoFileResource(store, info));
    }

    /**
     * The row of a served repository path: a path that normalises, lies below a served root and has a row.
     * @param path the path a request named
     * @return the row, or empty
     */
    public Optional<RepoFileInfo> servedFile(String path) {
        String p = RepoPaths.served(path);
        return p == null ? Optional.empty() : store.stat(p);
    }

    /** The media type a file is served with, from its name; {@code application/octet-stream} for an unknown extension */
    MediaType mediaTypeOf(String fileName) {
        String mt = fileTypes.getContentType(Paths.get(fileName));
        return (mt != null) ? MediaType.parseMediaType(mt) : MediaType.APPLICATION_OCTET_STREAM;
    }

    /**
     * The headers that keep a repository file from running as active content on this origin.
     * <p>
     * A repository file is whatever an editor uploaded, served from the backend origin, which the
     * administration application and the identity provider may share. So every file is sent with
     * {@code nosniff}, and every file but a PDF with {@code Content-Security-Policy: sandbox} (no script,
     * no same-origin access), which is the policy the public site's nginx applies to the same files.
     * A PDF is the exception there too: no browser runs a PDF's script in the page's origin, and a
     * browser's PDF viewer need not accept a sandboxed document.
     * <p>
     * Only the types the applications show inline (raster images, PDF, audio and video) are sent inline.
     * Everything else, HTML, SVG and XML above all, is sent as an attachment, so opening it downloads it
     * rather than rendering it here. Either disposition names the file, which also keeps Spring MVC from
     * adding its own {@code inline;filename=f.txt} to a type it does not consider safe (a PDF, say).
     *
     * @param mediaType the content type the file is served with
     * @param fileName the name of the file, for the disposition
     * @return the headers to add to the response
     */
    public static HttpHeaders fileSecurityHeaders(MediaType mediaType, String fileName) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(ServedContentHeaders.X_CONTENT_TYPE_OPTIONS, ServedContentHeaders.NOSNIFF);
        if (!isPdf(mediaType)) {
            headers.set(ServedContentHeaders.CONTENT_SECURITY_POLICY, ServedContentHeaders.SANDBOX);
        }
        ContentDisposition.Builder disposition = isInlineType(mediaType)
                ? ContentDisposition.inline()
                : ContentDisposition.attachment();
        headers.setContentDisposition(disposition.filename(fileName, StandardCharsets.UTF_8).build());
        return headers;
    }

    /** Whether the media type is a PDF */
    private static boolean isPdf(MediaType mediaType) {
        return "application".equalsIgnoreCase(mediaType.getType()) && "pdf".equalsIgnoreCase(mediaType.getSubtype());
    }

    /** Whether a file of the media type is passive content that may be shown inline: a raster image, a PDF, audio or video */
    static boolean isInlineType(MediaType mediaType) {
        String type = mediaType.getType().toLowerCase(Locale.ROOT);
        String subtype = mediaType.getSubtype().toLowerCase(Locale.ROOT);
        if ("image".equals(type)) {
            // SVG is a document that can carry script, and so is any other XML-based image type
            return !subtype.contains("svg") && !subtype.endsWith("+xml");
        }
        return isPdf(mediaType) || "audio".equals(type) || "video".equals(type);
    }

    /**
     * The answer to a request for a file that does not exist, or that lies outside the repository.
     * <p>
     * Plain text and never the requested path: the path is chosen by the caller, and a body that echoes it
     * would let a crafted link inject markup into a page on this origin.
     */
    public static ResponseEntity<String> fileNotFound() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(ServedContentHeaders.X_CONTENT_TYPE_OPTIONS, ServedContentHeaders.NOSNIFF);
        headers.set(ServedContentHeaders.CONTENT_SECURITY_POLICY, ServedContentHeaders.SANDBOX);
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .headers(headers)
                .contentType(MediaType.TEXT_PLAIN)
                .body("File not found");
    }

    /**
     * Deletes the file specified by the path
     * @param filePath the path
     * @return the response
     */
    @DeleteMapping(value = "/file/{*file}", produces = "text/plain")
    @RolesAllowed(Roles.EDITOR)
    public ResponseEntity<String> deleteFile(@PathVariable("file") String filePath) {

        String path = WebUtils.trimLeadingSlash(filePath);

        // Only allow deletion of files below the "temp" root. The editor role is per-domain and the
        // repository is not domain-partitioned, so without this an editor could delete any file
        // (including another domain's committed message attachments). All legitimate deletions target
        // the current edit session's temp folder. validateTempRepoPath() also normalizes the path,
        // closing any "../" traversal outside the repository root.
        String p = validateTempRepoPath(path);

        if (!store.delete(p)) {
            log.warn("Failed deleting non-existing file: " + p);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("File not found: " + p);
        }
        // The thumbnails of the file go with it; a miss is made again, a stale one would be served
        thumbnailService.deleteThumbnails(p);
        log.info("Deleted file " + p);

        return ResponseEntity.ok("File deleted " + p);
    }

    /**
     * Returns the thumbnail to use for the file specified by the path: the cached thumbnail of an image, served
     * directly, or a redirect to the icon of the file's type.
     * @param filePath the path
     * @param size the icon size, either 32, 64 or 128
     * @return the thumbnail to use for the file specified by the path
     */
    @GetMapping("/thumb/{*file}")
    public ResponseEntity<?> getThumbnail(@PathVariable("file") String filePath,
                                          @RequestParam(name = "size", defaultValue = "64") int size,
                                          HttpServletRequest request, HttpServletResponse response)
            throws IOException, URISyntaxException {

        String path = WebUtils.trimLeadingSlash(filePath);
        IconSize iconSize = IconSize.getIconSize(size);
        Optional<RepoFileInfo> file = servedFile(path);

        // A path outside the repository answers exactly like a missing file
        if (file.isEmpty()) {
            log.warn("Failed streaming file: " + path);
            return fileNotFound();
        }

        // An image's thumbnail is a cached file on the volume, made on a miss, and is served from there
        Path thumbFile = thumbnailService.getThumbnail(file.get(), iconSize);
        if (thumbFile != null) {
            return cachedFile(thumbFile, request, response);
        }

        // Fall back to file type icons: getIcon returns a root-relative path ("/img/..."); JAX-RS prepended "../"
        // so Jersey would re-resolve against /rest. Under Spring MVC, build an absolute Location URL directly from
        // the root-relative path (resolving "../" against the request URI yields a wrong, double-slashed URL).
        String thumbUri = fileTypes.getIcon(Paths.get(file.get().name()), iconSize);
        log.trace("Redirecting to thumbnail: " + thumbUri);
        return ResponseEntity.status(HttpStatus.TEMPORARY_REDIRECT)
                .location(WebUtils.absoluteUrl(thumbUri))
                .build();
    }

    /**
     * Serves a cached derived file (a thumbnail, a generated map image) directly, with what {@code /file} sends:
     * the security headers, the repository's expiry and a weak ETag of the file's time, size and name. The name
     * is part of the ETag because every generated map image of one message carries the same time, and time and
     * size alone would barely tell the old image from the new one.
     *
     * @param file the cached file
     * @param request the request, for the conditional GET
     * @param response the response
     * @return the response: the file, or a 304
     */
    public ResponseEntity<?> cachedFile(Path file, HttpServletRequest request, HttpServletResponse response) throws IOException {
        return cachedFile(file, Duration.ofMinutes(cacheTimeout), request, response);
    }

    /** As {@link #cachedFile(Path, HttpServletRequest, HttpServletResponse)}, with the given expiry */
    public ResponseEntity<?> cachedFile(Path file, Duration expiry, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        if (!Files.isRegularFile(file)) {
            return fileNotFound();
        }
        String name = file.getFileName().toString();
        MediaType mediaType = mediaTypeOf(name);
        long size = Files.size(file);
        // The name is in the tag as a hash: the map-image endpoint serves first a superseded image and then the
        // current one from one URL, both with the message's time, and a tag with the name as text would need quoting
        String etag = "W/\"" + Files.getLastModifiedTime(file).toMillis() + "_" + size + "_"
                + Integer.toHexString(name.hashCode()) + "\"";
        Date expirationDate = new Date(System.currentTimeMillis() + expiry.toMillis());
        HttpHeaders headers = fileSecurityHeaders(mediaType, name);
        headers.setExpires(expirationDate.getTime());
        if (new ServletWebRequest(request, response).checkNotModified(etag)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).headers(headers).build();
        }
        return ResponseEntity.ok()
                .headers(headers)
                .eTag(etag)
                .contentType(mediaType)
                .body(new FileSystemResource(file));
    }

    /**
     * Returns a list of files in the folder specified by the path
     * @param folderPath the path
     * @return the list of files in the folder specified by the path
     */
    @GetMapping(value = "/list/{*folder}", produces = "application/json;charset=UTF-8")
    public List<RepoFileVo> listFiles(@PathVariable("folder") String folderPath) {

        final String path = WebUtils.trimLeadingSlash(folderPath);
        // Spring's {*folder} matches an empty capture (unlike JAX-RS {folder:.+}); reject a blank folder so
        // /rest/repo/list (no sub-path) stays a 404 rather than listing the repository root.
        if (StringUtils.isBlank(path)) {
            throw new NiordWebException(404);
        }
        String folder = resolveInRepo(path);
        if (folder == null) {
            // A folder outside the repository answers like the blank folder above
            throw new NiordWebException(404);
        }

        List<RepoFileVo> result = new ArrayList<>();
        if (!RepoPaths.isServed(folder)) {
            // Nothing is ever written outside the served roots, so such a folder lists empty, as any folder
            // without files does
            return result;
        }
        // Filter out hidden files, thumbnails and map images
        for (RepoFileInfo f : store.list(folder)) {
            if (!isListedFileName(f.name())) {
                continue;
            }
            RepoFileVo vo = new RepoFileVo();
            vo.setName(f.name());
            vo.setPath(WebUtils.encodeURI(f.path()));
            vo.setDirectory(false);
            vo.setUpdated(Date.from(f.updated()));
            vo.setSize(f.size());
            result.add(vo);
        }
        return result;
    }

    /**
     * Returns whether a folder listing shows a file of the given name. Every row is an upload (the thumbnails and
     * the generated map images are cached files, not rows), so all are listed but hidden files and the custom map
     * thumbnail an editor set through the map-image endpoint, {@code custom_thumb_<size>.png}.
     *
     * @param fileName the file name
     * @return whether the listing shows the file
     */
    static boolean isListedFileName(String fileName) {
        return !fileName.startsWith(".") && !CUSTOM_THUMBNAIL.matcher(fileName).matches();
    }

    /** The name of the custom map thumbnail of a message, as the map-image endpoint writes it */
    private static final Pattern CUSTOM_THUMBNAIL = Pattern.compile("custom_thumb_\\d{1,4}\\.png");

    /**
     * Handles upload of files.
     * <p>
     * Like {@code upload-temp}, this uploads to the "temp" root only. The editor role is per-domain and the
     * repository is not domain-partitioned, so an upload into a committed folder would let an editor of one
     * domain add files to another domain's messages and publications. Files reach a committed folder only when
     * the message or publication they belong to is saved.
     *
     * @param folderPath the folder to upload to
     * @param request the multi-part input request
     */
    @PostMapping(value = "/upload/{*folder}",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = "application/json;charset=UTF-8")
    @RolesAllowed(Roles.EDITOR)
    public List<String> uploadFile(@PathVariable("folder") String folderPath, MultipartHttpServletRequest request) throws IOException {

        String path = WebUtils.trimLeadingSlash(folderPath);
        // Spring's {*folder} matches an empty capture (unlike JAX-RS {folder:.+}); reject a blank folder so
        // POST /rest/repo/upload (no sub-path) stays a 404 instead of writing into the publicly-served repo root.
        if (StringUtils.isBlank(path)) {
            throw new NiordWebException(404);
        }
        // Only below the "temp" root (403 otherwise), normalised so no "../" leaves it
        String folder = validateTempRepoPath(path);
        return uploadRows(folder, request).stream().map(RepoFileInfo::path).toList();
    }

    /**
     * Writes the files of a multipart request into a temp folder, each under a name unique in the folder, and
     * returns their rows. The folder has been validated as a temp folder by the caller.
     *
     * @param folder the normalised temp folder
     * @param request the multipart request
     * @return the rows of the uploaded files
     */
    public List<RepoFileInfo> uploadRows(String folder, MultipartHttpServletRequest request) throws IOException {
        List<RepoFileInfo> result = new ArrayList<>();
        Instant now = Instant.now();
        for (List<MultipartFile> files : request.getMultiFileMap().values()) {
            for (MultipartFile file : files) {
                if (file == null) {
                    continue;
                }
                // The name is chosen by the client (a browser may send its path with it): reduce it to a bare name
                String fileName = WebUtils.bareFileName(file.getOriginalFilename());
                if (fileName == null) {
                    throw new NiordWebException("An uploaded file has no usable file name", 400);
                }
                // The first free name, decided by the database: two uploads of one name at once get two names
                RepoFileInfo written = null;
                for (int x = 1; written == null; x++) {
                    String destPath = RepoPaths.join(folder, x == 1 ? fileName : numberedName(fileName, x));
                    try (InputStream in = file.getInputStream()) {
                        written = store.writeNew(destPath, in, file.getSize(), now).orElse(null);
                    }
                }
                log.info("File " + fileName + " is uploaded to " + written.path());
                result.add(written);
            }
        }
        return result;
    }

    /**
     * Returns a unique path for a file name in the given folder.
     * If the given file name is not unique, a new is constructed by adding a number to the file name
     * @param folder the folder
     * @param name the file name
     * @return the path of the new, unique file
     */
    public String getUniquePath(String folder, String name) {
        String path = RepoPaths.join(folder, name);
        if (store.exists(path)) {
            for (int x = 2; true; x++) {
                path = RepoPaths.join(folder, numberedName(name, x));
                if (!store.exists(path)) {
                    break;
                }
            }
        }
        return path;
    }

    /** The name a file takes when its name is taken: {@code <base> <n>.<ext>} */
    static String numberedName(String name, int n) {
        return FilenameUtils.removeExtension(name) + " " + n + "." + FilenameUtils.getExtension(name);
    }

    /**
     * Returns a new unique "temp" directory. Please note, the directory has not yet been created.
     *
     * @return a new unique "temp" directory
     */
    @GetMapping(value = "/new-temp-dir", produces = "application/json;charset=UTF-8")
    public RepoFileVo getNewTempDir() {

        // Construct a unique directory name
        String name = UUID.randomUUID().toString();// test "f823b7d5-9559-4a76-b3a3-6d32f2bf55f2";

        RepoFileVo dir = new RepoFileVo();
        dir.setName(name);
        dir.setPath(WebUtils.encodeURI(RepoPaths.TEMP_ROOT + "/" + name));
        dir.setDirectory(true);
        return dir;
    }

    /**
     * Handles upload of files to the "temp" repo root.
     * Only the "editor" role is required to upload to the "temp" root
     *
     * @param folderPath the folder to upload to
     * @param request the multi-part input request
     */
    @PostMapping(value = "/upload-temp/{*folder}",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = "application/json;charset=UTF-8")
    @RolesAllowed(Roles.EDITOR)
    public List<String> uploadTempFile(@PathVariable("folder") String folderPath, MultipartHttpServletRequest request) throws IOException {

        String path = WebUtils.trimLeadingSlash(folderPath);
        // Spring's {*folder} matches an empty capture (unlike JAX-RS {folder:.+}); reject a blank folder so
        // POST /rest/repo/upload-temp (no sub-path) stays a 404.
        if (StringUtils.isBlank(path)) {
            throw new NiordWebException(404);
        }

        // Check that the specified folder is indeed under the "temp" root
        validateTempRepoPath(path);

        return uploadFile(path, request);
    }

    /**
     * Resolves the relative repository path as a temporary repository path and returns it normalised.
     * @param path the path to resolve and validate as a temporary repository path
     * @return the normalised path
     * @throws NiordWebException 403 when the path is not under the temp root
     */
    public String validateTempRepoPath(String path) {
        // normalize() collapses any "." / ".." segments so a traversal that escapes the temp root
        // (e.g. "temp/../escaped") no longer slips past the boundary check.
        String folder = resolveInRepo(path);
        if (folder == null || !RepoPaths.isTemp(folder)) {
            log.warn("Failed streaming file to temp root folder: " + (folder != null ? folder : path));
            throw new NiordWebException("Invalid upload folder: " + path, 403);
        }
        return folder;
    }

    /**
     * Normalises a repository-relative path, and returns it only when it stays inside the repository.
     * <p>
     * The path usually comes from a request. It is normalised before the boundary check, so neither a ".."
     * segment nor an absolute path reaches outside the repository, and a path the store cannot represent counts
     * as outside. The check does not depend on the firewall in front of the handlers.
     *
     * @param path the repository-relative path
     * @return the normalised path inside the repository root, or null when the path is null or leaves the root
     */
    public String resolveInRepo(String path) {
        return RepoPaths.normalize(path);
    }

    /**
     * Resolves the committed repository folder of a message or publication: inside the repository root, and
     * neither the root itself nor anything below the "temp" root.
     *
     * @param path the repository-relative folder path
     * @return the normalised folder
     * @throws NiordWebException 403 when the path is not such a folder
     */
    String validateRepoFolderPath(String path) {
        String folder = resolveInRepo(path);
        // Below a served root, and not the temp root: neither the root itself nor the archive, which is never served
        if (folder == null || !RepoPaths.isServed(folder) || RepoPaths.isTemp(folder)) {
            log.warn("Invalid repository folder: " + (folder != null ? folder : path));
            throw new NiordWebException("Invalid repository folder", 403);
        }
        return folder;
    }


    /**
     * Every hour, delete the temp rows that have expired, and the cached thumbnails of the temp root that are
     * as old
     */
    @Scheduled(cron="50 22 * * * ?")
    void cleanUpTempRoot() {
        store.sweepExpired();
        cache.sweepTemp(RepoStore.TEMP_LIFETIME);
    }

    /**
     * Creates a temporary repository folder for the given repository-backed value object
     * @param vo the message
     * @param copyToTemp whether to copy all resources to the associated temporary directory or not
     */
    public void createTempEditRepoFolder(IRepoBackedVo vo, boolean copyToTemp) {

        String editRepoPath = getNewTempDir().getPath();
        vo.setEditRepoPath(editRepoPath);

        if (copyToTemp) {

            // For existing messages, copy the existing message repo to the new repository. The copies get
            // their own time, so they expire a day from now whatever their source's dates.
            if (StringUtils.isNotBlank(vo.getRepoPath())) {
                String srcPath = resolveInRepo(vo.getRepoPath());
                if (srcPath != null && !srcPath.isEmpty() && store.hasAny(srcPath)) {
                    log.info("Copy folder " + srcPath + " to temporary folder " + editRepoPath);
                    store.copyFolder(srcPath, editRepoPath);
                }
            }

            // Point any embedded links and images to the temporary repository folder
            vo.rewriteRepoPath(vo.getRepoPath(), vo.getEditRepoPath());
        }
    }

    /**
     * Copy new files from the temporary edit-repo path to the actual repo folder associated with the value object
     * @param vo the value object to update
     */
    public void updateRepoFolderFromTempEditFolder(IRepoBackedVo vo) {

        if (vo != null && StringUtils.isNotBlank(vo.getRepoPath()) && StringUtils.isNotBlank(vo.getEditRepoPath())) {

            // The edit folder arrives with the request body, so it must be a folder below the "temp" root, and
            // not that root itself, which holds every user's uploads and extracted archives.
            // The destination must be a committed folder: inside the repository, outside "temp" (the REST
            // callers also replace the body's repoPath with the persisted entity's). 403 otherwise.
            String srcPath = validateTempRepoPath(vo.getEditRepoPath());
            if (srcPath.equals(RepoPaths.TEMP_ROOT)) {
                log.warn("Invalid edit folder: the temp root itself");
                throw new NiordWebException("Invalid edit folder", 403);
            }
            String dstPath = validateRepoFolderPath(vo.getRepoPath());
            String revision = String.valueOf(vo.getRevision());

            if (store.hasAny(srcPath)) {

                // Case 1: If this is a new publication, copy the entire directory
                if (!store.hasAny(dstPath)) {
                    log.info("Syncing folder " + srcPath + " with " + dstPath);
                    store.copyFolder(srcPath, dstPath);

                    // Case 2: Copy the latest revision sub-folder back to the source folder
                } else if (store.hasAny(srcPath + "/" + revision)) {
                    log.info("Syncing revision " + revision + " of folder " + srcPath + " with folder " + dstPath);
                    store.copyFolder(srcPath + "/" + revision, dstPath + "/" + revision);
                } else {
                    log.info("No new revision files to sync");
                }
            }
        }
    }
}
