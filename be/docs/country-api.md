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
| `org.niord:niord-parent:<v>` (pom) | its `<parent>`: the dependency management niord-base was tested with, the repositories, and the Surefire and Failsafe configuration with the country build group |
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
`MailHtmlTest`'s frontend check outside a niord-ng tree, and `CountryDefaultsChecksTest` while the class path holds no
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
  applied once, to a fresh database, after the pinned base product's `niord-bootstrap` zip in the same run. Since
  its bump to 4.0.87 (P5e, niord-dk #6 on `feature/p5-dk-moves`), DK's holds its 56 templates and scripts (42
  shadows of base product templates, 14 of its own), its 5 domains with their 6 series, the 4 DGPS stations of the
  `aton` dictionary and the `da` of the 111 base product `aton` keys, the parameter types `dgps` and
  `dgps_station_list` and additions carrying the `da` of the 13 base product composite types' template parameters,
  additions carrying the `da` of the 13 base product categories and their 11 template parameters and the links of
  the 10 template categories to `niord-nw`, its 6 reports, and `Audio` with additions linking `NAVTEX` and
  `Twitter` to its domains. From the base product version with the country split's P7, whose bootstrap carries
  `Audio`, DK's `Audio` row restates the base product's: on a fresh database its `da` replaces the base product's
  English and its `niord-nw` link is added (see Changes).
  Since its bump to 4.0.87 (P6e, niord-gl #7 on `feature/p6-gl-moves`), GL's holds its 86 templates and scripts (46
  shadows, 40 of its own), the domain `niord-gl-nw` with its series `ako-nw`, the DGPS stations and types whole with
  their `da` and `kl`, the `da` and `kl` of the 111 base product `aton` keys, additions carrying the `da` and `kl`
  of the 13 base product composite types' template parameters, additions carrying the `da` and `kl` of the 13 base
  product categories and their 11 template parameters (no domain links), `Audio` (`kl`, priority 10, requirement
  `MANDATORY`; from the base product version with P7 a restatement of the base product's `Audio`: on a fresh
  database its `kl`, priority 10 and `MANDATORY` replace or fill the base product's English, 50 and empty
  requirement, with no domain link, see Changes) and `Navtex GL`, and an addition linking `NAVTEX` to
  `niord-gl-nw`. GL restates nothing whole but `Audio`. Neither holds mailing lists.
  From the base product version with CAT45 (the owner's ruling of 2026-11-04, decision 145), the base product's
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
  case-insensitively, under the same parent (a root: by name at any depth, CATEGORY2 in
  `be/docs/potential-errors.md`; the base product's 45 English names are unique, case-insensitively, so a root's
  lookup finds the intended category), so an addition carries the English name, and the parents' too, and a
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
  fixture (`DkBootstrapTemplates`, `GlBootstrapTemplates`) writes in one transaction of its own, committed once
  (before C4b's fixture, below, it wrote the country's templates first and then the base product's from
  `target/niord-bootstrap/` for the paths the country lacked), and re-points `CdiUtils` at the test's own application context (`pointScriptsAt`; CDI1 in
  `be/docs/potential-errors.md`). From the base product version with the code/data/config plan's C4b on, the
  test-jar's fixture already holds every base product template row and the sample's (84 `ScriptResource` rows), so
  a country's IT fixture writes its own templates over them, creating or replacing each path, and needs the base
  product's half for no path: a completion that creates only missing paths never writes a country's copy of a base
  product template, and the IT renders the base product's (see Changes). Since DK's and GL's bumps to 4.0.97
  (`niord-dk` #10, `niord-gl` #9, 2026-10-25) their fixtures, `DkBootstrapTemplates.writeDksTemplates` and
  `GlBootstrapTemplates.writeTemplates`, write only the country's templates over the test-jar's rows, creating or
  replacing each path (an equal row is left), and the base product's half is gone; the create-only completion
  (`addMissingTemplates`) is history. The unpacked base product's bootstrap stays for the countries' bootstrap
  ITs, which run it before the country's archive and compare with it; no test writes it to the IT database.
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

| Base product version | Change | What a country does |
|---|---|---|
| the first with the code/data/config plan's C5b | The contract as above: the start-up guard, the country build group in `niord-parent`, the override check in `niord-image-tools`, this file | Moving its `<parent>` to it runs the group, once it declares niord-base's test-jar and the test dependencies above. Keep `issuer-uri` as the only `spring.security.*` key in its jar's configuration. Add `be/overrides.json` with its logo and the check step after the package step in its workflows, and move its templates to `be/bootstrap/`, in the code/data/config plan's C6: its jar may shadow nothing but a listed static image under `META-INF/resources/img/` (a png, jpg, jpeg, gif, webp or ico; every other shadow is `forbidden-shadow`, listed or not), and C5b's route A found no shadow in DK's or GL's jar but the templates and the logo. Put no `@PropertySource` on a class (its configuration goes in `application*.yml`). A script that names an `org.`, `com.`, `net.` or `edu.` package other than `org.niord.core.` and `org.niord.model.`, or a `java.util` `zip`, `jar`, `logging`, `spi` or `concurrent` class, is refused at import: change it before its release file or bootstrap carries it. An item of its bootstrap's `batch-set.json` carries no `add` or `overwrite`. A deployment whose bootstrap archive is larger than 1 MB raises `spring.servlet.multipart.max-file-size` and `max-request-size` |
| the first with the code/data/config plan's C6 (its base product half) | Within one bootstrap run a later archive may replace a template and add languages and domain links to what an earlier archive of the run created, and the job log says what was added and kept (§ Bootstrap and release folders; `BootstrapRun`); an item of `batch-set.json`, an upload or a batch set may not carry `bootstrapRun`, and the batch instances endpoint leaves it out. The base product's archive is posted first (not checked). A retry of a failed or broken-off bootstrap is accepted with one archive only, for a deployment whose bootstrap is that one archive; with more it is refused (409), and the remedy is an empty database (D4); from P7 there is no retry at all (the last row). The base product's bootstrap is in English only: its 170 Danish descriptions are gone (`aton-dict.json` 111, `categories.json` 24, `parameter-types.json` 35) | At its pin bump to it: its archive keeps working, its whole restatements applied as additions, and gives the rows it gave before, apart from the templates the rule now replaces, the languages it now adds and the Danish descriptions the base product no longer carries. So it carries the `da` it needs that it did not carry (DK: the categories' 24; GL: the 111 `aton` and the categories' 24), may add its categories' languages and `Category_Domain` links, may turn its whole restatements into additions (optional: a composite type as its `type` and name, with its template parameters' `paramId` and the languages it adds, GL's with both `da` and `kl`; a promulgation type as its `typeId` with its domains), and updates the tests that hold its restatements equal to the base product's. A failed bootstrap of a fresh deployment is recreated empty (D4): a retry with one archive is accepted only for a deployment whose bootstrap is that one archive, and a retry with the base product's archive alone would end `applied` and leave the country's archive unapplied for good |
| the first with the code/data/config plan's C4b | The IT fixture in niord-base's test-jar (`db/niord-base-data.sql`) is a dump of the base product's bootstrap and the sample: every base product template and script and the sample's four as `ScriptResource` rows (84), the base product's three reports, the sample's five domains (`niord-nw`, `niord-nm`, `niord-fa`, `niord-almanac`, `niord-annex`) and its 34 messages under the fixture's own uid prefix; no `Setting`, `User` or `BatchData` row (each IT start writes its settings from its class path). The IT realm's domain clients are `niord-nw` … `niord-annex` (`niord-client-master` kept) | At its pin bump to it: its IT fixture creates or replaces its bootstrap templates over the fixture's rows rather than adding only the missing paths (DK's `DkLightDestroyedTemplateITTest` needs it, its `tmpl/light-destroyed.ftl` being DK's own version of a base product template path (one of its 42 shadows), which differs from the fixture's base row; the paths of DK's `DkDgpsTemplateITTest` and GL's `GlFiringExercisesScriptITTest` hold the sample's copies, byte-equal to theirs today); no IT reads a `Setting` row from the fixture; an IT names the domains by the sample's ids and finds a sample message by its short id (`NW-001-26`), never by a uid |
| the first with the country split's P7, 4.0.97 (its backend half) | The base product's bootstrap carries `Audio` (`typeId` and `serviceId` `audio`, English, priority 50, active, no domain link) after `NAVTEX` and `Twitter`. This reaches fresh databases only: no deployment's rows change | At its pin bump to it: its `Audio` row stays as it is and restates the base product's (§ Bootstrap and release folders). Within the first bootstrap its values replace the base product's (the next row): DK's `da` replaces the English and its `niord-nw` link is added; GL's `kl`, priority 10 and `MANDATORY` replace or fill the English, 50 and empty requirement. The tests that hold its `Audio`'s language hold as they are (DK's `DkBootstrapITTest` and `DkBootstrapFolderTest`, GL's `GlBootstrapITTest` and `GlBootstrapFolderTest`); GL's adds priority 10 and `MANDATORY`, which prove the fill; the additions-name-base-entries tests expect `audio` among the base product's entries (DK: `navtex`, `audio`, `twitter`; GL: `navtex`, `audio`), allowed to carry values |
| the first with the country split's P7, 4.0.97 (the owner's bootstrap ruling of 2026-10-24, P7-bs) | A bootstrap is one first-time deployment: within the run a later archive's values replace what an earlier archive created, a value it leaves out is kept, what is new is added and nothing is removed (§ Bootstrap and release folders); a list given explicitly, even empty, replaces, a field left out or null keeps, and an empty string or object counts as left out (the owner's decision of 2026-10-25, on the review of #204; `Given` and `BootstrapArchive` read with the typed read's Jackson-2 defaults); a default-text key the country's archive restates is kept, with a job-log line. The claim refuses (409) a database that holds data, naming the table (`BootstrapRefusedException.DATA`), checked with the marker in one locked transaction; the rows a first start writes by itself, the mirrored users and the queued mails (`ScheduledMail`, `ScheduledMailRecipient`, which a scheduled SECOM alert may write before the bootstrap) are not data (`BootstrapMarker.NOT_DATA`). A `pending` marker the claim did not move on (a concurrent claim) is refused with "The claim did not take effect although the marker reads 'pending' (another claim changed it meanwhile); ask again". There is no retry and no takeover: `failed` and `in-progress` are refused for good, with one archive or more, and a bootstrap job's restart is refused (409); the remedy is a database recreated empty (D4). A later archive that gives a `domainId` an earlier one gives is refused (400) before the claim. `apply` runs only on a marker the run claimed. The library API that changed with it, which no country calls: `BootstrapMarker.claim(String)` returns a `BootstrapMarker.Claim` record (claimed, state, error, stale, dataTable), not a boolean, and `claim(String, boolean)`, `retryable()` and `RETRYABLE` are removed, while `NOT_DATA`, `dataTables()` and `firstDataTable()` are new; `BootstrapRun.Additions` is (entry, added, replaced, kept, notes), its 3-argument constructor removed and its 4-argument one now (entry, added, replaced, kept), with `Additions.change(…)` and `Scope.createdBy(…)` new; `DictionaryService.ImportResult` loses `keptInRun` and gains `replacedInRun`; `CategoryService.addToCategory`, `TemplateExecutionService.addToParamType` and `addToTemplateParams`, `PromulgationTypeService.addDomains` and `FmReportService.addDomains` are replaced by `replaceInCategory`, `replaceInParamType`, `checkSameKind`, `replaceTemplateParams`, `PromulgationTypeService.replaceValues(orig, type, given)` and `FmReportService.replaceValues(orig, report)`; `BootstrapArchive.domainIds()` and the class `org.niord.core.bootstrap.Given` are new | At its pin bump to it: no code change (its bootstrap IT runs the runner on its own new container, which the claim accepts, and no country calls or constructs what changed above: their bootstrap ITs read the marker with `read()` and `State` only, both unchanged; checked on DK's `bc9353a` and GL's `6b80842`, older than their `main`, so P7's dry run checks again). Its `domains.json` restates no base product domain (neither does today). The empty-list rule changes none of its rows: neither DK's `main` (`d6fa491`) nor GL's (`e866206`) gives an explicit empty list of a replaced list (checked). Its READMEs and javadocs drop the retry sentences and say that its values replace the base product's within the first bootstrap. The message of its bootstrap IT's `paramIds` assertion, "a missing one is logged, a new one not added", is reworded (a new `paramId` is now appended); the assertion, equal `paramIds`, stays. A failed bootstrap of a fresh deployment is recreated empty (D4) |
| the first with the repository-files plan's R1 (`docs/repo-files-in-database-plan.md`) | The repository files (attachments, publication files, the superseded editions of the archive) are rows of the database, table `RepoFile`, read and written through `RepoStore` (JDBC, no entity); the volume is a cache. `RepositoryService` has no `Path` API any more (`getRepoRoot`, `getTempRepoRoot`, `moveRepoFolder`, `getHashedSubfolder` and the `Path` overloads are gone; `validateTempRepoPath` and `resolveInRepo` return the normalised repository-relative `String`); the settings `repoRootPath` and `publicationArchiveRootPath` are gone (the archive is the store's `archive/` folder, never served); the thumbnails and map images are drawn into `<home>/cache/<repository path>` (`RepoCache`), the AtoN tiles into `<home>/aton-tiles`. Liquibase `V009` adds the table. The `repo-import` batch job, started by `POST /rest/batch/repo-import` (SYSADMIN, a JSON body `{"dir", "archiveDir", "verify"}`, both folders below `niord.home`), imports an old repository root and archive root into rows, moves the derived files into the cache tree, rewrites the audit entries' archived locations and reports every reference that resolves to no row. niord-base's defaults set the multipart limits to 50 MB (`niord.home` follows `NIORD_HOME` since the E-fixes, the row below, so the chart passes no variable of that name). The sample's attachments are rows of `niord-sample.sql` (no `sample/repo/`) | At its pin bump to it: its deployment runs `repo-import` once, against the old `repo/` and `publication-archive/` folders on its volume, in a window the plan's §4.8 describes; its own code that reached the repository through `getRepoRoot()` or a `Path` of `RepositoryService` moves to an injected `RepoStore` (`stat`, `readAll`, `copyTo`, `spool`, `write`, `delete`, `list`), and a derived file it draws goes under `RepoCache.pathFor(...)`; its settings file drops `repoRootPath` and `publicationArchiveRootPath`, which no catalog declares any more (its build's settings check fails on an undeclared key; a deployment's rows of either stay as they are, inert); its multipart limit, if it set one below 50 MB, is raised or kept on purpose |
| the first with NG-B | A root-level `<bundle>_<lang>.properties` is no longer read (layer 3 gone); the text check's `root-bundle` rule fails a country build that has one | Nothing for DK and GL. Otherwise move the keys to `defaults/country/texts/<lang>/<bundle>.properties` before the bump |
| the first with the S-124 opt-out (the owner, 2026-11-01) | The boot property `niord.s124.enabled` (default `true` in `niord-core-defaults.yml`) gates `S124Service` and `S124RestService`: with `false`, `/rest/S-124/**` answers 404 and the producer code is neither used nor checked. `niord.secom.enabled` needs it, and start-up refuses `false` with SECOM on, naming both keys (`org.niord.s124.S124SwitchCheck`). The automatic cancellation warning is unchanged (`s124-cancellation-plan.md` decision 2). `/rest/settings/web` always carries the computed booleans `s124Enabled` and `secomEnabled`, never stored; a stored `secomEnabled` row is overridden. niord-app writes `false`. The chart renders `niord.s124.enabled` and `niord.s124.producer-code` only when its values set `s124.enabled` and `s124.producerCode`: it has no `DK00` default any more (`be/k8s/README.md` § S-124 on or off) | At its pin bump to it: write `niord.s124.enabled` explicitly in `be/src/main/resources/application.yml` (`true` for DK and GL, beside their `producer-code` `DK01`). Check that its GitLab chart values set `s124.producerCode` wherever a deployment relied on the chart's `DK00`; without it the jar's `DK01` now applies. Nothing else changes: no code of its own calls `S124Service` or reads the `secomEnabled` row (checked on DK's `bc9353a` and GL's `6b80842`, where only comments name `S124Service`) |
| the first with the E-fixes (the owner's rulings of 2026-11-03) | (a) `niord.home` follows `NIORD_HOME` (a system property, else an environment variable, when not blank) whenever no `niord.home` system property or environment variable is set, ahead of the database row (`SettingsService.override`); no other key gains an alias. (b) The base product's bootstrap gives the master domain `niord-client-master` active, so every fresh database has an active Master domain with no series, as the start-up's master domain was before C4.1 (`Domain.active` defaults to true); a running deployment is never bootstrapped again, so no deployment's rows change. (c) The IT fixture's master domain is active, and the sample's three external link publications carry `example.org` links. (d) Within the first bootstrap a later archive may restate an earlier archive's domain with its `domainId` and a boolean `active` only, and that flag wins (`BootstrapArchive.activeOnlyDomainIds()` and `BootstrapRun.Kind.DOMAIN` new; `BatchDomainImportProcessor` and `BatchDomainImportWriter` read the run's scope); any other restatement is still refused (400). (e) The base product's English `pdf.nm_almanac.title` is the neutral `NtM Almanac` (`defaults/base/texts/en/pdf.properties`, `bootstrap/texts.json`); fresh databases only, since start-up never changes a text row it already holds (`DefaultsLedger`) | Nothing at its pin bump. To keep `EfS A` in English, a country carries `pdf.nm_almanac.title=EfS A` in `be/src/main/resources/defaults/country/texts/en/pdf.properties`: DK and GL do since niord-dk #14 (`1a4d3be4`) and niord-gl #12 (`113ed0a4`), merged on 2026-11-03 before this base product version, so their English almanac PDF keeps its title on a fresh database. A country may leave the master domain inactive by restating it so in its `domains.json` (the kit's scaffold does not generate it); DK's and GL's do not restate it, so it stays active for them. Its settings file sets `"niord.home": "${NIORD_HOME}"` (niord-dk `d8a1046`, niord-gl `ffca4eb`), and the rows of its test data hold `"${NIORD_HOME}"` (checked on the local copies `niord_test` and `niord_gl`), so the home is unchanged. Its bootstrap ITs name their domains by id (`DkBootstrapITTest:297-299`, `GlBootstrapITTest:301-302`), and no country test names `niord-client-master` or the sample's publications. A Settings-page edit of `niord.home` is inert where `NIORD_HOME` is set, as for `authServerUrl` |
| the first with CAT45 (the owner's ruling of 2026-11-04, decision 145) | The base product's bootstrap carries all 45 categories: after the 13 (`Light`, `Light buoy`, `Firing Exercises`) the 32 under `Buoy`, `Beacon`, `Obstruction`, `Drifting object` and `Radio navigation`, in English, with no domain link and no `messageId`; `Radio navigation`/`DGPS` names no script and no template parameter (§ Bootstrap and release folders). This reaches fresh databases only: no deployment's rows change | Nothing at its pin bump. A country that wants its own names for the 32 restates them in its archive as it does for the 13, with its domain links, and for `DGPS` its scripts and `dgps` template parameter; until then a fresh database shows them in English and offers them in every domain |
| the first with the sea-chart tile cache (`docs/sea-chart-tile-cache-plan.md`) | Two operator settings, `wmsCacheDays` (code default `0`: no cache) and `wmsCacheMaxMb` (`200`), declared by `org.niord.web.wms.WmsTileCache` and in neither settings file of the base product. With `wmsCacheDays` above 0 the WMS proxy keeps the tiles it fetches under `wms-tiles/` on the application's volume and serves them to logged-in users only; a deployment switches it on only when its provider's licence allows a cache. Nothing changes for a deployment that leaves it off | Nothing at the bump. To switch the cache on: set both keys in `defaults/country/settings.json` in the release that first carries this base version, since start-up adds a missing key once, with the file's value at that start, and never changes the row afterwards (later: the Settings page, and a restart, as both are read at start-up); and the administration app must name the proxy, `map.wmsUrl: '/wms/'` in its production environment file |
| the first with the message list order fix (Liquibase `V010`) | A message's parts, references and attachments and a part's event dates keep every element, in order. Before, Hibernate ORM 7 stored every element of these lists with `indexNo` 0, because the element (`MessagePart`, `Reference`, `Attachment`, `DateInterval`) mapped the column of its list's `@OrderColumn` writable, and a reload kept one element per list; a later save of a part that had two event dates failed (409, or 500 in a domain with promulgations). The four entities now map `indexNo` read-only, as `Feature` does, so the list position is the order, and `V010` gives their four `indexNo` columns `DEFAULT 0` (schema only: no stored row changes). `setIndexNo` on these four reaches no database, `updateMessagePart`, `updateReference`, `updateAttachment` and `updateDateInterval` no longer copy the index, and `FiringExerciseService` no longer numbers its parts and event dates | At its pin bump to it: no code change, unless its own code orders one of these lists with `setIndexNo` (it orders the list itself instead). Its deployments that ran an earlier version hold, for each message created or saved there with two or more elements in a list, that list with every index 0: `MessageListOrderITTest.DAMAGED_LISTS` (niord-base's test sources) lists them, read-only, and `REPAIR_INDEXES` renumbers them 0 to n-1 by stored index and then row id (a list stored with every index 0 thus by row id). The fix does not repair them: such a message still shows one element per list, a save writes the shown elements anew beside the hidden rows, and one whose shown part has two event dates still cannot be saved (409, `TransientPropertyValueException`), until its lists are renumbered. Renumbering restores a list exactly when its rows come from one save; after a further save it shows the hidden rows of the earlier save beside the rows of the later one, so an element can show twice or come back after an editor removed it, and such a message is looked at after the repair. Its deployments run the repair only on a human's say-so, after the version with the fix is deployed (before it, the next save stores 0 again) |
| the first with the error hunt's fixes (Liquibase `V011`) | `MessageFilter.parameters` is `text` (was `character varying(255)`), so a saved filter's whole query is stored. Schema only: a catalog-only change on PostgreSQL, applied at the next start; no stored row changes | At its pin bump to it: nothing, unless it keeps its own copy of something that pins the schema or the changeset list (a database dump made by the base product's regeneration scripts, a changelog checksum pin); regenerate that on the new base |
| the first with the error hunt's fixes (their behaviour at the extension points) | No extension point, protected bean or property and no type of the script allow-list changed; what these do did. (a) A promulgation service (`BasePromulgationService`): a status change rolls back for any exception, a checked `PromulgationException` from `onUpdateMessageStatus` too (`MessageService.updateStatus` and `cancelMessage` are `rollbackFor = Exception.class`; before, a checked exception left the change committed); the minute's publication sweep then sets the message back to DRAFT, and the hourly firing run keeps the area's current exercise in force. A promulgation type whose script fails makes Generate fail for that type: `PromulgationManager.generateMessagePromulgation` throws `PromulgationException` (it answered the unscripted text), and `POST /rest/promulgations/generate/{typeId}` answers that, or a `PromulgationException` of the service's own `generateMessagePromulgation`, with 500 and a detail that names the type, never the error. The NAVTEX and SafetyNET services resolve a selected transmitter or area by its exact name where two differ only in case, refuse a name ambiguous that way and a new one named like an existing one (`IllegalStateException`, 409), refuse an editor's update that selects one that no longer exists (`IllegalArgumentException`, 400) and drop it with a WARN on a create, an import or a status change; `NavtexPromulgationService.persistedTransmitters` (protected) takes a third argument, `refuseUnknown`. (b) The script allow-list's beans: `MessageService.updateStatus` throws `IllegalArgumentException` for no status and `IllegalStateException` for a refused transition (it threw `Exception`), and `createMessage` and `updateMessage` set the year of the publish date in the zone of the series' domain; `NavtexPromulgationService.findTransmitterByName` and `SafetyNetPromulgationService.findAreaByName` throw `IllegalStateException` for an ambiguous name and let a database failure through (they answered null); `MessageSearchService.searchOrFail` is new beside `search`, which still answers a failure with an empty result; `AreaService` refuses the delete of an area in use (409) and the update of an unknown id (404). (c) Firing schedules: a firing period that does not end after it starts is refused with 400 (`PUT /rest/firing-schedules/firing-area-periods`, nothing of that day is saved), as are that request without its date, area or periods and a schedule without its domain, target domain or target message series or with an unknown one (`POST /rest/firing-schedules/firing-schedule`); a second schedule for a domain is refused with 409. A stored period that ends at or before its start is read by the hourly run as ending one day later. The hourly run's schedule is the property `niord.schedule.firing-exercise-cron` (default the shipped `50 21 */1 * * ?`; `-` switches it off, as ITTest does) | At its pin bump to it: nothing for DK and GL, unless its own code depends on the old behaviour. A promulgation service of its own that throws `PromulgationException` from `onUpdateMessageStatus` on purpose now stops the status change; one that wants the change kept logs instead of throwing. A script or service that catches `Exception` from `updateStatus` keeps working, and one that tells the refusals apart reads the two runtime exceptions. Its own subclass of `NavtexPromulgationService` that calls or overrides `persistedTransmitters` passes or takes `refuseUnknown`. Its firing-schedule data that holds periods ending at or before their start keeps working (read as ending the next day), and its tools that post such periods or a second schedule for a domain get the 400 or 409. Its ITs that commit firing schedules turn the run off with `niord.schedule.firing-exercise-cron=-` where they do not extend ITTest |
| the first with the endpoint sweep and its fixes | No extension point, protected bean or property and no type of the script allow-list changed; what these do did. `AreaService.createArea` and `CategoryService.createCategory` with a parent id that names no row throw `NiordWebException` 404 ("No Area with id N", "No Category with id N"; they threw a `NullPointerException`). `UserService.joinKeycloakGroup` and `leaveKeycloakGroup` throw `NiordWebException` where they returned having done nothing: 403 when Keycloak refuses, 404 when it has no such user or group, 502 when it fails or cannot be reached. `KeycloakIntegrationService`'s admin calls throw the new checked `org.niord.core.keycloak.KeycloakAdminException`, which carries Keycloak's status, with the message they had (they threw `Exception`). `DomainService.createDomain` throws `IllegalArgumentException` (400) for a blank domain id or name, and `updateDomain` for a blank name, before Keycloak is asked. `PublicationService.updateStatus` throws `IllegalArgumentException` for no status and `IllegalStateException` (409) for a refused transition (it threw `Exception`). A template mail goes out without a stylesheet the backend cannot fetch from its `baseUri`, with a WARN (`HtmlMail.inlineStyles`; the mail failed before). An unknown template name is logged at DEBUG by `FmTemplateBuilder.process` (it was an ERROR with a stack) and still throws; the light-character and call-sign directives log their fallback to the raw value at WARN. The endpoint sweep and its table are test code of niord-base and reach no country build | At its pin bump to it: nothing, unless its own code calls one of these and relied on the old outcome: a caller of `joinKeycloakGroup` or `leaveKeycloakGroup` that went on after a failure now gets the exception. Its language packs gain the admin key `admin.users.toast.groupRefused` |
| the first with the error hunt's phase 2 fixes | No extension point, protected bean or property and no type of the script allow-list changed, and there is no Liquibase change set; what some of them do did. (a) Every handler, a country's own `@RestController` included (`RestExceptionStatusAdvice` advises them all): a value longer than its column answers 400 "A value is longer than its field allows: at most N characters" (it was 500), and the loser of a database deadlock 409 "This request crossed another one at the database and was stopped; try again". (b) The status endpoints (`PUT /rest/messages/message/{id}/status` and `/update-statuses`) refuse with 409 a step from DRAFT to VERIFIED or from VERIFIED to PUBLISHED of a message with no title, or with a MANDATORY promulgation type of the current domain not ticked, and a step to PUBLISHED before the message's publish-from date (`MessagePublishGate`); `MessageService.updateStatus` itself is not gated, so the scheduler, the firing run, the imports and scripts are unchanged; `POST /rest/messages/message` creates a message as a DRAFT only and answers 400 for any other status, so a status change goes through the status endpoints (`MessageService.createMessage` is unchanged). (c) A message number is taken inside the publication (`MessageSeriesService.lockMessageNumber`, `SequenceService.takeNextValue` and `lockNextValue`, both new and needing the caller's transaction): a failed publication leaves no gap, two in one series and year wait for each other, and `MessageService.cancelMessage` locks the number first when it will issue a cancellation warning. `SequenceService.nextValue` keeps its signature and its own transaction; `peekNextValue` and `resetNextValue` now join the caller's transaction instead of committing on their own. So two publications at once can no longer take the same number. `PUT /rest/message-series/series/{seriesIds}/number/{year}` (`MessageSeriesService.setNextMessageNumber`) refuses with 400 (`IllegalArgumentException`, a detail that says why) a next number below the one Compute gives for a YEARLY or CONTINUOUS series (the highest number used in the series and year, in any year for a CONTINUOUS series, plus one), after it has locked the series' number row as a publication does, so a sysadmin's reset can no longer give a number twice either; Compute's number, any above it, and any number of 0 or more in a series and year with no numbered message are accepted as before. `POST /rest/messages/message` and `PUT /rest/messages/message/{id}` keep no message number from the body for a message of a series that numbers its warnings itself (YEARLY or CONTINUOUS, as the current domain holds the series): a DRAFT or VERIFIED message keeps none, and one with a later status keeps its stored number; a MANUAL or NONE series keeps the body's number, and the imports, the integration and the firing run are unchanged. (d) `FiringExerciseService.updateFiringExercises(Set<Integer>)` returns `FiringExerciseService.FiringRunResult` (failed schedules, areas and cancellations) instead of `void`: source-compatible for a caller that ignores it, but a class compiled against the old library is recompiled. `MessageSeriesService` gains `currentYear(MessageSeries)` and the static `yearAt(Instant, ZoneId)`; `S124Mapper.toValidated` without a `ZoneId` is deprecated (it maps in the JVM's zone). (e) Settings: `SettingsService.get`, `peek`, `getInteger`, `getLong`, `getFloat` and `getDouble` answer a number key whose row is empty with the default, not null; `/rest/settings/web` serves only rows whose key a settings catalog declares, so an undeclared legacy row (`faMrnPrefix`, `frontPageMessageSeries`, `lightCharacterUrl`, `numeralLanguages`) stays in the database and leaves the list; the Settings page shows the catalog's description of every declared key, also where the stored row holds an older one. (f) niord-base's defaults set `spring.task.scheduling.pool.size: 4` (Spring Boot's default is one thread) and the auto-publish sweep's default cron is `7 * * * * ?` (was `37 * * * * ?`); a due message whose publication meets another transaction's lock (a deadlock or a lock timeout), a version conflict or a database connection timeout stays VERIFIED and is tried again the next minute, where any other failure still sets it back to DRAFT; each of the sweep's units waits for another transaction's lock at most `niord.schedule.lock-timeout` (new, default `20s`, environment variable `NIORD_SCHEDULE_LOCK_TIMEOUT`; zero or less sets no limit), a key no deployment has. (g) Imports and bootstrap: a geometry with a longitude beyond 180 is stored moved by whole turns of 360 degrees, and a latitude beyond 90, a coordinate that is not a finite number or a geometry wider than the globe is refused (400), in the message, area and chart endpoints and the archive, reference-data and chart imports; the Niord integration skips a served warning with a position off the globe, with a WARN naming it, and imports the rest of the run, and a firing-area template with such a position costs only its own area (counted in `failedAreas`; that area's current exercise stays in force); a list value of a parameter type given by its key alone, with a key that more than one dictionary holds, is refused (400; a bootstrap or import job fails, naming the dictionaries); the publication category import skips a row that would create a category whose id holds `/`, `\`, `%`, `;` or a control character, or is `.` or `..`. (h) Mailing-list subjects write the month words of `${date-short}` and `${date-long}` in the trigger's language, and the date in the trigger's zone for a scheduled trigger and in the zone of the message series' domain for a status-change trigger (the JVM's zone where neither is known). (i) The phase's template fixes reach a running deployment only by a sysadmin's import of `bootstrap/releases/2026-11-16-error-hunt-phase-2-templates` (its README): `templates/messages/message-support.ftl` names a model language without a `lang.<code>` text by its code, and `templates/tmpl/common.js` no longer has `updateNavtexTransmitters` | At its pin bump to it: nothing in code, unless its own code relied on an old outcome above: recompile against the new library (d); a client or script of its own that creates a message over REST with a status other than DRAFT creates it as a draft and then changes its status through the status endpoints (b); a promulgation service of its own with a MANDATORY type adds the type's row in `onLoadSystemMessage`, as the base product's services do (the base class adds none): a message that holds no ticked row of the type is refused by (b); a web setting its frontend reads is declared by an `@Setting` of its own code, and so in its generated `defaults/country/settings-catalog.json`, which must be current, or it leaves `/rest/settings/web` (e); its bootstrap gives no list value of a parameter type by a key that two of its dictionaries hold, or the bootstrap fails naming them (g); a tool or script of its own that sets a series' next number below one already used gets the 400 or the `IllegalArgumentException` (c); its own script that calls `updateNavtexTransmitters` fails once a sysadmin imports the release folder's `common.js` (it failed at that call before); a `common.js` or `message-support.ftl` its bootstrap shadows keeps its own copy, and the fix is carried into that copy in a release folder of its own. A deployment that sets `niord.schedule.auto-publish-cron` or `spring.task.scheduling.pool.size` (`SPRING_TASK_SCHEDULING_POOL_SIZE`) keeps its value. Its language packs gain the administration texts that `.niord/kit/API-CHANGES.md` lists |