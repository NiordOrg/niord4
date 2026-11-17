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
package org.niord.web.map;

import java.io.IOException;

/**
 * A map background could not be composed because a tile it needs could not be had in time: the tile host
 * failed, answered with something that is not a 256 x 256 image, is paused after repeated failures, or did not
 * answer before the deadline.
 *
 * <p>The failure is transient. The caller answers with the placeholder image and writes nothing, so the next
 * request tries again rather than keeping a degraded image.
 */
public class TileUnavailableException extends IOException {

    public TileUnavailableException(String message) {
        super(message);
    }

    public TileUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
