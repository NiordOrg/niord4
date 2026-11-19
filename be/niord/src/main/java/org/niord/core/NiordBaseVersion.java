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
package org.niord.core;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * The version of the niord-base library this backend runs, for the places where the backend names itself to
 * another server, such as the User-Agent of its map tile requests, and for the build version the administration
 * app shows.
 *
 * <p>The version is the Maven project version, which the build writes into {@code niord-base.properties} next to
 * this class (the one filtered resource of the module, see its {@code pom.xml}): {@code 4.0.<run>} in a published
 * build, {@code 4.0.0-SNAPSHOT} in a local one. When that resource is missing or was copied without filtering,
 * the jar manifest's {@code Implementation-Version} is used, and failing that {@value #FALLBACK}.
 *
 * <p>The build-version endpoint ({@link NiordApp#getBuildVersion()}, {@code GET /rest/version/build-version})
 * answers this version too.
 */
public final class NiordBaseVersion {

    /** The resource the build writes the version into, relative to this class */
    static final String RESOURCE = "niord-base.properties";

    /** The version reported when neither the resource nor the manifest names one */
    public static final String FALLBACK = "dev";

    private static final Logger log = LoggerFactory.getLogger(NiordBaseVersion.class);

    private static final String VERSION = resolve(
            readFilteredVersion(),
            NiordBaseVersion.class.getPackage().getImplementationVersion());

    private NiordBaseVersion() {
    }

    /**
     * Returns the version of the niord-base library, resolved once per class loader
     * @return the version, never blank
     */
    public static String get() {
        return VERSION;
    }

    /**
     * Returns the first usable of the two versions, or {@value #FALLBACK}. A value is usable when it is not blank
     * and holds no placeholder that a build left unreplaced.
     *
     * @param filtered the version read from {@value #RESOURCE}, or null
     * @param implementationVersion the manifest's {@code Implementation-Version}, or null
     * @return the version
     */
    static String resolve(String filtered, String implementationVersion) {
        if (usable(filtered)) {
            return filtered.trim();
        }
        if (usable(implementationVersion)) {
            return implementationVersion.trim();
        }
        return FALLBACK;
    }

    private static boolean usable(String version) {
        return StringUtils.isNotBlank(version) && !version.contains("@") && !version.contains("${");
    }

    /** Returns the {@code version} of {@value #RESOURCE}, or null when it cannot be read */
    private static String readFilteredVersion() {
        try (InputStream in = NiordBaseVersion.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                log.warn("{} is missing from the class path; the niord-base version falls back", RESOURCE);
                return null;
            }
            Properties properties = new Properties();
            properties.load(in);
            return properties.getProperty("version");
        } catch (IOException e) {
            log.warn("Cannot read {}; the niord-base version falls back", RESOURCE, e);
            return null;
        }
    }
}
