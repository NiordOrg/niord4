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
package org.niord.core.publication.series;

import org.niord.core.settings.annotation.Setting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Where the publication previews live: a cache on the application's volume, which nothing backs up.
 * <p>
 * A publication's own files and the archive of its superseded editions are rows of the repository store
 * ({@link org.niord.core.repo.RepoStore}), the archive under a root no repository endpoint serves
 * ({@link IssueArchiveService}); the preview of an open issue is the one publication file that stays on disk,
 * found by listing its folder and swept after seven days ({@link IssuePreviewService}). The preview folder is
 * created at start, when the settings are in.
 */
@Service
public class PublicationPathService {

    private static final Logger log = LoggerFactory.getLogger(PublicationPathService.class);

    @Setting(value = "publicationPreviewRootPath", defaultValue = "${niord.home}/publication-preview", editable = false,
            description = "Root directory for publication previews: a cache on the application's volume, swept after"
                    + " seven days. Deployment configuration, not on the Settings page: set by an environment variable or system property of this name, or the settings file.")
    Path previewRoot;


    @EventListener(ApplicationReadyEvent.class)
    void init() {
        if (previewRoot == null) {
            throw new IllegalStateException("publicationPreviewRootPath is not configured");
        }
        try {
            Files.createDirectories(previewRoot);
        } catch (IOException e) {
            throw new IllegalStateException("could not create " + previewRoot + ": " + e.getMessage());
        }
        log.info("Publication preview root: {}", previewRoot);
    }

    public Path previewRoot() {
        return previewRoot;
    }

    /** Where a preview generation is rendered to. */
    public Path previewPathFor(String issuePublicId, String lang, long generation, String fileName) {
        return previewRoot.resolve(issuePublicId).resolve(lang).resolve(generation + "-" + fileName);
    }
}
