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
package org.niord.core.bootstrap;

/**
 * A bootstrap run refused before it wrote anything: an archive broke a rule (status 400), the marker was in a state
 * the runner does not accept (status 409), or the database holds data (status 409): a bootstrap runs once, on a new,
 * empty database. The message names the rule, the state or the first table that holds a row.
 */
public class BootstrapRefusedException extends RuntimeException {

    /** An archive broke a rule */
    public static final int ARCHIVE = 400;

    /** The marker is in a state the runner does not accept */
    public static final int STATE = 409;

    /** The database holds data: a bootstrap runs only on a new, empty one */
    public static final int DATA = 409;

    private final int status;

    /**
     * Constructor
     *
     * @param message what was refused, naming the rule, the state or the table
     * @param status  {@link #ARCHIVE}, {@link #STATE} or {@link #DATA}
     */
    public BootstrapRefusedException(String message, int status) {
        super(message);
        this.status = status;
    }

    /** Returns the HTTP status the command answers the refusal with */
    public int getStatus() {
        return status;
    }
}
