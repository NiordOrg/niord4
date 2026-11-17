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
package org.niord.core.message.vo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.niord.model.message.AttachmentVo;
import org.niord.model.message.MessagePartDescVo;
import org.niord.model.message.MessagePartType;
import org.niord.model.message.MessagePartVo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Framework-neutral unit tests for the repository-path rewriting behaviour of {@link SystemMessageVo}:
 * {@link SystemMessageVo#toEditRepo()}, {@link SystemMessageVo#toMessageRepo()},
 * {@code rewriteRepoPath(String, String)} and the private {@code fixDescriptionRepoPaths()} reached through
 * {@code toMessageRepo()}.
 *
 * <p>When a message is opened for editing its repository folder is copied to a temporary location, so every
 * stored repository path (attachment paths, the system thumbnail path and repository URLs embedded in the rich
 * text description of each message part) must be rewritten to point at the temporary edit folder, and rewritten
 * back when the edit is saved. These pure in-memory transformations require no Quarkus container, database or
 * CDI, so the value objects are built with {@code new} and the rewrites are asserted directly on their state.
 */
public class MessageRepoPathRewriteTest {

    /** Permanent repository folder of the message. */
    private static final String REPO_PATH = "messages/a/ab/uid";

    /** Temporary repository folder used while editing. */
    private static final String EDIT_REPO_PATH = "temp/uuid";

    private SystemMessageVo message;
    private AttachmentVo attachment;
    private MessagePartDescVo desc;

    @BeforeEach
    public void setUp() {
        message = new SystemMessageVo();
        message.setRepoPath(REPO_PATH);
        message.setEditRepoPath(EDIT_REPO_PATH);
        message.setThumbnailPath(REPO_PATH + "/thumb.png");

        attachment = new AttachmentVo();
        attachment.setPath(REPO_PATH + "/img.png");
        message.checkCreateAttachments().add(attachment);

        // A DETAILS part whose description embeds a link to a file in the permanent repo folder.
        MessagePartVo part = new MessagePartVo();
        part.setType(MessagePartType.DETAILS);
        desc = new MessagePartDescVo();
        desc.setLang("en");
        desc.setDetails("<a href=\"/rest/repo/file/" + REPO_PATH + "/img.png\">img</a>");
        part.checkCreateDescs().add(desc);
        message.checkCreateParts().add(part);
    }

    /**
     * {@code toEditRepo()} rewrites every stored permanent-repo path - the attachment path, the system
     * thumbnail path and the repo URL embedded in the part description - to the temporary edit folder.
     */
    @Test
    public void toEditRepo_rewritesAttachmentThumbnailAndDescToEditFolder() {
        message.toEditRepo();

        assertEquals(EDIT_REPO_PATH + "/img.png", attachment.getPath(),
                "attachment path must point at the edit folder");
        assertEquals(EDIT_REPO_PATH + "/thumb.png", message.getThumbnailPath(),
                "thumbnail path must point at the edit folder");
        assertEquals("<a href=\"/rest/repo/file/" + EDIT_REPO_PATH + "/img.png\">img</a>", desc.getDetails(),
                "embedded description link must point at the edit folder");

        // Sanity: nothing still references the permanent folder after switching to the edit folder.
        assertTrue(attachment.getPath().startsWith(EDIT_REPO_PATH),
                "attachment path should start with the edit folder");
        assertTrue(message.getThumbnailPath().startsWith(EDIT_REPO_PATH),
                "thumbnail path should start with the edit folder");
    }

    /**
     * After switching to the edit folder, {@code toMessageRepo()} rewrites the attachment path, the thumbnail
     * path and the embedded description link back to the permanent repository folder.
     */
    @Test
    public void toMessageRepo_rewritesEditFolderBackToPermanentRepo() {
        message.toEditRepo();
        // Precondition: everything now points at the edit folder.
        assertTrue(attachment.getPath().startsWith(EDIT_REPO_PATH));

        message.toMessageRepo();

        assertEquals(REPO_PATH + "/img.png", attachment.getPath(),
                "attachment path must be restored to the permanent repo");
        assertEquals(REPO_PATH + "/thumb.png", message.getThumbnailPath(),
                "thumbnail path must be restored to the permanent repo");
        assertEquals("<a href=\"/rest/repo/file/" + REPO_PATH + "/img.png\">img</a>", desc.getDetails(),
                "embedded description link must be restored to the permanent repo");

        assertTrue(attachment.getPath().startsWith(REPO_PATH),
                "attachment path should start with the permanent repo folder");
        assertTrue(message.getThumbnailPath().startsWith(REPO_PATH),
                "thumbnail path should start with the permanent repo folder");
    }

    /**
     * {@code toMessageRepo()} also runs {@code fixDescriptionRepoPaths()}, which repairs the context-root that
     * a rich-text editor may strip from links: a part description containing the literal {@code "rest/repo/file}
     * (no leading slash) gets a leading slash inserted, becoming {@code "/rest/repo/file}.
     */
    @Test
    public void toMessageRepo_addsLeadingSlashToContextRootStrippedLinks() {
        // Build an isolated message whose repo paths do not appear in the details, so only the
        // context-root fix (not the repo-folder rewrite) affects the description.
        SystemMessageVo msg = new SystemMessageVo();
        msg.setRepoPath("repoX");
        msg.setEditRepoPath("repoY");

        MessagePartVo part = new MessagePartVo();
        part.setType(MessagePartType.DETAILS);
        MessagePartDescVo d = new MessagePartDescVo();
        d.setLang("en");
        // The leading slash has been stripped from the context root.
        d.setDetails("<img src=\"rest/repo/file/messages/x/file.png\">");
        part.checkCreateDescs().add(d);
        msg.checkCreateParts().add(part);

        msg.toMessageRepo();

        assertEquals("<img src=\"/rest/repo/file/messages/x/file.png\">", d.getDetails(),
                "the missing leading slash must be re-inserted before rest/repo/file");
    }

    /**
     * {@code rewriteRepoPath} is guarded: when the source path is blank it is a no-op and leaves the
     * attachment path, thumbnail path and embedded description link untouched.
     */
    @Test
    public void rewriteRepoPath_withBlankSource_isNoOp() {
        String originalAttachmentPath = attachment.getPath();
        String originalThumbnailPath = message.getThumbnailPath();
        String originalDetails = desc.getDetails();

        message.rewriteRepoPath("", EDIT_REPO_PATH);

        assertEquals(originalAttachmentPath, attachment.getPath(),
                "blank source path must leave the attachment path unchanged");
        assertEquals(originalThumbnailPath, message.getThumbnailPath(),
                "blank source path must leave the thumbnail path unchanged");
        assertEquals(originalDetails, desc.getDetails(),
                "blank source path must leave the embedded description link unchanged");
    }
}
