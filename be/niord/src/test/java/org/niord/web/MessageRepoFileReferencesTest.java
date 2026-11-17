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
package org.niord.web;

import org.junit.jupiter.api.Test;
import org.niord.core.message.Attachment;
import org.niord.core.message.Message;
import org.niord.core.web.NiordWebException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link MessageRestService#checkRepoFileReferences}: a message's attachments and thumbnail are stored as the
 * request body sends them and resolved against the repository later, by the anonymous message archive export
 * among others. On save they must name files in the message's own repository folder.
 */
class MessageRepoFileReferencesTest {

    private static final String REPO_PATH = "messages/a/ab/uid";

    private static Message message(String thumbnailPath, Attachment... attachments) {
        Message message = new Message();
        message.setRepoPath(REPO_PATH);
        message.setThumbnailPath(thumbnailPath);
        for (Attachment att : attachments) {
            message.getAttachments().add(att);
        }
        return message;
    }

    private static Attachment attachment(String path, String fileName) {
        Attachment att = new Attachment();
        att.setPath(path);
        att.setFileName(fileName);
        return att;
    }

    private static void assertRefused(Message message) {
        NiordWebException e = assertThrows(NiordWebException.class,
                () -> MessageRestService.checkRepoFileReferences(message));
        assertEquals(400, e.getStatusCode().value());
    }

    @Test
    void referencesInTheMessageFolderAreAccepted() {
        assertDoesNotThrow(() -> MessageRestService.checkRepoFileReferences(message(
                REPO_PATH + "/2/custom_thumb_256.png",
                attachment(REPO_PATH + "/2/Buoy%20seal.jpg", "Buoy seal.jpg"),
                attachment(REPO_PATH + "/1/chart.pdf", "chart.pdf"))));
    }

    @Test
    void aMessageWithoutReferencesIsAccepted() {
        assertDoesNotThrow(() -> MessageRestService.checkRepoFileReferences(message(null)));
        assertDoesNotThrow(() -> MessageRestService.checkRepoFileReferences(message("", attachment(null, null))));
    }

    @Test
    void anAttachmentFileNameThatIsNotABareNameIsRefused() {
        for (String fileName : new String[] { "../../other-uid/1/x.jpg", "sub/x.jpg", "..\\x.jpg", "/etc/passwd", ".." }) {
            assertRefused(message(null, attachment(REPO_PATH + "/1/x.jpg", fileName)));
        }
    }

    @Test
    void anAttachmentPathOutsideTheMessageFolderIsRefused() {
        for (String path : new String[] {
                REPO_PATH + "/../other-uid/1/x.jpg",
                "messages/c/cd/other-uid/1/x.jpg",
                "temp/0a1b/1/x.jpg",
                "/" + REPO_PATH + "/1/x.jpg" }) {
            assertRefused(message(null, attachment(path, "x.jpg")));
        }
    }

    @Test
    void aThumbnailPathOutsideTheMessageFolderIsRefused() {
        assertRefused(message(REPO_PATH + "/1/../../other-uid/1/secret.png"));
        assertRefused(message("messages/c/cd/other-uid/custom_thumb_256.png"));
    }
}
