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
package org.niord.test.sample;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How the sample names a base row (docs/code-data-config-plan.md §8): by its natural key, never by its id, so that
 * the sample loads on any country's backend after the base product's bootstrap, whatever ids the country's first
 * start and the bootstrap gave the base rows. The one owner of the key shapes: the regeneration writes the
 * references into the INSERTs and the guard's checks with them ({@link SampleTool}), and the safety check admits
 * exactly these shapes as values ({@link SampleSafetyCheck}).
 *
 * <p>A shape is a {@code FROM ... WHERE} clause over the alias {@code t}, with string slots and number slots. A
 * reference is {@code (SELECT t.id <clause>)}, the guard's count {@code (SELECT count(*) <clause>)}:
 * <ul>
 *   <li>Dictionary by name;</li>
 *   <li>DictionaryEntry by its dictionary's name and its key (a key repeats across dictionaries, {@code term.and});</li>
 *   <li>PromulgationType by typeId;</li>
 *   <li>Category by MRN when it has one, else by its English name among the categories without one;</li>
 *   <li>TemplateParam by its owner and position, since paramId, type and English name repeat among the base
 *       parameters: a category's (that category's MRN or English name, the list position, the paramId) or a
 *       composite parameter type's (the type's name, the list position, the paramId);</li>
 *   <li>ParamType by name, for the guard only: the sample's template parameters name a base parameter type by
 *       name in their type, and no sample row names one by id.</li>
 * </ul>
 * The sample's own rows take the ids from {@link #FIRST_ID} on, in every table.
 */
public final class SampleReferences {

    /**
     * The sample's first id, in every table. Far above what a base bootstrap and a country's first start write
     * (a few thousand rows a table, a JVM start skipping at most 49 ids of a sequence with INCREMENT BY 50), and
     * below the int4 limit of the ids
     */
    public static final int FIRST_ID = 1_000_001;

    /** A string slot of a shape */
    static final String TEXT = "?";

    /** A number slot of a shape */
    static final String NUMBER = "#";

    /** The key shapes: the table, the shape's code in the regeneration's keys file, its label, its clause */
    public enum Shape {
        DICTIONARY("Dictionary", "name", "", true,
                "FROM public.\"Dictionary\" t WHERE t.name = ?"),
        DICTIONARY_ENTRY("DictionaryEntry", "key", "", true,
                "FROM public.\"DictionaryEntry\" t JOIN public.\"Dictionary\" d ON d.id = t.dictionary_id "
                        + "WHERE d.name = ? AND t.dict_key = ?"),
        PROMULGATION_TYPE("PromulgationType", "typeId", "", true,
                "FROM public.\"PromulgationType\" t WHERE t.\"typeId\" = ?"),
        CATEGORY_BY_MRN("Category", "mrn", "mrn ", true,
                "FROM public.\"Category\" t WHERE t.mrn = ?"),
        CATEGORY_BY_NAME("Category", "name", "", true,
                "FROM public.\"Category\" t JOIN public.\"CategoryDesc\" d ON d.entity_id = t.id AND d.lang = 'en' "
                        + "WHERE t.mrn IS NULL AND d.name = ?"),
        TEMPLATE_PARAM_OF_CATEGORY_BY_MRN("TemplateParam", "category-mrn", "category mrn ", true,
                "FROM public.\"TemplateParam\" t JOIN public.\"Category_TemplateParam\" o ON o.\"templateParams_id\" = t.id "
                        + "JOIN public.\"Category\" c ON c.id = o.\"Category_id\" "
                        + "WHERE c.mrn = ? AND o.\"indexNo\" = # AND t.\"paramId\" = ?"),
        TEMPLATE_PARAM_OF_CATEGORY("TemplateParam", "category", "category ", true,
                "FROM public.\"TemplateParam\" t JOIN public.\"Category_TemplateParam\" o ON o.\"templateParams_id\" = t.id "
                        + "JOIN public.\"Category\" c ON c.id = o.\"Category_id\" "
                        + "JOIN public.\"CategoryDesc\" d ON d.entity_id = c.id AND d.lang = 'en' "
                        + "WHERE c.mrn IS NULL AND d.name = ? AND o.\"indexNo\" = # AND t.\"paramId\" = ?"),
        TEMPLATE_PARAM_OF_TYPE("TemplateParam", "type", "type ", true,
                "FROM public.\"TemplateParam\" t JOIN public.\"ParamType_TemplateParam\" o ON o.\"templateParams_id\" = t.id "
                        + "JOIN public.\"ParamType\" p ON p.id = o.\"CompositeParamType_id\" "
                        + "WHERE p.name = ? AND o.\"indexNo\" = # AND t.\"paramId\" = ?"),
        PARAM_TYPE("ParamType", "name", "", false,
                "FROM public.\"ParamType\" t WHERE t.name = ?");

        final String table;
        final String code;
        final String label;
        final boolean reference;
        final String clause;
        final List<String> slots = new ArrayList<>();
        final Pattern pattern;

        Shape(String table, String code, String label, boolean reference, String clause) {
            this.table = table;
            this.code = code;
            this.label = label;
            this.reference = reference;
            this.clause = clause;
            StringBuilder regex = new StringBuilder(Pattern.quote("(SELECT t.id "));
            Matcher m = Pattern.compile("[?#]").matcher(clause);
            int at = 0;
            while (m.find()) {
                regex.append(Pattern.quote(clause.substring(at, m.start())));
                slots.add(m.group());
                regex.append(TEXT.equals(m.group()) ? "'([^']*+(?:''[^']*+)*+)'" : "(0|[1-9][0-9]{0,8})");
                at = m.end();
            }
            regex.append(Pattern.quote(clause.substring(at) + ")"));
            this.pattern = Pattern.compile(regex.toString());
        }

        /** @return the table whose rows the shape names */
        public String table() {
            return table;
        }

        /** @return the shape's code in the regeneration's keys file */
        public String code() {
            return code;
        }

        /** @return whether a value of an INSERT may take the shape (else the guard alone uses it) */
        public boolean reference() {
            return reference;
        }
    }

    private SampleReferences() {
    }

    /**
     * A base row, named by its key parts in a shape
     *
     * @param shape the shape
     * @param parts the key parts, one per slot: the text of a string slot, the digits of a number slot
     */
    public record Reference(Shape shape, List<String> parts) {

        public Reference {
            Objects.requireNonNull(shape);
            parts = List.copyOf(parts);
            if (parts.size() != shape.slots.size()) {
                throw new IllegalArgumentException("A " + shape.table + " key by " + shape.code + " has "
                        + shape.slots.size() + " parts, not " + parts.size() + ": " + parts);
            }
            for (int i = 0; i < parts.size(); i++) {
                if (NUMBER.equals(shape.slots.get(i)) && !parts.get(i).matches("0|[1-9][0-9]{0,8}")) {
                    throw new IllegalArgumentException("Part " + (i + 1) + " of a " + shape.table + " key by "
                            + shape.code + " is a number, not " + parts.get(i));
                }
            }
        }

        /** @return the shape's clause with the parts in its slots */
        String clause() {
            StringBuilder out = new StringBuilder();
            Matcher m = Pattern.compile("[?#]").matcher(shape.clause);
            int at = 0;
            int part = 0;
            while (m.find()) {
                out.append(shape.clause, at, m.start());
                String value = parts.get(part++);
                out.append(TEXT.equals(m.group()) ? quote(value) : value);
                at = m.end();
            }
            return out.append(shape.clause.substring(at)).toString();
        }

        /** @return the reference, a scalar subselect of the row's id */
        public String value() {
            return "(SELECT t.id " + clause() + ")";
        }

        /** @return the guard's count of the rows the key names */
        public String count() {
            return "(SELECT count(*) " + clause() + ")";
        }

        /** @return the key as the guard's refusal names it */
        public String text() {
            return shape.label + String.join(" / ", parts);
        }
    }

    /**
     * The shape of a table by its code in the regeneration's keys file
     *
     * @param table the table
     * @param code the code
     * @return the shape, or null when the table has none of that code
     */
    public static Shape shape(String table, String code) {
        for (Shape shape : Shape.values()) {
            if (shape.table.equals(table) && shape.code.equals(code)) {
                return shape;
            }
        }
        return null;
    }

    /**
     * Reads a value of an INSERT as a reference, in exactly one of the reference shapes
     *
     * @param value the value as written
     * @return the reference, or null when the value is not one
     */
    public static Reference parse(String value) {
        for (Shape shape : Shape.values()) {
            Matcher m = shape.pattern.matcher(value);
            if (shape.reference && m.matches()) {
                List<String> parts = new ArrayList<>();
                for (int i = 1; i <= m.groupCount(); i++) {
                    parts.add(TEXT.equals(shape.slots.get(i - 1)) ? m.group(i).replace("''", "'") : m.group(i));
                }
                return new Reference(shape, parts);
            }
        }
        return null;
    }

    /**
     * The guard's check of one table's keys, inside its DO block (whose DECLARE has {@code missing text} and the
     * remedy {@code unclean}): every key resolves to exactly one row, or the load is refused, naming the keys that do
     * not. It reads only (SELECT, FROM, JOIN, WHERE, VALUES), as the guard's rule demands.
     *
     * @param table the table
     * @param references the keys of its rows the sample names
     * @return the check, lines indented as the guard's body is
     */
    public static String guard(String table, Collection<Reference> references) {
        List<Reference> sorted = references.stream().sorted(Comparator.comparing(Reference::text)).toList();
        if (sorted.isEmpty() || sorted.stream().anyMatch(r -> !r.shape().table.equals(table))) {
            throw new IllegalArgumentException("The guard checks keys of \"" + table + "\" only, and one at least");
        }
        StringBuilder out = new StringBuilder("    missing := (SELECT string_agg(k, ', ' ORDER BY k) FROM (VALUES\n");
        for (int i = 0; i < sorted.size(); i++) {
            Reference r = sorted.get(i);
            out.append("            (").append(quote(r.text())).append(", ").append(r.count()).append(")")
                    .append(i + 1 < sorted.size() ? ",\n" : ") v(k, n) WHERE n <> 1);\n");
        }
        return out.append("    IF missing IS NOT NULL THEN\n")
                .append("        RAISE EXCEPTION 'niord-sample: base rows of \"").append(table)
                .append("\" that the sample refers to are missing or not unique: %; %', missing, unclean;\n")
                .append("    END IF;\n")
                .toString();
    }

    /** A string literal, its quotes doubled */
    static String quote(String text) {
        return "'" + text.replace("'", "''") + "'";
    }
}
