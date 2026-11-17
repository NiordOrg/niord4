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

import org.springframework.core.io.AbstractResource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * A repository file as a Spring {@link org.springframework.core.io.Resource}, for a controller to return.
 * <p>
 * Spring MVC answers a {@code Range} request on a resource body with resource regions, skipping to the region's
 * start on the stream: the first stream reads the row whole, in one query ({@link RepoStore#readAll}), and every
 * stream the request opens afterwards serves those bytes again, skipping by offset.
 */
public class RepoFileResource extends AbstractResource {

    private final RepoStore store;
    private final RepoFileInfo file;

    public RepoFileResource(RepoStore store, RepoFileInfo file) {
        this.store = store;
        this.file = file;
    }

    /** The row the resource serves */
    public RepoFileInfo file() {
        return file;
    }

    @Override
    public String getDescription() {
        return "repository file [" + file.path() + "]";
    }

    /**
     * The row's bytes, read once for the resource's life, which is one request: Spring's region writer opens the
     * stream again for every range that starts before its position, so a request of many ranges would otherwise
     * read the row once per range
     */
    private byte[] data;

    @Override
    public InputStream getInputStream() throws IOException {
        if (data == null) {
            data = store.readAll(file);
        }
        return new ByteArrayInputStream(data);
    }

    @Override
    public boolean exists() {
        return true;
    }

    @Override
    public boolean isReadable() {
        return true;
    }

    @Override
    public long contentLength() {
        return file.size();
    }

    @Override
    public long lastModified() {
        return file.updated().toEpochMilli();
    }

    @Override
    public String getFilename() {
        return file.name();
    }
}
