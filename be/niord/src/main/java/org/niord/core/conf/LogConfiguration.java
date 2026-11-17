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
package org.niord.core.conf;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Produces logger
 */
@SuppressWarnings("unused")
public class LogConfiguration {

    // PORT: The CDI @Produces Logger producer (which injected a per-class slf4j
    // Logger via the InjectionPoint's declaring class) has no clean Spring analog.
    // Consumers that used "@Inject Logger log;" are translated per-class into
    // "private static final Logger log = LoggerFactory.getLogger(X.class);".
    // This helper is retained for callers that resolve a logger by class.
    public Logger getLogger(Class<?> declaringClass) {
        return LoggerFactory.getLogger(declaringClass);
    }

}
