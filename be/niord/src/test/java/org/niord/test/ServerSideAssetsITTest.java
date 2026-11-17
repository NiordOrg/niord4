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

import io.restassured.response.Response;
import org.junit.jupiter.api.Test;
import org.niord.core.repo.FileTypes;
import org.niord.core.repo.IconSize;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.file.Paths;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The server-side assets contract: the stylesheets and images under {@code META-INF/resources} that the
 * backend itself refers to are served at the web root, by Spring Boot's default static-resource handler.
 * <p>
 * The consumers fetch them over HTTP, never from the classpath: the PDF renderer and the mail CSS
 * inliner resolve {@code /css/templates/*.css}, {@code /img/company-logo.png} and
 * {@code /img/flags/{lang}.png} against {@code baseUri}; and the REST layer redirects to
 * {@code /img/blank.png} (WMS), {@code /img/aton/aton.png} and the {@code /img/filetypes} icons. The redirect
 * tests assert only the {@code Location} header, so this is the test that proves the targets resolve.
 * {@code /img/map_image_placeholder.png} stays served for the pages that link it; the map-image endpoint answers
 * the same image itself.
 * <p>
 * The backend serves no page at its root: {@code GET /} answers 404 while {@code adminAppUrl} is unset and no
 * web app directory is configured ({@code niord.web-app.dir}, see {@link WebAppITTest}).
 */
class ServerSideAssetsITTest extends ITTest {

    /**
     * One file name per icon folder {@link FileTypes} maps to, plus an unknown extension for the
     * generic icon. The icon path itself is computed by {@link FileTypes}, as the redirects compute it.
     */
    private static final List<String> ONE_FILE_PER_ICON = List.of(
            "a.doc", "a.pdf", "a.xls", "a.ppt", "a.zip", "a.mp3", "a.bmp", "a.gif", "a.png", "a.jpg",
            "a.tif", "a.html", "a.txt", "a.mpg", "a.unknown");

    @Autowired
    FileTypes fileTypes;

    @Test
    void theTemplateStylesheetsAreServed() {
        assertServed("/css/templates/pdf.css", "text/css");
        assertServed("/css/templates/common.css", "text/css");
        assertServed("/css/templates/mail.css", "text/css");
    }

    @Test
    void theImagesTheBackendFetchesOrRedirectsToAreServed() {
        assertServed("/img/company-logo.png", "image/png");
        assertServed("/img/flags/en.png", "image/png");
        assertServed("/img/map_image_placeholder.png", "image/png");
        assertServed("/img/blank.png", "image/png");
        assertServed("/img/aton/aton.png", "image/png");
    }

    @Test
    void everyFileTypeIconIsServed() {
        for (String file : ONE_FILE_PER_ICON) {
            String icon = fileTypes.getIcon(Paths.get(file), IconSize.SIZE_32);
            assertTrue(icon.startsWith("/img/filetypes/"), "unexpected icon path for " + file + ": " + icon);
            assertServed(icon, "image/png");
        }
    }

    @Test
    void theRootHasNoPage() {
        given()
                .redirects().follow(false)
                .when().get("/")
                .then().statusCode(404);
    }

    private static void assertServed(String path, String contentType) {
        Response response = given().when().get(path);
        assertEquals(200, response.statusCode(), path + " must be served");
        assertTrue(response.contentType().startsWith(contentType),
                path + " must be served as " + contentType + ", was " + response.contentType());
    }
}
