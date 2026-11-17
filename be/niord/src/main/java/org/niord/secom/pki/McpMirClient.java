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
package org.niord.secom.pki;

/**
 * Issues MCP certificates from the identity provider's MIR (Maritime Identity Registry) REST
 * API - the renewal transport of identity plan T3. Kept behind an interface so an ACME client
 * can slot in if MCP registries ever implement the renewal protocol G1183 §5.6 calls for.
 */
public interface McpMirClient {

    /** Requests a new certificate for the given service; returns the PEM certificate chain, leaf first */
    String issueServiceCertificate(String orgMrn, String serviceMrn, String csrPem) throws Exception;

    /** Requests a new certificate for the given user (the renewal robot renewing itself); returns the PEM chain */
    String issueUserCertificate(String orgMrn, String userMrn, String csrPem) throws Exception;
}
