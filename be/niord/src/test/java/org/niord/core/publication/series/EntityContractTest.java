/*
 * Copyright 2026 Danish Emergency Management Agency.
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

package org.niord.core.publication.series;

import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.TableGenerator;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The entity contract: identity, column coverage, and column type.
 *
 * Both are reflection over the classes rather than a reading of the source,
 * because both failures are silent. An entity that quietly acquires its own id
 * generator still works -- until its ids collide with nothing and its rows
 * cannot be joined to the rest of the system. A dropped column still compiles.
 *
 * No database and no application context: plain reflection and the committed changeset.
 */
public class EntityContractTest {

    private static final List<Class<?>> ENTITIES = List.of(
            PublicationSeries.class,
            PublicationSeriesDesc.class,
            PublicationIssue.class,
            PublicationIssueDesc.class,
            IssueMember.class,
            IssueOverride.class,
            IssueAuditEntry.class);

    /**
     * No entity brings an id generator of its own: every one inherits BaseEntity's.
     *
     * PORT: this rule used to read "every id in this system comes from one shared sequence row",
     * which was true of the MySQL schema's single hibernate_sequence. It is not true here, and the
     * new statement is the one above. Hibernate 7 reads BaseEntity's inherited
     * {@code @GeneratedValue(strategy = AUTO)} as a sequence PER ENTITY -- {@code "<Entity>_SEQ"},
     * INCREMENT BY 50, 57 of them in V001 and one per entity in V004 -- so two entity types MAY
     * carry the same id number and nothing is wrong with that.
     *
     * What is asserted is unchanged, and is still the whole contract: inheriting the base class is
     * how an entity gets its id, and anything that overrides it takes one table out of that scheme
     * silently.
     */
    @Test
    public void noEntityBringsItsOwnIdGenerator() {
        List<String> offenders = new ArrayList<>();

        for (Class<?> type : ENTITIES) {
            for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
                if (c.isAnnotationPresent(SequenceGenerator.class)) {
                    offenders.add(c.getSimpleName() + " carries @SequenceGenerator");
                }
                if (c.isAnnotationPresent(TableGenerator.class)) {
                    offenders.add(c.getSimpleName() + " carries @TableGenerator");
                }
                for (Field f : c.getDeclaredFields()) {
                    if (f.isAnnotationPresent(SequenceGenerator.class)) {
                        offenders.add(c.getSimpleName() + "." + f.getName() + " carries @SequenceGenerator");
                    }
                    if (f.isAnnotationPresent(TableGenerator.class)) {
                        offenders.add(c.getSimpleName() + "." + f.getName() + " carries @TableGenerator");
                    }
                    GeneratedValue g = f.getAnnotation(GeneratedValue.class);
                    if (g != null && g.strategy() == GenerationType.IDENTITY) {
                        offenders.add(c.getSimpleName() + "." + f.getName() + " uses GenerationType.IDENTITY");
                    }
                }
            }
        }

        if (!offenders.isEmpty()) {
            fail("these break the identity contract -- the generator is BaseEntity's:\n  "
                    + String.join("\n  ", offenders));
        }
    }

    /**
     * Every column the specification declares exists on the entity.
     *
     * Checked against a generated manifest rather than by review, so a column
     * dropped during a refactor is a red build. The manifest is regenerated from
     * the data model by gen-field-manifest.js; a stale one is caught in the other repo.
     */
    @Test
    public void everyDeclaredColumnExistsOnItsEntity() throws Exception {
        JsonNode manifest;
        try (InputStream in = EntityContractTest.class.getResourceAsStream("/entity-fields.json")) {
            assertNotNull(in, "entity-fields.json is missing");
            // PORT: Jackson 3 (tools.jackson) is the mapper here; the manifest is read the same way.
            manifest = JsonMapper.builderWithJackson2Defaults().build().readTree(in);
        }

        // Inherited from BaseEntity / VersionedEntity / DescEntity, or replaced by
        // the generalised audit.
        Set<String> notOwnFields = Set.of("id", "version", "created", "updated", "entity", "lang",
                "statusChangedAt", "statusChangedBy", "statusChangeReason");

        List<String> missing = new ArrayList<>();
        int checked = 0;

        for (JsonNode entity : manifest.path("entities")) {
            if (entity.path("existing").asBoolean(false)) {
                continue; // the two pre-existing tables are not ours to declare
            }
            String name = entity.path("entity").asText();
            Class<?> type = ENTITIES.stream()
                    .filter(c -> c.getSimpleName().equals(name))
                    .findFirst()
                    .orElse(null);
            if (type == null) {
                continue; // collection tables have no class of their own
            }

            Set<String> declared = new LinkedHashSet<>();
            for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    declared.add(f.getName());
                }
            }

            for (JsonNode field : entity.path("fields")) {
                String fieldName = field.path("name").asText();
                if (notOwnFields.contains(fieldName)) {
                    continue;
                }
                checked++;
                if (!declared.contains(fieldName)) {
                    missing.add(name + "." + fieldName + "  (" + field.path("ddl").asText() + ")");
                }
            }
        }

        assertTrue(checked > 80, "only " + checked + " fields were checked; the manifest looks truncated");

        if (!missing.isEmpty()) {
            fail(missing.size() + " declared column(s) have no field on their entity:\n  "
                    + String.join("\n  ", missing));
        }
    }

    /**
     * The reserved columns.
     *
     * These belong to work that may never ship, and they are here anyway because
     * adding a column later means hand-rolled DDL against a live schema. This is
     * the one carve-out a reviewer must not tidy away, so it is a test rather than
     * a comment.
     */
    @Test
    public void thePlaceholderColumnsAreReserved() {
        assertHasField(IssueMember.class, "sortIndex");
        assertHasField(PublicationIssue.class, "snapshotSortBy");
        assertHasField(PublicationIssue.class, "snapshotSortOrder");
        assertHasField(PublicationIssue.class, "snapshotDomainId");
        assertHasField(PublicationIssue.class, "snapshotIntervalFrom");
        assertHasField(PublicationIssue.class, "legacyPublicationId");
        assertHasField(PublicationIssue.class, "publicWindowSource");
        assertHasField(PublicationIssue.class, "intervalFromSource");
        assertHasField(PublicationIssue.class, "intervalToSource");
        assertHasField(PublicationIssue.class, "reportParams");
        assertHasField(PublicationIssue.class, "criteriaOverride");
    }

    /** The audit is generalised, so a row can belong to a series instead of an issue. */
    @Test
    public void theAuditIsGeneralisedNotThreeFixedColumns() {
        assertHasField(IssueAuditEntry.class, "issue");
        assertHasField(IssueAuditEntry.class, "series");

        for (String gone : List.of("statusChangedAt", "statusChangedBy", "statusChangeReason")) {
            assertTrue(!hasField(PublicationSeries.class, gone),
                    "PublicationSeries." + gone + " is back. Three fixed columns cannot record a series that "
                            + "was activated, flagged dormant and reactivated -- an event that overwrites its "
                            + "own predecessor is not an audit trail.");
        }
    }

    /**
     * Every collection and map field is non-null on a freshly constructed entity.
     *
     * The converters make this a contract rather than a preference: a null column
     * comes back from JpaPropertiesAttributeConverter as an EMPTY map, so an entity
     * loaded from the database always has one. An uninitialised field therefore
     * leaves exactly one instance that behaves differently -- the one nobody has
     * persisted yet -- and the create path is made entirely of those.
     *
     * PublicationSeries.reportParams was that field. updateFromVo called clear() on
     * it, SystemPublicationSeriesVo initialises its own map so the null-guard above
     * the call was never false, and every create through the REST endpoint answered
     * 500 with an NPE. Not data-dependent: it could not have worked once. The suite
     * missed it because the tests build entities with setters and none of them went
     * through updateFromVo; the seed script found it on the first real call.
     *
     * Asserted over every field of every entity, rather than as a check that those
     * two maps are non-null, because a rule that only knows the fields already fixed
     * would not have caught PublicationIssue.reportParams -- which had the same gap,
     * and which this found.
     */
    @Test
    public void noEntityStartsLifeWithANullCollection() throws Exception {
        List<String> offenders = new ArrayList<>();

        for (Class<?> type : ENTITIES) {
            Constructor<?> ctor = type.getDeclaredConstructor();
            ctor.setAccessible(true);
            Object fresh = ctor.newInstance();

            for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) {
                        continue;
                    }
                    if (!Collection.class.isAssignableFrom(f.getType())
                            && !Map.class.isAssignableFrom(f.getType())) {
                        continue;
                    }
                    f.setAccessible(true);
                    if (f.get(fresh) == null) {
                        offenders.add(type.getSimpleName() + "." + f.getName()
                                + " (" + f.getType().getSimpleName() + ")");
                    }
                }
            }
        }

        assertTrue(offenders.isEmpty(),
                "These fields are null on a newly constructed entity, so any code that mutates them in "
                        + "place -- clear(), add(), put() -- throws for created rows and works for loaded "
                        + "ones: " + offenders + ". Initialise them at the declaration.");
    }

    /**
     * Every enum-typed column keeps the type the changesets actually gave it.
     *
     * PORT: the rule this replaces was about MySQL, where the schema-generation default for an
     * {@code @Enumerated(EnumType.STRING)} field is a NATIVE ENUM column rather than a varchar, and
     * where only columnDefinition could change that. PostgreSQL has no such column type -- there is
     * no {@code CREATE TYPE} anywhere in this schema -- so Hibernate 7 renders a string enum as
     * {@code character varying(255)} plus a NAMED CHECK constraint listing the constants
     * ({@code "PublicationSeries_status_check"} is the shape).
     *
     * The replacement has the same shape and exactly the same thing at stake: a mapping and a
     * changeset that disagree cost nothing while the application runs, because the enum reads and
     * writes its own name either way, and the whole cost lands on the one boot that checks --
     * {@code spring.jpa.hibernate.ddl-auto=validate} refuses to start, and that boot is a deploy.
     * A field with NO columnDefinition expects the checked column the generator renders; a field
     * that PINS {@code columnDefinition = "varchar(255)"} expects the plain, unchecked one, which is
     * how a column whose enum may gain a constant WITHOUT a migration is declared. V004's header
     * names the three that are deliberately unchecked, and the CHECK half of this test is what holds
     * them to three.
     *
     * Stated as agreement between the entity and the changeset rather than as "every enum column is
     * a varchar", because on PostgreSQL that half is true by construction and would agree with
     * everything.
     */
    @Test
    public void everyStringEnumColumnKeepsTheTypeTheChangesetsGaveIt() throws Exception {
        String sql = publicationChangeset();
        Map<String, String> declared = columnTypesFromChangeset(sql);
        assertTrue(declared.size() > 100,
                "only " + declared.size() + " columns were parsed out of " + CHANGESET + "; the scan is "
                        + "broken, and a broken scan agrees with everything");

        List<String> offenders = new ArrayList<>();
        int checked = 0;

        for (Class<?> type : ENTITIES) {
            Table table = type.getAnnotation(Table.class);
            String tableName = table != null && !table.name().isBlank() ? table.name() : type.getSimpleName();

            for (Field f : type.getDeclaredFields()) {
                Enumerated enumerated = f.getAnnotation(Enumerated.class);
                if (enumerated == null || enumerated.value() != EnumType.STRING) {
                    continue;
                }
                checked++;

                Column column = f.getAnnotation(Column.class);
                String columnName = column != null && !column.name().isBlank() ? column.name() : f.getName();
                String pin = column == null ? "" : column.columnDefinition();
                // No columnDefinition means the generator picks, and on PostgreSQL it picks a
                // varchar carrying a CHECK that names every constant.
                String expected = pin.isBlank() ? "varchar" : typeKeyword(pin);

                String actual = declared.get(tableName + "." + columnName);
                if (actual == null) {
                    offenders.add(tableName + "." + columnName + " -- no changeset ever created this column");
                    continue;
                }
                if (!actual.equals(expected)) {
                    offenders.add(tableName + "." + columnName + " -- the changeset created it as " + actual
                            + ", the mapping expects " + expected
                            + (pin.isBlank() ? " (no columnDefinition, so the generator chooses)"
                                             : " (columnDefinition = \"" + pin + "\")"));
                }

                boolean constrained = sql.contains("\"" + tableName + "_" + columnName + "_check\"");
                if (constrained != pin.isBlank()) {
                    offenders.add(tableName + "." + columnName + " -- the changeset "
                            + (constrained ? "constrains it with a CHECK" : "leaves it unconstrained")
                            + ", the mapping "
                            + (pin.isBlank() ? "pins no columnDefinition and so expects one"
                                             : "pins columnDefinition = \"" + pin + "\", which asks for none"));
                }
            }
        }

        assertTrue(checked >= 20, "only " + checked + " enum fields were found; the reflection is broken");

        assertTrue(offenders.isEmpty(),
                "an enum-typed column disagrees with the column the database actually has, so a boot with "
                        + "spring.jpa.hibernate.ddl-auto=validate fails while everything else keeps "
                        + "working. Pin the column with columnDefinition = \"varchar(255)\" where the "
                        + "changeset made a plain varchar -- PublicationIssue.intervalToSource is the "
                        + "pattern -- and leave it unpinned where the changeset checks it:\n  "
                        + String.join("\n  ", offenders));
    }

    /** The changeset that builds this model, as a classpath resource rather than a source path. */
    private static final String CHANGESET = "/db/changelog/changes/V004__publication_series.sql";

    /**
     * The changeset, read from the classpath.
     *
     * PORT: the MySQL suite listed a {@code /db/migration} directory of Flyway files and replayed
     * them in version order. Liquibase builds this model in ONE changeset, so there is one file to
     * read and no ordering to reproduce; V001-V003 hold no publications table.
     */
    private static String publicationChangeset() throws Exception {
        try (InputStream in = EntityContractTest.class.getResourceAsStream(CHANGESET)) {
            assertNotNull(in, CHANGESET + " is not on the test classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** A create-table statement in the shipped changeset; every mixed-case name is quoted. */
    private static final Pattern CREATE_TABLE = Pattern.compile(
            "(?is)create\\s+table\\s+public\\.\"(\\w+)\"\\s*\\((.*?)\\n\\);");

    /** Not a column: the table-level clauses that share the create-table body. */
    private static final Set<String> NOT_A_COLUMN =
            Set.of("primary", "unique", "key", "index", "constraint", "foreign", "check", "exclude");

    /**
     * The schema the committed changeset builds, as column name to type keyword.
     *
     * Read from the file rather than from a live database, so the rule holds on a machine with no
     * PostgreSQL and cannot be satisfied by a database that someone reshaped by hand.
     */
    private static Map<String, String> columnTypesFromChangeset(String sql) {
        Map<String, String> types = new HashMap<>();
        Matcher tables = CREATE_TABLE.matcher(sql);
        while (tables.find()) {
            String table = tables.group(1);
            for (String column : topLevelParts(tables.group(2))) {
                Matcher c = Pattern.compile("(?s)^\"?(\\w+)\"?\\s+(.+)$").matcher(column);
                if (c.find() && !NOT_A_COLUMN.contains(c.group(1).toLowerCase())) {
                    types.put(table + "." + c.group(1), typeKeyword(c.group(2)));
                }
            }
        }
        return types;
    }

    /** Splits a create-table body on the commas that separate columns, not the ones inside a CHECK. */
    private static List<String> topLevelParts(String body) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        for (char c : body.toCharArray()) {
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            }
            if (c == ',' && depth == 0) {
                parts.add(current.toString().trim());
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        if (!current.toString().isBlank()) {
            parts.add(current.toString().trim());
        }
        return parts;
    }

    /**
     * "character varying(255)" and "timestamp(6) without time zone" both reduce to the word the
     * column type is; "varchar(255)", as a mapping pins it, reduces to the same word as the first.
     */
    private static String typeKeyword(String columnDefinition) {
        String type = columnDefinition.trim().toLowerCase();
        if (type.startsWith("character varying")) {
            return "varchar";
        }
        Matcher m = Pattern.compile("^([a-z]+)").matcher(type);
        return m.find() ? m.group(1) : type;
    }

    private static boolean hasField(Class<?> type, String name) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getName().equals(name)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void assertHasField(Class<?> type, String name) {
        assertTrue(hasField(type, name),
                type.getSimpleName() + "." + name + " is missing. Adding it later means hand-rolled DDL "
                        + "against a live schema, which is exactly what reserving it now avoids.");
    }
}
