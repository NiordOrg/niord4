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
package org.niord.core.message;

/**
 * Thrown when the free-text index cannot parse a search's free-text query, such as one with an unbalanced quote. A
 * client error: as an {@link IllegalArgumentException} it is answered 400. The message is fixed and does not echo
 * the query; the cause keeps the parser's own account for the log.
 */
public class InvalidFreeTextQueryException extends IllegalArgumentException {

    public InvalidFreeTextQueryException(Throwable cause) {
        super("The search text could not be read: check its quotes, brackets and operators", cause);
    }
}
