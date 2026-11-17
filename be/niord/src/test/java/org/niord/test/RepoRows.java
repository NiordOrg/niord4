/*
 * Copyright 2026 Danish Maritime Authority.
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
package org.niord.test;

import org.niord.core.repo.RepoFileInfo;
import org.niord.core.repo.RepoStore;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * Text rows of the repository store, for the tests that stage a file and read one back: a row's bytes as UTF-8
 * text, and a text as a row written now.
 */
public final class RepoRows {

    private RepoRows() {
    }

    /** The row's bytes as UTF-8 text; an assertion error when there is no row */
    public static String readText(RepoStore store, String path) {
        RepoFileInfo file = store.stat(path).orElseThrow(() -> new AssertionError("no row at " + path));
        try {
            return new String(store.readAll(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Writes the text as the row's bytes, with the time now */
    public static RepoFileInfo writeText(RepoStore store, String path, String text) {
        return store.write(path, text.getBytes(StandardCharsets.UTF_8), Instant.now());
    }
}
