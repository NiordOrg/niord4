# Niord — Quarkus → Spring Boot 4 migration (POC)

Faithful port of the Quarkus `niord` + `niord-dk` reactor (repo root `../`) to
**Spring Boot 4.0.6 / Spring Framework 7** on **Java 21**, living under `spring-niord/`.

## Guiding principles

1. **Faithful port, not a rewrite.** Keep the source structure, class names, JPA
   entities, named queries, REST paths and JSON contracts. The existing
   stand-alone `github.com/kaspernielsen/spring-niord` repo is a *rewrite*
   (flattened single module, Spring Data repositories, PostGIS, JMS→`@Async`,
   ~146 new tests). This POC deliberately does the opposite: a 1:1 port that
   keeps the `niord` (base library) + `niord-dk` (application) split and ports
   the **existing** test suite.
2. **Change as few dependencies as possible.** Every non-Quarkus library is kept
   at its original version (JBeret, qpid-jms, Freemarker, Batik, GeoTools,
   Flying Saucer, Twitter4J, JTidy, BouncyCastle, MicroProfile-OpenAPI
   annotations, …). Only the Quarkus **framework** extensions are replaced.
3. **Every committed stage stays green.** Dependencies and packages are added
   bottom-up (leaf → root). `mvn test` (and later `mvn verify`) passes at every
   stage; we never commit a red reactor.

## Status

| Stage | Scope | Tests | State |
|-------|-------|-------|-------|
| 0 — Foundation | SB4 reactor, parent + `niord` + `niord-dk` POMs | — | **DONE** |
| 1 — `org.niord.model` | 43 public REST/JSON value objects + 21 test classes | **522 green** | **DONE** |
| 2 — `org.niord.core` main + units | 382 files: CDI→Spring DI, JPA, security/OIDC, scheduler, JMS, JBeret, Hibernate Search 8, cache | — | **DONE** |
| 3 — `org.niord.web` (JAX-RS/Jersey) + `org.niord.s124` + units | 53 files + 16 test classes | — | **DONE** |
| **niord-base total** | **478 main + 221 unit-test files** | **3171 green** | **DONE** |
| 4a — `niord-dk` app boots | 22 main + Spring Boot app (security, Jersey, `@Setting` BPP, JBeret, JMS) | boot IT | **DONE** |
| 4b — `niord-dk` ITs | 164 `@QuarkusTest` ITTest → `@SpringBootTest` + Testcontainers (MySQL seed/Keycloak/MailHog/Artemis), incl. WMS/Twitter/Mail/JMS stubs | 2053 | **DONE** |
| **🎉 COMPLETE** | **`mvn clean verify` → 5475 tests, 0 failures** | 3171 + 251 + 2053 | **ALL GREEN** |

The full **Niord Spring Boot application boots end-to-end** against a Testcontainers
MySQL: `mvn clean verify` → niord-base **3171 units** + niord-dk
**`ContextLoadsITTest`** green. The app wiring (DI, JPA/Hibernate 7, Jersey JAX-RS,
Spring Security + JSR-250, the `@Setting` `BeanPostProcessor`, embedded Artemis JMS,
JBeret `JobOperator`, Hibernate Search 8.2) all starts. See the memory note
`spring-niord-poc-foundation` for the ~12 context-startup fixes. Remaining: rewrite
the 164 `@QuarkusTest` ITs onto `AbstractNiordITTest`, load the seed SQL + Keycloak
realm, and job-scope the batch artifacts so batch jobs run.

The **entire `niord-base` library** (the bulk of the app) is ported and its full
unit suite is green:

```bash
cd spring-niord && JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn clean test
# Niord Base … Tests run: 3171, Failures: 0, Errors: 0  → BUILD SUCCESS
```

### How Stage 2–3 were done
Bulk-copy each subtree → a mechanical Python transform (`.transform_cdi.py`)
mapping CDI scopes → `@Component` and stripping Quarkus Arc annotations → two
porting workflows (6 + 4 + 4 agents, conventions in
`.context-core-porting-conventions.md`) for the ~50 framework-seam files →
iterative `mvn compile`/`test` triage. `@Inject`, `@Named`, jakarta
`@Transactional`, `@RolesAllowed`, JAX-RS `@Path` were kept verbatim (Spring +
Jersey honour them). Extra deps beyond the map: keycloak-adapter-core/spi,
jakarta.batch/jms/json/jws APIs, `commons-io 2.16.1` (override transitive 1.3.1),
`httpclient 4.5.14`. Both `src/main/resources` and `src/test/resources` must be
copied. See the memory note `spring-niord-poc-foundation` for the full play-by-play.

## Test inventory (the work to be ported)

| Module | Tier | Files | Notes |
|--------|------|------:|-------|
| `niord` (base) | plain `*Test` (JUnit 4 units) | **237** | No `@QuarkusTest`; but exercise CDI/JPA-coupled core code, so need the core main port first. 21 done (model). |
| `niord-dk` | `@QuarkusTest` `*ITTest` | **164** | Need a live Spring context + Testcontainers (MySQL/Keycloak/MailHog). The end-to-end safety net. |
| `niord-dk` | plain `*Test` | ~19 | Mostly framework-neutral units. |

JUnit 4 tests run unchanged on the modern JUnit Platform via
`junit-vintage-engine` (already wired). No test-framework rewrite required for
the units; only `@QuarkusTest` ITs are rewritten to Spring test annotations.

## Dependency map (Quarkus → Spring Boot 4)

### Replaced framework extensions

| Quarkus extension | Spring Boot 4 replacement | Notes |
|---|---|---|
| CDI (`quarkus-arc`, `jakarta.enterprise`/`jakarta.inject`) | Spring DI (`@Component`/`@Service`/`@Repository`, constructor `@Autowired`) | 72 `@ApplicationScoped`, 155 `@Inject`. `@ApplicationScoped`→`@Component`/`@Service`(singleton); `@Inject`→constructor injection; `@Produces`→`@Bean`; `@Observes`→`@EventListener`; `@PostConstruct` kept (Jakarta). |
| `quarkus-resteasy` + `quarkus-resteasy-jackson` (JAX-RS) | `spring-boot-starter-jersey` (**keep JAX-RS**) | 40 `@Path` resources. Jersey on Spring Boot keeps `@Path`/`@GET`/`@Produces`/`@Context` working → resources + RestAssured ITs port ~1:1. App path set to `/rest`. *(Alternative: rewrite to Spring MVC `@RestController` — larger churn, rejected for minimal-change.)* |
| `quarkus-oidc` | `spring-boot-starter-oauth2-resource-server` | JWT bearer validation against Keycloak. `SecurityFilterChain` + `JwtAuthenticationConverter` mapping Keycloak realm/resource roles → authorities. |
| `quarkus-keycloak-admin-client` | `org.keycloak:keycloak-admin-client` (plain) | Same Keycloak admin API, wired as a Spring bean. |
| `@RolesAllowed`/`@PermitAll` (JSR-250 via Quarkus security) | `@EnableMethodSecurity(jsr250Enabled = true)` | **CRITICAL**: without `jsr250Enabled=true`, `@RolesAllowed` becomes a fail-OPEN no-op. 32 `@RolesAllowed` + 31 `@PermitAll`. Add a deliberate wrong-role→403 test. |
| `quarkus-hibernate-orm` | `spring-boot-starter-data-jpa` (Hibernate ORM 7) | Persistence is **plain `EntityManager` + Criteria + `@NamedQuery`** (zero Panache), so it ports near 1:1 onto JPA. 69 `@Entity`, 35 `@NamedQuery`, 98 `jakarta.persistence`. |
| `quarkus-scheduler` (`@Scheduled` cron) | Spring `@Scheduled` + `@EnableScheduling` | 12 schedules. Quarkus cron syntax → Spring cron (Quartz-style 6-field; review each expression). |
| `quarkus-smallrye-openapi` | `springdoc-openapi-starter-webmvc-ui` (or jersey variant) | Keep the MicroProfile `@Schema` annotations (springdoc reads them) — minimal change. |
| `quarkus-undertow` (servlets) | `spring-boot-starter-web` embedded Tomcat | Servlets/filters (e.g. CORS, cache) → Spring `FilterRegistrationBean`/`@WebFilter`. |
| `quarkus-qpid-jms` | **keep** `org.amqphub:amqp-10-jms` / qpid-jms + Spring `JmsTemplate`/`@JmsListener` | AMQP 1.0. JMS surface is tiny (3 files). qpid-jms client retained; only the wiring changes. |
| `quarkus-jberet` (JSR-352 batch) | **keep JBeret** (`org.jberet:jberet-core`) wired as Spring beans | Per project direction "JBeret can be used from Spring". 5 batch files. Provide a JobOperator bean + a Spring-aware `ArtifactFactory` so `@Named` batchlets resolve from the Spring context. |
| `infinispan-core-jakarta` | `spring-boot-starter-cache` + Caffeine (or keep Infinispan) | 9 files. Niord uses it as a local cache → `@Cacheable`/`CacheManager`. Keep Infinispan only if clustered caching is required. |

### Retained verbatim (framework-neutral — only the version is pinned)

`microprofile-openapi-api` (annotations), `jakarta.xml.bind`+`jaxb-runtime`,
Jackson, `freemarker` 2.3.31, `flying-saucer-*` 9.0.9, `cssparser` 0.9.20,
`jtidy` r938, `bcprov-jdk15on` 1.67, `jsoup` 1.15.3, `twitter4j-core` 4.0.6,
`batik-*` 1.16, `xmlgraphics-commons` 2.6, `com.github.NiordOrg:josm-seachart`
0.1.0, `nashorn-core` 15.3, `gt-main` (GeoTools) 26.2, `hibernate-spatial`,
`httpclient`, `commons-lang` 2.6. Test: JUnit 4 + `junit-vintage-engine`,
Mockito, Testcontainers 2.0.5 (+ docker-java 3.7.1 override).

## Key compatibility risks (decide before the mass port)

1. **Hibernate Search 6.2 (orm6) vs Hibernate ORM 7 (SB4).** SB4 brings Hibernate
   ORM 7; `hibernate-search-mapper-orm-orm6` 6.2 is incompatible. Options:
   (a) **upgrade Hibernate Search → 8.x** (targets ORM 7) and adapt the ~5
   search files — recommended; (b) follow the rewrite and **drop Lucene** for DB
   queries (larger behavioural change). This is the single biggest dependency
   change forced by SB4.
2. **`@RolesAllowed` fail-OPEN** unless `@EnableMethodSecurity(jsr250Enabled=true)`.
   Must be covered by a negative (wrong-role→403) test.
3. **Transaction rollback semantics.** Jakarta `@Transactional` rolls back on any
   exception (checked included); Spring `@Transactional` rolls back only on
   unchecked/`Error` by default. Audit the 80 `@Transactional` sites; add
   `rollbackFor = Exception.class` where the original relied on checked-exception
   rollback.
4. **Config precedence.** Bespoke `SettingsService` order (sysprop > env > DB).
   Map Quarkus `application.properties`/`@ConfigProperty` → Spring
   `application.yml`/`@Value`/`@ConfigurationProperties`; pin env-var ordering.
5. **`@QuarkusTest` IT rewrite.** 164 ITs use Quarkus test injection + RestAssured
   + Quarkus Dev/Testcontainers. Rewrite to `@SpringBootTest(webEnvironment=RANDOM_PORT)`
   + `@Testcontainers`; the RestAssured HTTP assertions themselves are reusable.

## Package porting order (leaf → root)

```
org.niord.model            [DONE]      pure VOs
org.niord.core.util                    helpers, formatters, GeoJSON utils
org.niord.core.model / geojson         base entities, JTS/spatial converters
org.niord.core.{settings,sequence,db}  config + sequence + DB plumbing
org.niord.core.{domain,user,keycloak}  identity + security context
org.niord.core.{area,category,chart}   tree entities + services
org.niord.core.{message,aton,source,publication,...}  domain services
org.niord.core.{mail,mailinglist,promulgation,batch,schedule,script,report,integration}
org.niord.s124                         S-124 GML export
org.niord.web.*                        JAX-RS resources (Jersey)
niord-dk (application + DK services + ITs)
```

## Open decisions for sign-off

- **JAX-RS via Jersey (keep `@Path`) vs rewrite to Spring MVC.** Recommend Jersey
  for minimal change + 1:1 IT port.
- **Hibernate Search: upgrade to 8.x vs drop Lucene.** Recommend upgrade to 8.x.
- **Caching: keep Infinispan vs Spring Cache+Caffeine.** Recommend Spring Cache
  unless clustered caching is needed.
- **Scope of "all tests green":** the 237 base units are reachable once the core
  main port lands; the 164 `@QuarkusTest` ITs require the full app wiring +
  Testcontainers and are the long pole. Confirm whether the POC target is
  "base units green" first, then ITs.
