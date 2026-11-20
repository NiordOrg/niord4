# The backend contract for a country

A country deployable (today `SIFA-DK/niord-dk` and `SIFA-DK/niord-gl`) builds on the base product: it adds its
own configuration, data, texts and code, and changes what the base product does only through the ways this
file names. Everything else is the base product's, and a country that needs it changed asks for an extension point.
The frontend's counterpart is the country kit's API (`docs/fe-country-split-plan.md` §4.3); the rules behind both
are that plan's §4.9 and §4.11 rule 3 and `docs/code-data-config-plan.md` (§3.3, §5, §6.4, §6.5, §7.1).

The contract is enforced, not advised. Three things hold it:

- **The start-up guard** (`CountryContractGuard`, run by `CountryContractInitializer` from niord-base's
  `META-INF/spring.factories`) refuses to start an application that replaces a protected bean, packages a protected
  property or registers a handler without a JSR-250 annotation. It runs in every application that has niord-base
  on its class path, whatever packages it scans; DK and GL start under it today.
- **The country build group**: niord-base's security tests and the country checks, which `niord-parent` runs in
  every country build that declares niord-base's test-jar (DK and GL since their C6, `docs/code-data-config-plan.md`
  §9), with a workflow step from a country's C6 that fails when the group ran no test (§ The country build group).
- **The override check** (`check-overrides.sh` in `niord-image-tools`), which a country's workflow runs over its
  jar after the package step, from that country's C6.

They guard against carelessness. A country that sets out to defeat them can; review is what holds then.

## What a country builds on

All of one base product version, `4.0.<run>`, from the product's Maven registry (`NiordOrg/niord`):

| Artefact | What the country does with it |
|---|---|
| `org.niord:niord-parent:<v>` (pom) | its `<parent>`: the dependency management niord-base was tested with, the repositories, and the Surefire and Failsafe configuration with the country build group and the test JVMs' zone (the property `argLine`, `-Duser.timezone=UTC`, which a country's own `argLine` replaces) |
| `org.niord.base:niord-base:<v>` | its dependency: the library |
| `org.niord.base:niord-base:<v>:test-jar:tests` | a test dependency: the IT harness (`ITTest`, the containers, the token helpers), the settings and text checkers and the country build group |
| `org.niord:niord-bootstrap:<v>:zip` | the base product's bootstrap, applied to a fresh database before the country's own (§ Bootstrap and release folders) |
| `org.niord:niord-image-tools:<v>:zip` | `scripts/split-jar.sh` and `scripts/publish-images.sh`, which build its image, and `scripts/check-overrides.sh` with `scripts/CheckOverrides.java`, the override check |
| `org.niord:niord-local-run:<v>:zip` | for a local run only, never an image input: `scripts/local-run-lib.sh` (the launcher library) with `scripts/bootstrap.sh` beside it, `scripts/load-sample.sh` with `sample/`, `docker-compose.yml`, the local realm `keycloak/niord-realm-local.json`; known passwords |
| `org.niord:niord-country-kit:<v>:zip` | the country kit: set-up, the guides and the templates, with this file as `docs/country-api.md` |

## Extension points

What a country may provide a bean or a file for. Each is checked against the code as of this file's last change.

| Extension point | How | Where the base product picks it up |
|---|---|---|
| A promulgation service | a `@Component` subclass of `BasePromulgationService` (`org.niord.core.promulgation`) | `PromulgationManager` takes every bean of that type (`List<BasePromulgationService>`) and registers it under its `getServiceId()` |
| A token validator | an `OAuth2TokenValidator<Jwt>` bean | Spring Boot adds every such bean to the validators of the decoder it builds, beside the issuer, expiry and `azp` checks; a validator can only narrow what is accepted |
| Its own REST endpoints | `@RestController` classes, in any package, each handler with `@PermitAll`, `@RolesAllowed` or `@DenyAll` on the method or the class declaring it | Spring MVC; the guard refuses a handler without one (§ The protected list) |
| Its own settings | `@Setting` fields or static `Setting` constants in its own code, keys in its namespace (`dk.*`, `gl.*`), with their values in `defaults/country/settings.json` | the settings start-up and the Settings page, as for the base product's keys (§ The settings file) |
| A bean of its own for a base product interface | a `@Primary` bean, or an added bean where the base product takes a list, for any type not on the protected list | Spring's ordinary resolution |
| Its own template paths | `META-INF/niord/expected-template-paths.txt` in its jar, the paths of its bootstrap's templates and release files (no content) | the missing-path report reads every such list on the class path, so its localized variants are reported too (`docs/code-data-config-plan.md` §6.6) |
| Its data | `be/bootstrap/` and `be/bootstrap/releases/` (§ Bootstrap and release folders) | the bootstrap command and a sysadmin's import |
| Its texts and settings values | `defaults/country/` (§ The defaults/country/ contract) | start-up, layered over the base product's |
| A static image at a base product path | a png, jpg, jpeg, gif, webp or ico under `META-INF/resources/img/`, the only guarded override, listed in `be/overrides.json` (§ Overrides); any other shadow is refused | Spring Boot serves the country's copy first |

A promulgation service of its own with a `MANDATORY` type adds the type's row in `onLoadSystemMessage`, as the base
product's services do (the base class adds none): the status endpoints refuse (409, `MessagePublishGate`) a step to
VERIFIED or PUBLISHED of a message that holds no ticked row of a `MANDATORY` type of the current domain.

A base product Java class is never replaced by a class of the same name: the guard refuses it for the protected
classes, the override check for every class (every shadow but a static image), and a country that needs different
behaviour gets a bean or an extension point.

## The protected list

The guard (`CountryContractGuard`) refuses each of these, and names what it found. Its constants are the lists.
`CountryApiDocTest` fails when the code names something this file does not, and holds three lists to the code both
ways: the protected beans below, the first column of the property table and the override check's rules.

**Protected beans.** A country may neither replace one (a subclass, a `@Primary` bean, a factory method of its own,
also one that returns niord-base's class) nor add a second: `SecurityFilterChain`, `JwtAuthenticationConverter`,
`GrantedAuthorityDefaults`, `NiordDomainFilter`, `DomainService`, `UserService`, `TicketService`, `SettingsService`,
`ScriptResourceService`, `FmTemplateService`, `JsResourceService`, `MessageMailService`,
`MailingListExecutionService` and `WebAppConfig`. "From niord-base" means declared by a class that loads from
niord-base's own jar: a country's `org.niord.dk` classes come from its own jar, so a package prefix does not count.

Not checked, and refused by review: the hooks that run beside these, a servlet filter (a `jakarta.servlet.Filter` or
`FilterRegistrationBean` bean, which could set any authentication before method security sees the request), a
`SecurityContextHolderStrategy` bean and a `BearerTokenResolver` bean. A country that needs one asks for an extension
point.

**The decoder.** A `JwtDecoder` bean is accepted only from Spring Boot's `OAuth2ResourceServerJwtConfiguration`.
niord-base declares none: Spring Boot builds it and adds the `azp` check, and a decoder declared anywhere else switches
that configuration off.

**Method security.** Only niord-base's `SecurityConfig` carries `@EnableMethodSecurity`; a bean class elsewhere with
`@EnableMethodSecurity` or `@EnableGlobalMethodSecurity` is refused, and so is a `RoleHierarchy` or
`MethodSecurityExpressionHandler` bean declared outside niord-base and Spring Security (a role hierarchy would widen
every `@RolesAllowed`).

**CORS.** CORS is configured nowhere: the frontends reach the backend on its own origin. Refused: a
`CorsConfigurationSource` or `CorsFilter` bean declared outside the Spring framework (Spring MVC's own
`mvcHandlerMappingIntrospector` is one and is accepted), a `WebMvcConfigurer` outside niord-base that declares
`addCorsMappings`, a handler with `@CrossOrigin`, and the properties `management.endpoints.web.cors.*`.

**Handlers.** Every handler method Spring MVC registers must resolve `@PermitAll`, `@RolesAllowed` or `@DenyAll` on
the method or on the class declaring it, resolved as Spring Security's `Jsr250AuthorizationManager` resolves it
(`CountryContractGuard.JSR250`); two competing annotations are refused too. Under the base product's model the HTTP
layer is `permitAll` and method security is the whole guard, so a handler without an annotation would be public. The
check covers every handler method of Spring MVC's `RequestMappingHandlerMapping` beans, in any package; the reviewed
framework handlers are exempt: `BasicErrorController` (the `/error` page). The guard does not check role names (a
misspelt role fails closed); niord-base's `RestControllerSecurityAnnotationTest` does, for its own controllers.

Outside the guard, and refused by review (`docs/fe-country-split-plan.md` §4.11 rule 3): an HTTP endpoint that
is not such a handler method, that is a servlet or `ServletRegistrationBean`, a servlet filter, a `RouterFunction`, an
`HttpRequestHandler` behind a handler mapping of its own, the actuator's own mapping, and SECOM's RESTEasy endpoints
in niord-base. None of them carries a JSR-250 annotation that method security would read, so under `permitAll` such
an endpoint would be public. A country's only way to add an endpoint is an annotated `@RestController` (§ Extension
points).

**Protected properties.** Refused where a jar packages them, that is in a property source read from a class-path
resource (an `application*.yml` or `.properties` in the country's jar, or niord-base's `niord-core-defaults.yml`):

| Property | Rule |
|---|---|
| `management.server.port` | refused: it moves the actuator onto a management context `SecurityConfig` does not guard |
| `management.endpoints.web.exposure.include` | only `health`; `exposure.exclude` only narrows and is accepted |
| `management.endpoint.<id>.enabled`, `management.endpoint.<id>.access` | refused (Spring Boot 4 names the switch `access`) |
| `management.endpoints.access.*`, `management.endpoints.enabled-by-default` | refused |
| `management.endpoints.web.cors.*` | refused (CORS) |
| `spring.security.*` | refused, except `spring.security.oauth2.resourceserver.jwt.issuer-uri`, which a country's `application-local.yml` sets for a local run (the decoder's checks stay Spring Boot's and niord-base's) |
| `niord.oidc.base-client` | refused: the client whose roles form every caller's identity |
| `niord.oidc.allowed-clients` | only with a non-blank `niord.oidc.allowed-clients-reason` beside it, in the same file, saying why the country widens the clients whose tokens are accepted |

A bean class outside niord-base and the Spring framework that carries `@PropertySource` is refused as well: its file
reaches the environment after the property check has read it, so a jar's configuration goes in `application*.yml`,
where the guard reads it. A property source that a jar's own `EnvironmentPostProcessor` adds from a map is outside
the check and refused by review.

A deployment's own configuration is not checked: environment variables (`NIORD_OIDC_ALLOWED_CLIENTS`), system
properties, the command line, `SPRING_APPLICATION_JSON` and a `file:` location such as the Helm chart's
`/config/application.yaml`, which sets the issuer and the key set of each environment.

**Protected classes.** The niord-base classes among the protected beans, `SecurityConfig` and the guard itself must
load from niord-base, once: a class of the same name elsewhere on the class path is refused.

**What the start-up says.** A refused start ends with one exception that lists every finding, one per line:

```
java.lang.IllegalStateException: Niord country contract: the start is refused, 2 findings (be/docs/country-api.md)
 - property management.server.port in class path resource [application.yml]: a protected property: it moves the actuator onto a management context that SecurityConfig does not guard
 - bean 'countrySecurityFilterChain' (SecurityFilterChain) declared by org.niord.dk.DkSecurityConfiguration: a protected bean, declared only by niord-base
```

A `property` line names the key and the file in the jar, a `bean` line the bean, the protected type and the class
that declares it, a `handler` line the controller class and the method, a `class` line the class and where it was
found. The properties and class origins are checked before any bean definition is read, the beans once every
definition is registered and before any bean is created, and the handlers (with the beans again, by their instances'
types) once the singletons exist and before the web server starts; a refused application never serves a request.

## The country build group

niord-base's tests that every country build runs as well, tagged `@CountryBuildGroup` (the JUnit tag
`niord-country-build`; `CountryBuildGroupTest` lists them): the REST layer's annotation tests
(`RestControllerSecurityAnnotationTest`, `RestHandlerSecurityITTest`), the `SecurityConfig` tests
(`SecurityConfigJwtTest`, `SecurityConfigFrameOptionsTest`, `JwtAuthorizedPartyITTest`,
`MethodSecurityOrderingITTest`), the protected `NiordDomainFilter` and `WebAppConfig` (`NiordDomainFilterTest`,
`WebAppConfigTest`), the HTML sanitiser of mails and the template hardening (`MailHtmlTest`,
`FmTemplateClassResolverTest`), the guard's own tests (`CountryContractGuardTest`, `CountryContractStartupTest`) and
the country's defaults checks (`CountryDefaultsChecksTest`).

`niord-parent` runs them with two executions of its own, `niord-country-build` (Surefire) and
`niord-country-build-it` (Failsafe), beside the default ones. Each scans only niord-base's test-jar and selects the
tag, so a country build runs exactly the group and none of niord-base's other tests, and the country's own tests run
in the default executions as before. A country inherits both with its `<parent>`. The ITs among them start the
country's `org.niord.*` classes on the shared harness (`NiordTestApplication` scans `org.niord`) with
Testcontainers, so they need Docker, as the country's own ITs do; code in another package is checked by the start-up
guard at the application's own start, not by the group.

A country declares niord-base's test-jar and the test dependencies its ITs need (test-jar dependencies are never
transitive); DK's `pom.xml` has the list:

- `org.niord.base:niord-base:${project.version}:test-jar` (scope `test`);
- `spring-boot-starter-test`, `spring-security-test`, `spring-boot-testcontainers`;
- `org.testcontainers:testcontainers`, `testcontainers-postgresql`, `testcontainers-junit-jupiter` (without
  `io.quarkus:quarkus-junit4-mock`);
- `com.github.dasniko:testcontainers-keycloak` 3.4.0 (without `io.quarkus:quarkus-junit4-mock`);
- `io.rest-assured:rest-assured` 6.0.0 and `org.awaitility:awaitility`;
- as compile or runtime dependencies of the deployable: `org.postgresql:postgresql`, `spring-boot-starter-liquibase`,
  `spring-boot-starter-artemis` and `org.apache.artemis:artemis-jakarta-server` (Artemis 2.54.0).

A new country's `be/pom.xml`, which the kit's `init` writes from `templates/be/pom.xml.tmpl`, declares niord-base's
test-jar and every dependency above.

**Skipping nothing.** A country never sets `niord.countryGroup.skip` (only niord-base, niord-app and niord-bootstrap
set it, in the base product); its CI runs `./mvnw verify` with Docker, so the group's ITs run rather than skip. A
country whose test class path lacks niord-base's test-jar runs the group with 0 tests and no failure:
Surefire's `failIfNoTests` would refuse that, but it would also refuse a country's `-Dtest=<its own test>`. So the
count is checked after `verify` by the country's own `be/scripts/check-country-build-group.sh`, which its `ci.yml`
runs (added in its C6) and a local check runs too: it fails unless
`target/failsafe-reports/failsafe-summary-niord-country-build.xml` reports `completed` above 0 and `skipped` below
`completed` (Failsafe counts a skipped test as completed, so a run without Docker would pass otherwise), and a
Surefire report of `CountryContractGuardTest` exists under `target/surefire-reports/`. The country's `image.yml`
has no count: it packages with `-DskipTests` after a green CI run, which has counted. `-DskipTests` skips the group
with every other test, and a `-Dtest=` or
`-Dit.test=` filter applies to it as well: the group's executions do not fail when the filter names none of their
classes, so a country can run one test of its own. Two members skip themselves where they do not apply:
`MailHtmlTest`'s frontend check outside a base product checkout, and `CountryDefaultsChecksTest` while the class path holds no
`defaults/country/` and no `defaults/packs/`. A country whose `be/` ever becomes an aggregator with `pom` packaging sets
`niord.countryGroup.skip` to `true` in that aggregator only: Surefire refuses `groups` on a project without a test
class path, which is why the base product's own reactor pom switches the group off (the profile
`niord-base-reactor`).

## The defaults/country/ contract

A country's defaults live in its jar under `defaults/country/`, never at the base product's paths
(`docs/code-data-config-plan.md` §3.3, §4.4):

```
src/main/resources/defaults/country/
    README.md
    settings.json                        replaces the base product's settings file (below)
    settings-catalog.json                generated from the country's own code: its keys only
    texts/<lang>/<bundle>.properties     its texts, layered key by key over the packs' and the base product's
target/classes/defaults/packs/<lang>/<bundle>.properties
                                         the language packs' backend half, staged at build; never committed
```

- The settings file replaces `defaults/base/settings.json` whole; the country wins by the code's rules, not by
  shadowing a file.
- Texts are layered key by key: the base product's English, then the packs' backend half, then the country's texts.
  A country's text holds only its own wording of a base product key that a template or Java reads, and its own keys
  (prefixed `dk.`, `gl.`); a rewording only the administration app shows is a frontend text override or branding.
- No other file is read: a root-level `<bundle>_<lang>.properties` is not (the transitional layer is gone). The text
  check (`CountryDefaultsChecksTest`, rule `root-bundle`) fails a build that has one of the five bundles in `en`, in a
  language with a folder under `defaults/country/texts/` or `defaults/packs/`, or in the `modelLanguages` of
  `defaults/country/settings.json` (else of the base product's settings file). It looks those names up and never
  scans the class path, so a root file in another language is not reported.
- `defaults/packs/` is generated by the kit's set-up before the Maven build and never committed (the override
  check refuses a committed one).
- `DefaultsGuard` stops start-up when a known name under `defaults/` is on the class path more than once (also with
  equal content), when `defaults/country/` comes from more than one location, or when a `defaults/packs/en/` exists
  (the base product's English is not a pack). A country never puts a file under `defaults/base/` (the override check
  refuses it).
- Every value is a default only: start-up adds a key a deployment lacks and never changes one it has.

## The settings file

`defaults/country/settings.json` (`docs/code-data-config-plan.md` §5.2, §5.5):

```json
{ "values": { "modelLanguages": ["da", "en"], "country": "DK", "organisation": "…" },
  "useBaseDefault": [ { "key": "mapTileContact", "reason": "no DK-specific contact" } ] }
```

- It sets values only. Type, scope, web visibility and the `editable` flag come from the catalogs: the pinned base
  product's `defaults/base/settings-catalog.json` and the country's own `defaults/country/settings-catalog.json`.
- It sets every key of the base product's settings file, or lists it in `useBaseDefault` with a reason; the base
  product default is then the pinned base product's file value, else the code default.
- It sets the `required` keys `country` (the ISO 3166-1 two-letter code S-124 takes) and `organisation`; a `required` key cannot be listed in
  `useBaseDefault`.
- A password setting is empty in the file: the real value is set on the Settings page or in the server's
  `${niord.home}/niord.json`.

The checks (`DefaultSettingsChecks`, in the test-jar) run in the country build group (`CountryDefaultsChecksTest`)
over the country's class path, and fail the build on each finding, naming the file, the key and the rule:
`format`, `undeclared-key`, `state-key`, `type`, `secret`, `missing-key`, `use-base-default` and `required`; `https`
is a warning. The group does not redirect its output, so the report, with the warnings and the `useBaseDefault`
list, is in the Maven log. The same test runs the text checks (`DefaultTextsChecks`: UTF-8, length, control
characters, placeholders and markup against the base product's English) over the country's texts and the packs'
backend half, and the root-bundle rule (above). While the class path has no `defaults/country/` and no
`defaults/packs/` (a country before the code/data/config plan's C6) the test is skipped and the transitional root
`niord.json` is read at start-up, which only warns about a missing `required` key.

A country generates its own catalog from its own code with `SettingsCatalogGenerator` (in the test-jar); its
current-check, as niord-base's `SettingsCatalogCurrentTest`, is the country's own test, added with its first key.

## Bootstrap and release folders

A country's data is never a resource in its jar (`docs/code-data-config-plan.md` §6.4, §7.1):

- `be/bootstrap/` holds the country's data in the batch-set format the import jobs read (`batch-set.json` naming
  each job and its data file): its templates and scripts, domains and series, data dictionaries, parameter types,
  reports and promulgation types, and the languages and domain links it adds to the base product's entries. It is
  applied once, to a fresh database, after the pinned base product's `niord-bootstrap` zip in the same run.
  DK's holds its 56 templates and scripts (42
  shadows of base product templates, 14 of its own), its 5 domains with their 6 series, the 4 DGPS stations of the
  `aton` dictionary and the `da` of the 111 base product `aton` keys, the parameter types `dgps` and
  `dgps_station_list` and additions carrying the `da` of the 13 base product composite types' template parameters,
  additions carrying the `da` of the 13 base product categories and their 11 template parameters and the links of
  the 10 template categories to `niord-nw`, its 6 reports, and `Audio` with additions linking `NAVTEX` and
  `Twitter` to its domains. The base product's bootstrap carries `Audio` too, so DK's `Audio` row restates the
  base product's: on a fresh database its `da` replaces the base product's English and its `niord-nw` link is
  added.
  GL's holds its 86 templates and scripts (46
  shadows, 40 of its own), the domain `niord-gl-nw` with its series `ako-nw`, the DGPS stations and types whole with
  their `da` and `kl`, the `da` and `kl` of the 111 base product `aton` keys, additions carrying the `da` and `kl`
  of the 13 base product composite types' template parameters, additions carrying the `da` and `kl` of the 13 base
  product categories and their 11 template parameters (no domain links), `Audio` (`kl`, priority 10, requirement
  `MANDATORY`; a restatement of the base product's `Audio`: on a fresh database its `kl`, priority 10 and
  `MANDATORY` replace or fill the base product's English, 50 and empty requirement, with no domain link) and
  `Navtex GL`, and an addition linking `NAVTEX` to `niord-gl-nw`. GL restates nothing whole but `Audio`. Neither
  holds mailing lists. The base product's
  `categories.json` holds 45 categories in English, with no domain links and no `messageId`: the 13 above and the
  32 under `Buoy`, `Beacon`, `Obstruction`, `Drifting object` and `Radio navigation`, with `Radio navigation`/`DGPS`
  naming no script and no template parameter. DK's and GL's archives give the names of the 13 only. A country
  completes the 32 in the same form: an entry with the base category's exact English name, nested under its
  parents' entries with theirs, its own languages, its template parameters by `paramId` with their names, its domain
  links, and for `DGPS` its scripts and `dgps` template parameter. Without that, a fresh database shows them in
  English, offers them in every domain, and `DGPS` has no template.
- **Within the run** (`BootstrapRun`, `docs/code-data-config-plan.md` §7.2; the owner's ruling of 2026-10-24) the
  bootstrap is one first-time deployment, and a country's archive takes precedence over the base product's. For
  each entry it names that the base product's archive of the same run created:
  - each value it gives replaces the base product's: a template (the history records the source `import` and the
    sysadmin who ran the bootstrap); a name or description in a language, and a dictionary entry's value, long
    value and AtoN filter; a category's type, active flag, sort order and AtoN filter; a template parameter's type,
    mandatory and position-list flags and names; a promulgation type's name, `serviceId`, priority, active flag,
    requirement and language; a report's name, sort order and template path; and a list (a category's standard
    fields, editor fields and script paths, a promulgation type's message types and script paths, a list parameter
    type's values), whole, when it differs;
  - a value the base product left empty is filled;
  - a value it leaves out keeps the base product's. "Gives" means the field is in the file and is not null: a list
    given explicitly, even empty, replaces the base product's (so `[]` empties a base list), a field left out or
    null keeps it, and an empty string or an empty object counts as left out, so a bootstrap cannot clear a base
    product text (the administration app can afterwards). Template parameters and domains are merged and unioned,
    so an explicit `[]` there adds and removes nothing, and a report's properties and parameters are maps merged by
    key (the owner's decision of 2026-10-25);
  - what is new is added: a language, a template parameter (a new `paramId`, appended), a dictionary entry, a
    report's property or parameter key (the maps are merged by key), a domain link (the links are the union);
  - nothing is removed, and a value equal to the base product's writes nothing.

  A category's MRN is a matching key: it is filled when the base product's category has none, and a different one is
  kept, and the job log says so. A composite parameter type stays composite and a list type a list: a later archive
  that changes the kind fails the job, naming the type and both archives. A domain the file names that no row
  carries is not linked, and the job log says so; one it lists twice is linked once. The job log has one line per
  entry: `added …; replaced <field> <old> → <new>; kept …`, `kept` naming only what cannot change (a matching key,
  a domain not found), and `nothing to add` when nothing was added, replaced or noted. A key of one of the five
  default-text dictionaries (`web`, `message`, `pdf`, `mail`, `template`) that a country's archive restates with
  other values is start-up's, not the run's: it is kept, and the job log says so on a line naming the key.

  Not covered by the rule: a category that a `domains.json` creates (a domain's own categories) is not recorded,
  so a later archive's category import updates it whole, as before (the base product's `domains.json` creates
  none); a new active category under an inactive one an earlier archive created makes that one active
  (`TreeBaseEntity.updateActiveFlag`), and the job log says so; an `active: false` on a category an earlier
  archive created makes its active descendants inactive too, and the parent's job-log line names them; the mailing-list import and the later jobs (chart,
  area, source, publication-category, publication) keep their update-whole semantics inside a run, with no scope.
  The domain import keeps an existing domain apart from its active flag within the run, so a country's `domains.json` may restate a domain the base product's
  gives (`niord-client-master`) only with its `domainId` and a boolean `active`:
  `{"domainId": "niord-client-master", "active": false}` leaves the base product's master domain inactive on the
  fresh database, with nothing else of it changed, and the domain import's job log says so. Any other restatement
  is refused (400) before the marker is claimed, naming the domain and both archives. The base product's default
  stays active.

  An addition names its base product entry as the import finds it: a template by its path, a dictionary entry by
  the dictionary's name and its key, a category by its MRN, or else by one of its names in that name's language,
  case-insensitively, under the same parent (a root among the roots only), so an addition carries the English
  name, and the parents' too, and a
  `da`-only entry creates a second category; a parameter type by its name and a template parameter by its `paramId`
  (then by its order among equal ones), a promulgation type by its `typeId` and a report by its `reportId`. A whole
  restatement of a base product entry is applied by the same rule: its values replace the base product's where they
  differ, and an addition that gives only the matching key and what it adds gives the same rows as a restatement
  whose other values equal the base product's. Outside a run, that is in every sysadmin's import and every release
  folder, nothing of this applies.

  The base product's archive is posted first; the command does not check it, and a country's archive sent first
  would have its values replaced by the base product's. A bootstrap runs once, on a new, empty database: the claim
  refuses (409) a marker that reads anything but `pending`, and a database that holds data, naming the first table
  that holds a row. There is no retry: a failed bootstrap, and one that broke off, are refused for good, with one
  archive or more, and a restart of one of its jobs from the batch page is refused too. The remedy is a database
  recreated empty, the backend started on it and the same command run with the same archives (D4). A country's
  bootstrap IT, which runs the runner on its own new container, passes the claim as it is.
- A country's ITs complete the templates the way DK's and GL's do: the build unpacks the pinned base product's
  bootstrap, `org.niord:niord-bootstrap:${project.version}:zip`, into `target/niord-bootstrap/` with two executions
  at `pre-integration-test`, `clean-niord-bootstrap` (maven-clean-plugin, the folder and the unpack's marker, so a
  pin bump never leaves an older base product's files) and then `unpack-niord-bootstrap`
  (maven-dependency-plugin), with no dependency on the zip, so `package -DskipTests` never resolves it. The IT
  fixture (`DkBootstrapTemplates`, `GlBootstrapTemplates`) writes in one transaction of its own, committed once,
  and re-points `CdiUtils` at the test's own application context (`pointScriptsAt`; CDI1 in
  `be/docs/potential-errors.md`). The test-jar's fixture already holds every base product template row and the
  sample's (84 `ScriptResource` rows), so a country's IT fixture writes its own templates over them, creating or
  replacing each path, and needs the base product's half for no path: a completion that creates only missing
  paths never writes a country's copy of a base product template, and the IT renders the base product's. DK's and
  GL's fixtures, `DkBootstrapTemplates.writeDksTemplates` and `GlBootstrapTemplates.writeTemplates`, write only
  the country's templates over the test-jar's rows, creating or replacing each path (an equal row is left). The
  unpacked base product's bootstrap stays for the countries' bootstrap ITs, which run it before the country's
  archive and compare with it; no test writes it to the IT database.
- What a country's ITs may rely on in the test-jar's fixture (`db/niord-base-data.sql`, a dump of the base
  product's bootstrap and the sample): beside the 84 template and script rows, the base product's three reports,
  the master domain `niord-client-master` and the sample's five domains (`niord-nw`, `niord-nm`, `niord-fa`,
  `niord-almanac`, `niord-annex`), each with its client in the IT realm, and the sample's 34 messages under the
  fixture's own uid prefix. It holds no `Setting`, `User` or `BatchData` row: each IT start writes its settings
  from its class path. So an IT reads no `Setting` row from the fixture, names the domains by the sample's ids and
  finds a sample message by its short id (`NW-001-26`), never by a uid.
- `be/bootstrap/releases/<yyyy-mm-dd>-<topic>/` holds a release's import files: a new or changed template as
  `script-resources.json` (the Script resources import's format), with a `README.md`. A folder is named by the date
  and topic of its change and never changes once merged; a sysadmin imports it after the preview.
- Every one of these folders has a `README.md` whose first words are: *"Data, not code. Loaded only into a fresh
  deployment (bootstrap), only by a sysadmin's import (releases), or only into the demo database (sample). A running
  deployment's data may differ from these files and is the truth; nothing here ever changes it."*
- None of it goes into the jar or the image: the override check refuses any `templates/**` file in the jar.
- The jar carries `META-INF/niord/expected-template-paths.txt` with the paths of its bootstrap's and release files'
  templates, so the missing-path report covers them.
- An item of `batch-set.json` may carry the `properties` a job reads, but not `add` or `overwrite`: those are a
  sysadmin's ticks after an import's preview, and the bootstrap adds what is missing and overwrites nothing outside
  the rule above. Nor may it carry `bootstrapRun`, the runner's own token for that rule. The bootstrap refuses
  such an archive (400) before it claims the marker. An upload (any import's) or a batch set that carries
  `bootstrapRun` is refused too, and the batch instances endpoint leaves it out of a job's properties.
- An archive may hold 16 MB an entry and 64 MB in all (`BootstrapArchive`), but the server's multipart limit comes
  first: niord-base's defaults set `spring.servlet.multipart.max-file-size` and
  `spring.servlet.multipart.max-request-size` to 50 MB (the chart's proxy allows the same), and the server answers
  413 to a larger upload (`bootstrap.sh` says so). A country whose archive is larger raises both in its deployment's
  configuration, and the body limit of its proxy.

## The script allow-list

Every script and template the import or the bootstrap takes passes `ScriptResourceCheck` (`org.niord.core.script`,
`docs/code-data-config-plan.md` §6.5): the path rules (not blank, at most 255 characters, no `..` segment, no
backslash, no leading `/`, `.ftl` or `.js`), for a template a FreeMarker parse and a check of every literal `?new`
class name against `org.niord.core.script.directive`, and for a script a token list that refuses `Packages`,
`Java.type`, `Java.extend`, a `java.` or `javax.` package other than `java.util.`, `java.time.`, `java.text.` and
`java.math.` (so `java.io` and `java.nio.file` too), within `java.util.` the sub-packages `zip`, `jar`, `logging`,
`spi` and `concurrent`, an `org.`, `com.`, `net.` or `edu.` package other than `org.niord.core.` and
`org.niord.model.` (so `org.apache.commons.io` too), `java.lang.Runtime`, `ProcessBuilder`, reflection, `eval`,
`Function(`, `load(` of anything but a `niord:` path, `javax.script`, `jdk.nashorn`, entity-manager writes and
`.executeUpdate(`. A script may look up only these types with `CdiUtils.getBean(<type>.class)`
(`ScriptResourceCheck.ALLOWED_BEAN_TYPES`):

- `org.niord.core.message.MessageService`
- `org.niord.core.promulgation.NavtexPromulgationService`
- `org.niord.core.promulgation.SafetyNetPromulgationService`
- `org.niord.core.area.AreaService`
- `org.niord.core.message.MessageSearchService`

A type a country's script needs beyond these is a base product change. The check is a guard against carelessness,
not a boundary: it reads tokens, so a bracket form (`Java["type"](…)`, `em["remove"](x)`) or a name built from
strings passes it, and `ScriptResourceCheckTest` asserts that as the check's known limit. Scripts run with full
privileges, and the boundary is that only a sysadmin imports, recorded with the user in the history.

## Overrides

What remains of shadowing: a static image at a base product path, that is a png, jpg, jpeg, gif, webp or ico file
under `META-INF/resources/img/` (for example `META-INF/resources/img/company-logo.png`). Spring Boot serves the
country's copy first, so a base product fix to the image never reaches the country unless it looks; each such file
is therefore listed in `be/overrides.json`. No other niord-base file may be shadowed, listed or not. An SVG is not a
permitted image: served from the application's own origin, an SVG is a document that can run script, and niord-base
ships none.

```json
{ "overrides": [
  { "file": "META-INF/resources/img/company-logo.png",
    "basedOn": { "META-INF/resources/img/company-logo.png": "sha256:3f1c…(64 hex)" },
    "reason": "DK's logo",
    "added": "2026-11-20" }
] }
```

- `file` is the path in the jar (under `src/main/resources/`); `basedOn` holds that one path with the fingerprint of
  niord-base's file the copy was made from; `reason` says why; `added` is the date, `yyyy-mm-dd`. Nothing else is
  allowed, and a file is listed once.
- A country's workflow runs the check after its package step, with the scripts of the pinned `niord-image-tools`,
  wherever the workflow unpacked it (DK's and GL's `image.yml` unpack it with `maven-dependency-plugin:unpack` into
  `target/k8s/`, beside `split-jar.sh`):

  ```
  target/k8s/scripts/check-overrides.sh target/<the country's jar> .
  ```

  from `be/`, with Java 25. It compares the country's files (`BOOT-INF/classes/`, and the `META-INF/` that Spring
  Boot's repackaging keeps at the jar's root, its `META-INF/resources/` images among them) with the pinned
  niord-base in the same jar (`BOOT-INF/lib/niord-base-*.jar`) and prints the listed overrides on every run. Exit 0
  passes, 1 is a finding, 2 is wrong usage or a jar it cannot read.
- The rules, each its own finding: `unlisted-shadow` (a shadow that is a permitted static image, which
  `overrides.json` does not list); `forbidden-shadow` (any other shadow, listed or not, its fingerprint correct or
  not: a class, `config/`, `db/`, `keycloak-client-template.json`, a batch job's XML under `META-INF/batch-jobs/`, an
  XSLT, a stylesheet, an SVG, an image outside `META-INF/resources/img/` such as the root `blank_256.png`, a text
  file); `template-in-jar` (any `templates/**` file); `defaults-base` (any
  `defaults/base/**` file); `committed-packs` (anything under `src/main/resources/defaults/packs/`); `stale-basedOn`
  (niord-base's file has changed since the recorded fingerprint, or is gone); `format` (`overrides.json` breaks the
  format above, or lists a file the jar does not carry). Files every jar carries and that are read together are not
  shadows: the manifest, `META-INF/maven/**`, `META-INF/spring.factories`, `META-INF/spring/*.imports`,
  `META-INF/services/**`, Spring's configuration metadata and `META-INF/niord/expected-template-paths.txt`.
- **Recording a fingerprint.** An `unlisted-shadow` finding prints the fingerprint of niord-base's file
  (`basedOn "sha256:…"`); a `stale-basedOn` finding prints the new one. Compare the country's copy with niord-base's
  file at the new version, change the copy if needed, and record the printed value in `basedOn`. By hand:
  `unzip -p target/<jar> BOOT-INF/lib/niord-base-<v>.jar > /tmp/b.jar && unzip -p /tmp/b.jar <file> | sha256sum`.

## Compatibility

The contract is kept across base product versions, as the frontend's API is (`docs/fe-country-split-plan.md` §4.3).
A change that breaks anything this file lists (an extension point, the protected list, the group's dependencies, the
folders, the settings file's rules, the allow-list, the override check's rules) is named in its PR and recorded below
under **Changes** with what a country must do. A removed extension point is first deprecated (`@Deprecated`, kept and
working) for at least one base product version that both DK and GL have taken.

## Changes

No change is recorded. The record starts again, empty, after 4.0.196: what changed up to that version is in the
`docs/country-api.md` of its kit package. Until production the record is paused as the frontend's is, and gets no
row (the country kit's README, `country-kit/README.md`, "Paused until production"); the rule of **Compatibility**
above holds in full from the day it wakes.

| Base product version | Change | What a country does |
|---|---|---|
