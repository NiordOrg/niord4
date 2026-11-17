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

import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.apache.commons.io.FilenameUtils;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The repository files, as rows of {@code "RepoFile"}: every file the repository keeps (message attachments and
 * embedded images, custom map thumbnails, publication files, edit sessions, the archive of superseded editions).
 * <p>
 * The table has no entity, and this is the only class that reads or writes it. A JPA {@code byte[]} would put the
 * whole file on the heap at insert, and a JDBC delete or upsert beside an entity would leave stale instances in the
 * session; so the store runs on a {@link JdbcTemplate} over the same {@code DataSource}, which joins the JPA
 * transaction around a write through Spring's transaction synchronisation. A read is one query for the row's
 * bytes and holds no transaction of its own: the files are small (a publication PDF of a few MB at most, an
 * attachment under the {@link #MAX_SIZE} cap), so a file is read whole and served from memory.
 * <p>
 * Every write knows its length before the row is written, so the driver streams the bytes
 * ({@code setBinaryStream} with the length) and no whole file reaches the heap that was not there already. A write
 * is an upsert on the path: a save that copies an edit session over a committed folder replaces a row at the same
 * path, and two overlapping saves end last-write-wins rather than in a primary-key violation.
 */
@Service
public class RepoStore {

    /**
     * The most bytes a row holds: a file is read whole and served from memory, so the cap bounds what a download
     * costs. The multipart limit refuses a larger upload first; the import reports a larger file and leaves it
     */
    public static final long MAX_SIZE = 50L << 20;

    /** How long a row under the temp root lives after its write: an edit session, an upload, an extracted archive */
    public static final Duration TEMP_LIFETIME = Duration.ofDays(1);

    /** The most paths one {@link #existing} query asks for */
    private static final int LOOKUP_BATCH = 1000;

    private static final Logger log = LoggerFactory.getLogger(RepoStore.class);

    private static final String COLUMNS = "path, folder, name, size, sha256, created, updated, expires";

    private static final String UPSERT_TAIL = " ON CONFLICT (path) DO UPDATE SET folder = EXCLUDED.folder,"
            + " name = EXCLUDED.name, size = EXCLUDED.size, sha256 = EXCLUDED.sha256, updated = EXCLUDED.updated,"
            + " expires = EXCLUDED.expires, data = EXCLUDED.data";

    private static final RowMapper<RepoFileInfo> INFO = (rs, rowNum) -> new RepoFileInfo(
            rs.getString("path"), rs.getString("folder"), rs.getString("name"), rs.getLong("size"),
            rs.getString("sha256"), instant(rs, "created"), instant(rs, "updated"), instant(rs, "expires"));

    @Inject
    JdbcTemplate jdbc;

    // ------------------------------------------------------------------------------------------------ reads

    /** The row of a path, without its bytes; empty for a path the store cannot hold */
    public Optional<RepoFileInfo> stat(String path) {
        String key = RepoPaths.normalize(path);
        if (key == null || key.isEmpty()) {
            return Optional.empty();
        }
        List<RepoFileInfo> rows = jdbc.query("SELECT " + COLUMNS + " FROM \"RepoFile\" WHERE path = ?", INFO, key);
        return rows.stream().findFirst();
    }

    /** Whether a path has a row */
    public boolean exists(String path) {
        return stat(path).isPresent();
    }

    /** Whether any row lies strictly below the folder */
    public boolean hasAny(String folder) {
        Boolean any = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM \"RepoFile\" WHERE path LIKE ? ESCAPE '!')",
                Boolean.class, RepoPaths.likePrefix(folderKey(folder)));
        return Boolean.TRUE.equals(any);
    }

    /** The files directly in a folder, by name */
    public List<RepoFileInfo> list(String folder) {
        return jdbc.query("SELECT " + COLUMNS + " FROM \"RepoFile\" WHERE folder = ? ORDER BY name", INFO,
                folderKey(folder));
    }

    /** Every row at or below a folder, by path; for the export and the import's checks */
    public List<RepoFileInfo> listTree(String folder) {
        return jdbc.query("SELECT " + COLUMNS + " FROM \"RepoFile\" WHERE path LIKE ? ESCAPE '!' ORDER BY path",
                INFO, RepoPaths.likePrefix(folderKey(folder)));
    }

    /**
     * Which of the given paths have a row: one query per thousand paths, for the import's verification.
     *
     * @param paths the paths to look up
     * @return those of them that have a row
     */
    public Set<String> existing(Collection<String> paths) {
        Set<String> keys = new HashSet<>();
        for (String path : paths) {
            String key = RepoPaths.normalize(path);
            if (key != null && !key.isEmpty()) {
                keys.add(key);
            }
        }
        List<String> all = List.copyOf(keys);
        Set<String> found = new HashSet<>();
        for (int from = 0; from < all.size(); from += LOOKUP_BATCH) {
            String[] batch = all.subList(from, Math.min(all.size(), from + LOOKUP_BATCH)).toArray(String[]::new);
            found.addAll(jdbc.query(con -> {
                var ps = con.prepareStatement("SELECT path FROM \"RepoFile\" WHERE path = ANY(?)");
                ps.setArray(1, con.createArrayOf("varchar", batch));
                return ps;
            }, (rs, rowNum) -> rs.getString(1)));
        }
        return found;
    }

    /**
     * A file's bytes, in one query, with the row's {@code updated} pinned: a row replaced in place or deleted since
     * the caller looked it up is not served under the look-up's length and time.
     *
     * @param file the row, as looked up
     * @return its bytes
     * @throws IOException when the row is gone or was replaced since
     */
    public byte[] readAll(RepoFileInfo file) throws IOException {
        List<byte[]> rows = jdbc.query("SELECT data FROM \"RepoFile\" WHERE path = ? AND updated = ?",
                (rs, rowNum) -> rs.getBytes(1), file.path(), offsetDateTime(file.updated()));
        if (rows.isEmpty()) {
            throw new IOException("The repository file " + file.path() + " was replaced or deleted since it was looked up");
        }
        return rows.get(0);
    }

    /** Copies a file's bytes to a stream */
    public void copyTo(RepoFileInfo file, OutputStream out) throws IOException {
        out.write(readAll(file));
    }

    /**
     * Writes a file's bytes to a spool file under the JVM's temporary folder, for a generator that needs a file
     * (vips is a process, ImageIO wants a stream). The caller deletes it.
     */
    public Path spool(RepoFileInfo file) throws IOException {
        Path spool = Files.createTempFile("niord-repo-", spoolSuffix(file.name()));
        try {
            Files.write(spool, readAll(file));
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(spool);
            throw e;
        }
        return spool;
    }

    // ----------------------------------------------------------------------------------------------- writes

    /**
     * Writes a file, replacing a row at the same path, in one statement: the server hashes the bytes and returns
     * the row. A row under the temp root expires {@link #TEMP_LIFETIME} after the write; any other never.
     *
     * @param path the normalised repository path
     * @param in the bytes, which the driver streams to the server
     * @param length the number of bytes
     * @param updated the file's time: now for an upload, the message's change time for a generated image, the
     *                file's modification time for an import
     * @return the row as written
     */
    @Transactional
    public RepoFileInfo write(String path, InputStream in, long length, Instant updated) {
        String key = key(path);
        requireWithinCap(key, length);
        String folder = RepoPaths.folderOf(key);
        String name = RepoPaths.nameOf(key);
        Instant now = Instant.now();
        Instant expires = expiresFor(key, now);
        List<RepoFileInfo> written = jdbc.query(con -> {
            var ps = con.prepareStatement("INSERT INTO \"RepoFile\" (" + COLUMNS + ", data)"
                    + " SELECT ?, ?, ?, ?, encode(sha256(d), 'hex'), ?, ?, ?, d FROM (VALUES (?::bytea)) v(d)"
                    + UPSERT_TAIL + " RETURNING " + COLUMNS);
            ps.setString(1, key);
            ps.setString(2, folder);
            ps.setString(3, name);
            ps.setLong(4, length);
            ps.setObject(5, offsetDateTime(now));
            ps.setObject(6, offsetDateTime(updated));
            ps.setObject(7, expires == null ? null : offsetDateTime(expires));
            ps.setBinaryStream(8, in, length);
            return ps;
        }, INFO);
        return written.stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("The write of " + path + " returned no row"));
    }

    /**
     * Writes a file at a path that has no row yet, and writes nothing when it has: an upload takes the first free
     * name, and two uploads of one name at once get two names, since the conflict is decided by the database.
     *
     * @return the row as written, or empty when the path has a row
     */
    @Transactional
    public Optional<RepoFileInfo> writeNew(String path, InputStream in, long length, Instant updated) {
        String key = key(path);
        requireWithinCap(key, length);
        Instant now = Instant.now();
        Instant expires = expiresFor(key, now);
        return jdbc.query(con -> {
            var ps = con.prepareStatement("INSERT INTO \"RepoFile\" (" + COLUMNS + ", data)"
                    + " SELECT ?, ?, ?, ?, encode(sha256(d), 'hex'), ?, ?, ?, d FROM (VALUES (?::bytea)) v(d)"
                    + " ON CONFLICT (path) DO NOTHING RETURNING " + COLUMNS);
            ps.setString(1, key);
            ps.setString(2, RepoPaths.folderOf(key));
            ps.setString(3, RepoPaths.nameOf(key));
            ps.setLong(4, length);
            ps.setObject(5, offsetDateTime(now));
            ps.setObject(6, offsetDateTime(updated));
            ps.setObject(7, expires == null ? null : offsetDateTime(expires));
            ps.setBinaryStream(8, in, length);
            return ps;
        }, INFO).stream().findFirst();
    }

    /** Writes a file from bytes already in memory (a rendered PDF, a scaled thumbnail) */
    @Transactional
    public RepoFileInfo write(String path, byte[] bytes, Instant updated) {
        return write(path, new ByteArrayInputStream(bytes), bytes.length, updated);
    }

    /**
     * Writes a file from a stream whose length is not known (a report fetched over HTTP, an archive entry without a
     * size): spooled to a file first, since a write needs its length. The spooling opens no transaction of its own,
     * so a slow source holds no connection of the pool while it is read: the row is written afterwards, by the
     * sized write, which is one statement -- atomic by itself and, since it is a call within this bean, part of the
     * caller's transaction when there is one and auto-committed when there is none.
     */
    public RepoFileInfo write(String path, InputStream in, Instant updated) throws IOException {
        Path spool = Files.createTempFile("niord-repo-", spoolSuffix(RepoPaths.nameOf(path)));
        try {
            Files.copy(in, spool, StandardCopyOption.REPLACE_EXISTING);
            try (InputStream spooled = Files.newInputStream(spool)) {
                return write(path, spooled, Files.size(spool), updated);
            }
        } finally {
            Files.deleteIfExists(spool);
        }
    }

    /**
     * The key a path is stored under: the path normalised, so that a caller's spelling (a doubled slash, a dot
     * segment, a trailing slash) and a request's resolve to the same row.
     *
     * @throws IllegalArgumentException for a path the store cannot hold: null, the root, an absolute path or one
     *         that climbs out of the repository
     */
    private static String key(String path) {
        String key = RepoPaths.normalize(path);
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException("Not a path of the repository: " + path);
        }
        return key;
    }

    /** A folder's key: as {@link #key}, and the root is no folder of rows either */
    private static String folderKey(String folder) {
        return key(folder);
    }

    /**
     * The suffix of a spool file: the file's extension when it is a short plain one (vips and ImageIO sniff the
     * content, so it is a courtesy), never the whole name, which may exceed what the file system allows for a
     * temp file's name
     */
    private static String spoolSuffix(String name) {
        String ext = FilenameUtils.getExtension(name);
        return ext.length() > 0 && ext.length() <= 10 && ext.chars().allMatch(Character::isLetterOrDigit)
                ? "." + ext : ".spool";
    }

    /** A file above {@link #MAX_SIZE} is refused before a byte is sent */
    private static void requireWithinCap(String path, long length) {
        if (length > MAX_SIZE) {
            throw new IllegalArgumentException("The file " + path + " is " + length + " bytes, above the store's cap of "
                    + MAX_SIZE + " bytes");
        }
    }

    /** When a row written now at the path expires: {@link #TEMP_LIFETIME} from now under the temp root, else never */
    private static Instant expiresFor(String path, Instant now) {
        return RepoPaths.isTemp(path) ? now.plus(TEMP_LIFETIME) : null;
    }

    /** Deletes a file; false when there was none */
    @Transactional
    public boolean delete(String path) {
        return jdbc.update("DELETE FROM \"RepoFile\" WHERE path = ?", key(path)) > 0;
    }

    /** Deletes every row strictly below a folder; the number deleted */
    @Transactional
    public int deleteFolder(String folder) {
        return jdbc.update("DELETE FROM \"RepoFile\" WHERE path LIKE ? ESCAPE '!'",
                RepoPaths.likePrefix(folderKey(folder)));
    }

    /**
     * Copies every row strictly below one folder to the same place below another, replacing rows at the paths it
     * lands on. The bytes are copied inside the database and never cross the network; each copy keeps its
     * {@code updated}, as a file copy keeps the file's date, gets {@code created} now, and expires as a row written
     * now at its path would (an edit session's copies a day from now, a save's never).
     *
     * @param from the source folder
     * @param to the destination folder
     * @return the number of rows copied
     */
    @Transactional
    public int copyFolder(String from, String to) {
        from = folderKey(from);
        to = folderKey(to);
        Instant expires = expiresFor(to, Instant.now());
        int cut = from.length() + 1;
        return jdbc.update("INSERT INTO \"RepoFile\" (" + COLUMNS + ", data)"
                        + " SELECT ? || substr(path, ?), ? || substr(folder, ?), name, size, sha256, now(), updated, ?, data"
                        + " FROM \"RepoFile\" WHERE path LIKE ? ESCAPE '!'" + UPSERT_TAIL,
                to, cut, to, cut, expires == null ? null : offsetDateTime(expires), RepoPaths.likePrefix(from));
    }

    /**
     * Copies one row to another path, never replacing a row there: the archive of a superseded edition, and an
     * archive that overwrites is not an archive. Keeps {@code updated}; the copy expires as a row written now at
     * its path would.
     *
     * @return whether the copy was made: false when there is no row to copy, or a row at the target already
     */
    @Transactional
    public boolean copyFile(String from, String to) {
        String target = key(to);
        Instant expires = expiresFor(target, Instant.now());
        return jdbc.update("INSERT INTO \"RepoFile\" (" + COLUMNS + ", data)"
                        + " SELECT ?, ?, ?, size, sha256, now(), updated, ?, data FROM \"RepoFile\" WHERE path = ?"
                        + " ON CONFLICT (path) DO NOTHING",
                target, RepoPaths.folderOf(target), RepoPaths.nameOf(target),
                expires == null ? null : offsetDateTime(expires), key(from)) > 0;
    }

    /** Deletes the rows whose {@code expires} has passed; the number deleted */
    @Transactional
    public int sweepExpired() {
        int deleted = jdbc.update("DELETE FROM \"RepoFile\" WHERE expires < now()");
        if (deleted > 0) {
            log.debug("Deleted {} expired repository rows", deleted);
        }
        return deleted;
    }

    // ---------------------------------------------------------------------------------------------- helpers

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime time = rs.getObject(column, OffsetDateTime.class);
        return time == null ? null : time.toInstant();
    }

    /** The database's precision is the microsecond: a time is truncated to it, so what is written is what is read */
    static OffsetDateTime offsetDateTime(Instant instant) {
        return OffsetDateTime.ofInstant(instant.truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The SHA-256 of a stream in hex, as the rows hold it; the stream is read to its end and closed */
    static String sha256Hex(InputStream in) throws IOException {
        MessageDigest digest = sha256();
        try (InputStream digesting = new DigestInputStream(in, digest)) {
            digesting.transferTo(OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

}
