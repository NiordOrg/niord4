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
package org.niord.web.sweep;

import org.niord.core.user.Roles;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Who a sweep request is sent as: a token of the test realm (fetched per request through
 * {@code ITTest.accessToken(role)}) with a {@code NiordDomain} header, nobody, or a ticket.
 *
 * <p>The identities and the roles they hold in the domain of their header ({@code niord-nw} unless named):
 * <table>
 *   <caption>The identities of the role table</caption>
 *   <tr><th>Cell name</th><th>Realm user</th><th>Header</th><th>Roles there</th></tr>
 *   <tr><td>{@code anonymous}</td><td>none</td><td>none</td><td>none (the public site)</td></tr>
 *   <tr><td>{@code anonymous+header}</td><td>none</td><td>niord-nw</td><td>none (the admin app before sign-in)</td></tr>
 *   <tr><td>{@code user}</td><td>testuser</td><td>niord-nw</td><td>user</td></tr>
 *   <tr><td>{@code userOtherDomain}</td><td>testuser</td><td>niord-nm</td><td>none (authenticated)</td></tr>
 *   <tr><td>{@code editor}</td><td>testeditor</td><td>niord-nw</td><td>user, editor (editor on the base client too)</td></tr>
 *   <tr><td>{@code admin}</td><td>testadmin</td><td>niord-nw</td><td>user, editor, admin</td></tr>
 *   <tr><td>{@code sysadmin}</td><td>sysadmin</td><td>niord-nw</td><td>every role, and Keycloak's manage-users</td></tr>
 *   <tr><td>{@code domainEditor}</td><td>testdomaineditor</td><td>niord-nw</td><td>user, editor (niord-nw only)</td></tr>
 *   <tr><td>{@code adminElsewhere}</td><td>testadminelsewhere</td><td>niord-nw</td><td>user (admin in niord-nm only)</td></tr>
 * </table>
 *
 * <p>{@code ticket(<identity>)} is a ticket that identity mints through {@code GET /rest/tickets/ticket} just
 * before the request, with no role; {@code ticket(<identity>:<role>)} asks for the role, as the admin app's
 * download links do. The request then carries the ticket and nothing else.
 */
public record SweepIdentity(String name, String tokenRole, String domain, SweepIdentity minter, String ticketRole) {

    /** The domain of the identities' header, and the other domain of the role table */
    public static final String DOMAIN = "niord-nw";
    public static final String OTHER_DOMAIN = "niord-nm";

    private static final Map<String, SweepIdentity> BASE = new LinkedHashMap<>();
    private static final Map<String, Set<String>> ROLES = new LinkedHashMap<>();

    static {
        base("anonymous", null, null);
        base("anonymous+header", null, DOMAIN);
        base("user", "user", DOMAIN, Roles.USER);
        base("userOtherDomain", "user", OTHER_DOMAIN);
        base("editor", "editor", DOMAIN, Roles.USER, Roles.EDITOR);
        base("admin", "admin", DOMAIN, Roles.USER, Roles.EDITOR, Roles.ADMIN);
        base("sysadmin", "sysadmin", DOMAIN, Roles.USER, Roles.EDITOR, Roles.ADMIN, Roles.SYSADMIN);
        base("domainEditor", "domainEditor", DOMAIN, Roles.USER, Roles.EDITOR);
        base("adminElsewhere", "adminElsewhere", DOMAIN, Roles.USER);
    }

    private static void base(String name, String tokenRole, String domain, String... roles) {
        BASE.put(name, new SweepIdentity(name, tokenRole, domain, null, null));
        ROLES.put(name, Set.of(roles));
    }

    /** The four role tiers and anonymous: the identities a full run derives annotation cells for */
    public static final List<String> TIERS = List.of("anonymous", "user", "editor", "admin", "sysadmin");

    /** The identities whose realm users the owner's realm change added */
    public static final List<String> ADDED = List.of("domainEditor", "adminElsewhere");

    /**
     * The two added identities are in the test realm ({@code keycloak/niord-realm.json}): a token of one that
     * cannot be fetched fails the run. Set it to false to run against a realm without them; their cells are then
     * listed as not run.
     */
    public static final boolean ADDED_IN_REALM = true;

    private static final Pattern TICKET = Pattern.compile("ticket\\(([A-Za-z+]+)(?::([a-z-]+))?\\)");

    /** The identity of a cell name's identity part, or an exception naming what is wrong */
    public static SweepIdentity parse(String name) {
        SweepIdentity base = BASE.get(name);
        if (base != null) {
            return base;
        }
        Matcher m = TICKET.matcher(name);
        if (m.matches()) {
            SweepIdentity minter = BASE.get(m.group(1));
            if (minter == null || minter.tokenRole() == null) {
                throw new IllegalArgumentException("A ticket is minted by a signed-in identity, not '" + m.group(1)
                        + "'");
            }
            return new SweepIdentity(name, null, null, minter, m.group(2));
        }
        throw new IllegalArgumentException("Unknown identity '" + name + "'; the identities are " + BASE.keySet()
                + " and ticket(<identity>) or ticket(<identity>:<role>)");
    }

    public static SweepIdentity of(String name) {
        return parse(name);
    }

    public boolean isTicket() {
        return minter != null;
    }

    public boolean isAnonymous() {
        return tokenRole == null && minter == null;
    }

    /** The roles the identity holds in the domain of its header (a ticket: its minter's, if it asked for none) */
    public Set<String> roles() {
        if (isTicket()) {
            return ticketRole == null ? minter.roles() : Set.of(ticketRole);
        }
        return ROLES.get(name);
    }

    /** The expected answer of a handler annotated with the guard, derived from the roles */
    public Cell derived(String guard) {
        if (guard.equals("PermitAll")) {
            return Cell.OK;
        }
        if (guard.equals("DenyAll")) {
            return isAnonymous() ? Cell.status(401, null) : Cell.status(403, null);
        }
        if (!guard.startsWith("RolesAllowed(")) {
            throw new IllegalArgumentException("Unknown guard " + guard);
        }
        if (isAnonymous()) {
            return Cell.status(401, null);
        }
        for (String constant : guard.substring("RolesAllowed(".length(), guard.length() - 1).split(",")) {
            if (roles().contains(EndpointTableGenerator.roleValue(constant.trim()))) {
                return Cell.OK;
            }
        }
        return Cell.status(403, null);
    }

    /** The least privileged identity the guard admits (anonymous for PermitAll) */
    public static SweepIdentity lowestAllowed(String guard) {
        for (String name : List.of("anonymous", "user", "editor", "admin", "sysadmin")) {
            SweepIdentity identity = BASE.get(name);
            if (identity.derived(guard).isOk()) {
                return identity;
            }
        }
        throw new IllegalArgumentException("No identity is allowed by " + guard);
    }

    @Override
    public String toString() {
        if (isTicket()) {
            return name + " (a ticket minted by " + minter.name() + ", no token, no header)";
        }
        return name + (domain == null ? " (no header)" : " (NiordDomain: " + domain + ")");
    }
}
