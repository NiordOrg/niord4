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

import java.time.Instant;

/**
 * A repository file's row, without its bytes: what a listing, a check or an ETag needs.
 *
 * @param path the repository path
 * @param folder the parent folder
 * @param name the file name
 * @param size the size in bytes
 * @param sha256 the SHA-256 of the bytes, hex; null while a write is in progress
 * @param created when the row was made
 * @param updated when the bytes were last written, which the ETag prints and a cached thumbnail is compared with
 * @param expires when the hourly sweep may delete the row; null for a committed file
 */
public record RepoFileInfo(String path, String folder, String name, long size, String sha256,
                           Instant created, Instant updated, Instant expires) {

    /** Whether the row belongs to an edit session, an upload or an extracted archive under the temp root */
    public boolean isTemp() {
        return expires != null;
    }

    /** The weak ETag of the file, {@code W/"<updatedMillis>_<size>"}, the wire form the repository has always used */
    public String etag() {
        return "W/\"" + updated.toEpochMilli() + "_" + size + "\"";
    }
}
