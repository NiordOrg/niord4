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
package org.niord.core.script;

import java.util.List;

/**
 * The templates the code names (docs/code-data-config-plan.md §6.1, §6.2), as {@code ScriptResource} paths (no
 * leading slash): {@code LightCharacterService}'s two ({@code LIGHT_CHARACTER_TEMPLATE} and
 * {@code TELEPHONY_CODE_TEMPLATE}), the five geometry formats ({@code GeometryFormatService}, which names
 * {@code templates/geometry/<format>.ftl}), the message mail (the template path {@code MessageMailRestService} and
 * {@code MessageCommentRestService} send with), the built-in reports' template, and the two it includes.
 *
 * <p>They are data like every other template: a deployment renders its own row, and the code never reads one from
 * the jar. This list is used only by the missing-path report ({@link MissingPathReport}) and by the bootstrap's
 * completeness test, which fails the build when the base product's {@code bootstrap/templates/} lacks one. A new
 * path the code names goes here and into {@code bootstrap/templates/}; {@code RequiredTemplatesTest} fails on a
 * template-path literal in the code that is not listed.
 */
public final class RequiredTemplates {

    /** The 11 paths, sorted */
    public static final List<String> PATHS = List.of(
            "templates/aton/light-character.ftl",
            "templates/aton/telephony-codes.ftl",
            "templates/geometry/bullet-list.ftl",
            "templates/geometry/from-to-table.ftl",
            "templates/geometry/numbered-list.ftl",
            "templates/geometry/table.ftl",
            "templates/geometry/text.ftl",
            "templates/messages/message-list-pdf-footer.ftl",
            "templates/messages/message-list-pdf.ftl",
            "templates/messages/message-mail.ftl",
            "templates/messages/message-support.ftl");

    private RequiredTemplates() {
    }

    /**
     * Returns the path in {@code ScriptResource} form: the code names some with a leading slash, which FreeMarker
     * drops before it asks the loader
     *
     * @param path the path
     * @return the path without one leading '/', or null for null
     */
    public static String normalize(String path) {
        return path != null && path.startsWith("/") ? path.substring(1) : path;
    }
}
