/*
 * Copyright 2024 Danish Maritime Authority.
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
package org.niord.test.http;

import tools.jackson.databind.ObjectMapper;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.GroupRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An in-process, in-memory fake of the Keycloak admin REST API, layered on {@link HttpStub}.
 *
 * <p>The {@code KeycloakIntegrationService} talks to Keycloak through {@code keycloak-admin-client} at
 * {@code authServerUrl + "/admin/realms/niord/..."} (admin calls, Bearer-authenticated). This stub stands
 * up an ephemeral localhost server that:
 * <ul>
 *   <li>serves the configured clients / groups / users on the relevant GETs,</li>
 *   <li>is <em>stateful</em> for client creation — a POST to {@code /clients} adds the client (with a
 *       generated uuid) so the service's follow-up "find the new client" GET succeeds, and registered
 *       client roles are returned by {@code /clients/{uuid}/roles/{name}}, and</li>
 *   <li>accepts the remaining writes (role create/composite, user create/update/delete,
 *       reset-password, group join/leave) with a 2xx, recording them for assertion.</li>
 * </ul>
 *
 * <p>It is a reusable convenience over the lower-level {@link HttpStub} so that tests of the Keycloak
 * admin integration need not re-implement the routing each time. Assert on the issued requests via
 * {@link #http()} (path, method, {@code Authorization} header, body). Framework-neutral (JDK HTTP +
 * Keycloak representation POJOs already on the classpath); no Quarkus, no container.
 */
public final class KeycloakAdminStub implements AutoCloseable {

    public static final String REALM = "niord";

    private final HttpStub stub;
    private final String adminBase = "/admin/realms/" + REALM;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicInteger idSeq = new AtomicInteger(1);

    private final List<ClientRepresentation> clients = new CopyOnWriteArrayList<>();
    private final List<GroupRepresentation> groups = new CopyOnWriteArrayList<>();
    private final List<UserRepresentation> users = new CopyOnWriteArrayList<>();
    private final Map<String, List<RoleRepresentation>> clientRoles = new ConcurrentHashMap<>();
    private final Map<String, List<RoleRepresentation>> groupClientRoles = new ConcurrentHashMap<>();
    private final Map<String, List<GroupRepresentation>> userGroups = new ConcurrentHashMap<>();

    private KeycloakAdminStub() throws IOException {
        this.stub = HttpStub.start();
        this.stub.respond(this::route);
    }

    /** Starts the stub on an ephemeral localhost port. */
    public static KeycloakAdminStub start() throws IOException {
        return new KeycloakAdminStub();
    }

    /** The {@code authServerUrl} to set on the {@code KeycloakIntegrationService} under test. */
    public String baseUrl() {
        return stub.baseUrl();
    }

    /** The underlying stub, for request assertions (path/method/Authorization/body). */
    public HttpStub http() {
        return stub;
    }

    // ---- configuration (fluent) ----

    /** Registers a Keycloak client (domain) with the given client id and a generated uuid. */
    public KeycloakAdminStub withClient(String clientId) {
        ClientRepresentation c = new ClientRepresentation();
        c.setId("client-uuid-" + idSeq.getAndIncrement());
        c.setClientId(clientId);
        clients.add(c);
        return this;
    }

    /** Registers a group with the given uuid, name and path. */
    public KeycloakAdminStub withGroup(String id, String name, String path) {
        GroupRepresentation g = new GroupRepresentation();
        g.setId(id);
        g.setName(name);
        g.setPath(path);
        groups.add(g);
        return this;
    }

    /** Registers a user with the given uuid and username. */
    public KeycloakAdminStub withUser(String id, String username) {
        UserRepresentation u = new UserRepresentation();
        u.setId(id);
        u.setUsername(username);
        users.add(u);
        return this;
    }

    /** Sets the groups returned for a given user uuid (the {@code /users/{id}/groups} endpoint). */
    public KeycloakAdminStub withUserGroups(String userId, GroupRepresentation... gs) {
        userGroups.put(userId, new ArrayList<>(List.of(gs)));
        return this;
    }

    /** Sets the client roles mapped to a group (the group role-mappings endpoint), by client uuid. */
    public KeycloakAdminStub withGroupClientRoles(String groupId, String clientUuid, String... roleNames) {
        List<RoleRepresentation> rs = new ArrayList<>();
        for (String r : roleNames) {
            rs.add(new RoleRepresentation(r, r, false));
        }
        groupClientRoles.put(groupId + "/" + clientUuid, rs);
        return this;
    }

    /** Looks up the generated uuid of a registered client by its client id. */
    public String clientUuid(String clientId) {
        return clients.stream()
                .filter(c -> clientId.equals(c.getClientId()))
                .map(ClientRepresentation::getId)
                .findFirst()
                .orElse(null);
    }

    // ---- routing ----

    private HttpStub.StubResponse route(HttpStub.RecordedRequest req) {
        try {
            String path = req.path();
            String method = req.method();

            if (!path.startsWith(adminBase)) {
                return HttpStub.StubResponse.status(404);
            }
            String rel = path.substring(adminBase.length()); // e.g. "/clients", "/users/x/groups"

            // /clients
            if (rel.equals("/clients") && method.equals("GET")) {
                return json(clients);
            }
            if (rel.equals("/clients") && method.equals("POST")) {
                ClientRepresentation posted = mapper.readValue(req.bodyString(), ClientRepresentation.class);
                posted.setId("client-uuid-" + idSeq.getAndIncrement());
                clients.add(posted);
                return HttpStub.StubResponse.status(201);
            }

            // /clients/{uuid}/roles  and /clients/{uuid}/roles/{name}[/composites]
            if (rel.startsWith("/clients/") && rel.contains("/roles")) {
                String[] parts = rel.split("/"); // "", "clients", "{uuid}", "roles", ["{name}", ["composites"]]
                String clientUuid = parts[2];
                if (parts.length == 4 && method.equals("POST")) {
                    RoleRepresentation role = mapper.readValue(req.bodyString(), RoleRepresentation.class);
                    clientRoles.computeIfAbsent(clientUuid, k -> new CopyOnWriteArrayList<>()).add(role);
                    return HttpStub.StubResponse.status(201);
                }
                if (parts.length == 5 && method.equals("GET")) {
                    String roleName = parts[4];
                    RoleRepresentation found = clientRoles.getOrDefault(clientUuid, List.of()).stream()
                            .filter(r -> roleName.equals(r.getName()))
                            .findFirst()
                            .orElseGet(() -> new RoleRepresentation(roleName, roleName, false));
                    return json1(found);
                }
                if (parts.length == 6 && parts[5].equals("composites") && method.equals("POST")) {
                    return HttpStub.StubResponse.status(204);
                }
            }

            // /groups
            if (rel.equals("/groups") && method.equals("GET")) {
                return json(groups);
            }
            // /groups/{gid}/role-mappings/clients/{cuuid}
            if (rel.startsWith("/groups/") && rel.contains("/role-mappings/clients/") && method.equals("GET")) {
                String[] parts = rel.split("/"); // "", groups, {gid}, role-mappings, clients, {cuuid}
                String key = parts[2] + "/" + parts[5];
                return json(groupClientRoles.getOrDefault(key, List.of()));
            }

            // /users ...
            if (rel.equals("/users") && method.equals("GET")) {
                return json(users);
            }
            if (rel.equals("/users") && method.equals("POST")) {
                return HttpStub.StubResponse.status(201);
            }
            if (rel.startsWith("/users/")) {
                String[] parts = rel.split("/"); // "", users, {id}, [groups|reset-password|{gid}]
                String userId = parts[2];
                if (parts.length == 3 && method.equals("GET")) {
                    UserRepresentation u = users.stream()
                            .filter(x -> userId.equals(x.getId()))
                            .findFirst()
                            .orElseGet(() -> { UserRepresentation x = new UserRepresentation(); x.setId(userId); return x; });
                    return json1(u);
                }
                if (parts.length == 3 && method.equals("PUT")) {
                    return HttpStub.StubResponse.status(204);
                }
                if (parts.length == 3 && method.equals("DELETE")) {
                    return HttpStub.StubResponse.status(204);
                }
                if (parts.length == 4 && parts[3].equals("reset-password") && method.equals("PUT")) {
                    return HttpStub.StubResponse.status(204);
                }
                if (parts.length == 4 && parts[3].equals("groups") && method.equals("GET")) {
                    return json(userGroups.getOrDefault(userId, List.of()));
                }
                if (parts.length == 5 && parts[3].equals("groups")) {
                    // join (PUT) / leave (DELETE) group
                    return HttpStub.StubResponse.status(204);
                }
            }

            return HttpStub.StubResponse.status(404);
        } catch (Exception e) {
            return HttpStub.StubResponse.status(500);
        }
    }

    private HttpStub.StubResponse json(Object value) throws IOException {
        return HttpStub.StubResponse.json(mapper.writeValueAsString(value));
    }

    private HttpStub.StubResponse json1(Object value) throws IOException {
        return HttpStub.StubResponse.json(mapper.writeValueAsString(value));
    }

    @Override
    public void close() {
        stub.close();
    }
}
