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
package org.niord.core.db;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.niord.core.chart.Chart;
import org.niord.test.ITTest;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Integration tests for {@link CriteriaHelper}, the small JPA-Criteria predicate-builder used
 * throughout Niord's search services.
 *
 * <p>Rather than testing the helper in isolation (its predicates are only meaningful when handed to
 * a real database via a {@link jakarta.persistence.criteria.CriteriaBuilder}), every test builds a
 * handful of {@link Chart} rows with {@code em.persist(..)} + {@code em.flush()}, then constructs a
 * query through {@code CriteriaHelper} and EXECUTES it, asserting the exact set of matched rows.
 * {@link Chart} is used because it is a plain entity with no required FK associations and offers a
 * String attribute ({@code name}/{@code chartNumber}) and two nullable Integer attributes
 * ({@code scale}/{@code internationalNumber}) — everything the helper's methods need to exercise.
 *
 * <p>Each test runs in its own {@link TestTransaction} (rolled back at the end) and tags every row
 * it creates with a per-test unique {@code chartNumber} prefix. Each query AND-restricts on that
 * prefix (via {@code startsWith(chartNumber, prefix)}) so the per-test rows are the entire universe
 * the assertions reason about, even though other (seeded or concurrent) Chart rows may exist in the
 * database. The unique prefix also guarantees the {@code chartNumber} UNIQUE constraint is satisfied
 * for uncommitted rows.
 *
 * <p>This is a framework-coupled test (it needs a live EntityManager + database to run the criteria
 * queries); see docs/framework-coupled-tests.md.
 */
class CriteriaHelperITTest extends ITTest {

    @PersistenceContext
    EntityManager em;

    /** Per-test unique prefix; tags every Chart this test creates so queries see only its rows. */
    private String prefix;

    @BeforeEach
    void setUp() {
        // Unique per test invocation; "CH" keeps it a valid-looking chart number.
        prefix = "CH" + System.nanoTime() + "-";
    }

    // ------------------------------------------------------------------------------------------- //
    // Helpers
    // ------------------------------------------------------------------------------------------- //

    /**
     * Persists (and flushes) a Chart with the given (prefixed) chartNumber suffix, name, scale and
     * internationalNumber. Returns the chartNumber actually stored (prefix + suffix).
     */
    private String chart(String suffix, String name, Integer scale, Integer internationalNumber) {
        Chart c = new Chart();
        c.setChartNumber(prefix + suffix);
        c.setName(name);
        c.setScale(scale);
        c.setInternationalNumber(internationalNumber);
        c.setActive(true);
        em.persist(c);
        em.flush();
        return c.getChartNumber();
    }

    /** Convenience for charts that only need a name (scale / international number left null). */
    private String namedChart(String suffix, String name) {
        return chart(suffix, name, null, null);
    }

    /** Starts a CriteriaHelper query over Chart, restricted to THIS test's rows by prefix. */
    private CriteriaQueryFixture newQuery() {
        CriteriaHelper<Chart> ch = CriteriaHelper.initWithQuery(em, Chart.class);
        Root<Chart> root = ch.getCriteriaQuery().from(Chart.class);
        // Universe = the rows created by this test only.
        ch.startsWith(root.get("chartNumber"), prefix);
        return new CriteriaQueryFixture(ch, root);
    }

    /** Holds the helper + root together so a test can add predicates then execute. */
    private final class CriteriaQueryFixture {
        final CriteriaHelper<Chart> ch;
        final Root<Chart> root;

        CriteriaQueryFixture(CriteriaHelper<Chart> ch, Root<Chart> root) {
            this.ch = ch;
            this.root = root;
        }

        /** Attaches predicates via the consumer, then returns this fixture for execution. */
        CriteriaQueryFixture build(FixtureConsumer consumer) {
            consumer.accept(this);
            return this;
        }

        /** Executes the assembled query and returns the matched chartNumbers as a sorted set. */
        Set<String> chartNumbers() {
            ch.getCriteriaQuery().select(root).where(ch.where());
            List<Chart> res = em.createQuery(ch.getCriteriaQuery()).getResultList();
            return res.stream().map(Chart::getChartNumber).collect(Collectors.toCollection(TreeSet::new));
        }
    }

    /** Sorted-set literal so equality comparisons ignore ordering. */
    private static Set<String> setOf(String... values) {
        return new TreeSet<>(Arrays.asList(values));
    }

    // ------------------------------------------------------------------------------------------- //
    // equals
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void equalsMatchesExactValueOnly() {
        String alpha = chart("A", "Alpha", 50000, null);
        chart("B", "Beta", 50000, null);

        Set<String> match = newQuery().build(f -> f.ch.equals(f.root.get("name"), "Alpha")).chartNumbers();
        assertEquals(setOf(alpha), match, "equals(name, 'Alpha') must match only the row named 'Alpha'");
    }

    @Test
    @Transactional
    void equalsIsCaseSensitiveUnderPostgresDefaultCollation_documentsBehavior() {
        // CriteriaHelper.equals emits a plain SQL '=' (cb.equal), so its case-sensitivity is dictated by
        // the column's collation, NOT by the helper. PostgreSQL's default collation is case-SENSITIVE, so
        // 'alpha' does NOT match the stored 'Alpha'. This is a deliberate behaviour change from the original
        // MySQL utf8mb4_..._ci (case-INsensitive) collation — see docs/potential-errors.md (PG1). Use
        // equalsIgnoreCase for a portable, explicitly case-folded match (see the test below).
        String alpha = chart("A", "Alpha", 50000, null);

        Set<String> caseMismatch = newQuery().build(f -> f.ch.equals(f.root.get("name"), "alpha")).chartNumbers();
        assertEquals(Collections.emptySet(), caseMismatch,
                "equals(name, 'alpha') must NOT match 'Alpha' on Postgres — '=' is case-sensitive");

        Set<String> exact = newQuery().build(f -> f.ch.equals(f.root.get("name"), "Alpha")).chartNumbers();
        assertEquals(setOf(alpha), exact, "exact-case equals(name, 'Alpha') still matches the stored row");
    }

    @Test
    @Transactional
    void equalsWithNullValueAddsNoPredicate() {
        String a = chart("A", "Alpha", 50000, null);
        String b = chart("B", "Beta", 50000, null);

        Set<String> match = newQuery().build(f -> f.ch.equals(f.root.get("name"), (String) null)).chartNumbers();
        assertEquals(setOf(a, b), match, "equals(name, null) must add no predicate and return all in-scope rows");
    }

    // ------------------------------------------------------------------------------------------- //
    // equalsIgnoreCase
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void equalsIgnoreCaseMatchesRegardlessOfCase() {
        String a = chart("A", "abc", 50000, null);
        chart("B", "abcd", 50000, null);

        Set<String> match = newQuery().build(f -> f.ch.equalsIgnoreCase(f.root.get("name"), "ABC")).chartNumbers();
        assertEquals(setOf(a), match,
                "equalsIgnoreCase(name, 'ABC') must match the row stored as 'abc' (and not the longer 'abcd')");
    }

    @Test
    @Transactional
    void equalsIgnoreCaseWithNullValueAddsNoPredicate() {
        String a = namedChart("A", "abc");
        String b = namedChart("B", "DEF");

        Set<String> match = newQuery().build(f -> f.ch.equalsIgnoreCase(f.root.get("name"), null)).chartNumbers();
        assertEquals(setOf(a, b), match, "equalsIgnoreCase(name, null) must add no predicate");
    }

    // ------------------------------------------------------------------------------------------- //
    // like
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void likeMatchesCaseInsensitiveSubstring() {
        String a = namedChart("A", "Alpha Bravo");
        String b = namedChart("B", "Charlie BRAVO Delta");
        namedChart("C", "Echo Foxtrot");

        // 'bravo' as a substring, case-insensitive, matches both 'Bravo' and 'BRAVO'.
        Set<String> match = newQuery().build(f -> f.ch.like(f.root.get("name"), "bravo")).chartNumbers();
        assertEquals(setOf(a, b), match, "like(name, 'bravo') must substring-match case-insensitively");
    }

    @Test
    @Transactional
    void likeWithBlankValueAddsNoPredicate() {
        String a = namedChart("A", "Alpha");
        String b = namedChart("B", "Beta");

        Set<String> empty = newQuery().build(f -> f.ch.like(f.root.get("name"), "")).chartNumbers();
        assertEquals(setOf(a, b), empty, "like(name, '') must add no predicate (StringUtils.isNotBlank guard)");

        Set<String> blank = newQuery().build(f -> f.ch.like(f.root.get("name"), "   ")).chartNumbers();
        assertEquals(setOf(a, b), blank, "like(name, whitespace) must add no predicate");
    }

    // ------------------------------------------------------------------------------------------- //
    // startsWith
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void startsWithMatchesPrefixCaseInsensitively() {
        String a = namedChart("A", "Alpha");
        String b = namedChart("B", "alphabet");
        namedChart("C", "Beta Alpha"); // 'alpha' present but not at the start

        Set<String> match = newQuery().build(f -> f.ch.startsWith(f.root.get("name"), "ALPHA")).chartNumbers();
        assertEquals(setOf(a, b), match,
                "startsWith(name, 'ALPHA') must match only names that begin with 'alpha' (case-insensitive)");
    }

    @Test
    @Transactional
    void startsWithBlankValueAddsNoPredicate() {
        String a = namedChart("A", "Alpha");
        String b = namedChart("B", "Beta");

        Set<String> match = newQuery().build(f -> f.ch.startsWith(f.root.get("name"), "")).chartNumbers();
        assertEquals(setOf(a, b), match, "startsWith(name, '') must add no predicate");
    }

    // ------------------------------------------------------------------------------------------- //
    // in
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void inMatchesAnyOfTheGivenValues() {
        String a = namedChart("A", "Alpha");
        String b = namedChart("B", "Beta");
        namedChart("C", "Gamma");

        Set<String> match = newQuery()
                .build(f -> f.ch.in(f.root.get("name"), Set.of("Alpha", "Beta", "Zeta")))
                .chartNumbers();
        assertEquals(setOf(a, b), match, "in(name, {Alpha,Beta,Zeta}) must match the Alpha and Beta rows");
    }

    @Test
    @Transactional
    void inWithEmptyCollectionMatchesNothing() {
        namedChart("A", "Alpha");
        namedChart("B", "Beta");

        Set<String> match = newQuery()
                .build(f -> f.ch.in(f.root.get("name"), Collections.<String>emptySet()))
                .chartNumbers();
        assertEquals(Collections.emptySet(), match,
                "in(name, EMPTY) must add disjunction()==ALWAYS FALSE and therefore match zero rows");
    }

    @Test
    @Transactional
    void inWithNullCollectionMatchesNothing() {
        namedChart("A", "Alpha");
        namedChart("B", "Beta");

        Set<String> match = newQuery()
                .build(f -> f.ch.in(f.root.get("name"), (Collection<String>) null))
                .chartNumbers();
        assertEquals(Collections.emptySet(), match,
                "in(name, null) must add disjunction()==ALWAYS FALSE and therefore match zero rows");
    }

    // ------------------------------------------------------------------------------------------- //
    // between (on Integer scale)
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void betweenWithBothBoundsMatchesInclusiveRange() {
        String low = chart("A", "low", 100, null);
        String mid = chart("B", "mid", 500, null);
        String high = chart("C", "high", 1000, null);
        chart("D", "tooHigh", 2000, null);

        Set<String> match = newQuery()
                .build(f -> f.ch.between(f.root.get("scale"), 100, 1000))
                .chartNumbers();
        assertEquals(setOf(low, mid, high), match,
                "between(scale, 100, 1000) must include both bounds and exclude 2000");
    }

    @Test
    @Transactional
    void betweenWithOnlyLowerBoundMatchesGreaterOrEqual() {
        chart("A", "below", 100, null);
        String onBound = chart("B", "onBound", 500, null);
        String above = chart("C", "above", 1000, null);

        Set<String> match = newQuery()
                .build(f -> f.ch.between(f.root.get("scale"), 500, (Integer) null))
                .chartNumbers();
        assertEquals(setOf(onBound, above), match,
                "between(scale, 500, null) must match scale >= 500 only");
    }

    @Test
    @Transactional
    void betweenWithOnlyUpperBoundMatchesLessOrEqual() {
        String below = chart("A", "below", 100, null);
        String onBound = chart("B", "onBound", 500, null);
        chart("C", "above", 1000, null);

        Set<String> match = newQuery()
                .build(f -> f.ch.between(f.root.get("scale"), (Integer) null, 500))
                .chartNumbers();
        assertEquals(setOf(below, onBound), match,
                "between(scale, null, 500) must match scale <= 500 only");
    }

    @Test
    @Transactional
    void betweenWithBothBoundsNullAddsNoPredicate() {
        String a = chart("A", "a", 100, null);
        String b = chart("B", "b", 1000, null);

        Set<String> match = newQuery()
                .build(f -> f.ch.between(f.root.get("scale"), (Integer) null, (Integer) null))
                .chartNumbers();
        assertEquals(setOf(a, b), match, "between(scale, null, null) must add no predicate");
    }

    // ------------------------------------------------------------------------------------------- //
    // overlaps (treats each row's [scale, internationalNumber] as an interval)
    // ------------------------------------------------------------------------------------------- //
    //
    // overlaps(attr1=scale, attr2=internationalNumber, v1, v2):
    //   if v1 != null : (internationalNumber >= v1 OR internationalNumber IS NULL)
    //   if v2 != null : (scale <= v2 OR scale IS NULL)
    // For a row with scale=S, internationalNumber=I and filter (v1, v2) the row matches when:
    //   (I >= v1 || I == null) && (S <= v2 || S == null)

    @Test
    @Transactional
    void overlapsMatchesIntervalsThatIntersectTheFilterWindow() {
        // Filter window (v1=10, v2=20).
        // [S=5,  I=8 ] : I(8) >= 10? no, and I!=null -> excluded
        chart("A", "low", 5, 8);
        // [S=5,  I=15] : I(15)>=10 yes ; S(5)<=20 yes -> matched
        String b = chart("B", "leftOverlap", 5, 15);
        // [S=15, I=25] : I(25)>=10 yes ; S(15)<=20 yes -> matched
        String c = chart("C", "inside", 15, 25);
        // [S=25, I=30] : I(30)>=10 yes ; S(25)<=20? no, S!=null -> excluded
        chart("D", "high", 25, 30);

        Set<String> match = newQuery()
                .build(f -> f.ch.overlaps(f.root.get("scale"), f.root.get("internationalNumber"), 10, 20))
                .chartNumbers();
        assertEquals(setOf(b, c), match,
                "overlaps(scale, intl, 10, 20) must match only intervals intersecting [10,20]");
    }

    @Test
    @Transactional
    void overlapsTreatsNullUpperAttributeAsOpenEnded() {
        // Filter window (v1=10, v2=20). A null internationalNumber satisfies the (I >= v1 OR I IS NULL) branch.
        // [S=5, I=null] : (I IS NULL) ; S(5)<=20 yes -> matched (open-ended upper)
        String open = chart("A", "openUpper", 5, null);
        // [S=5, I=8]    : I(8)>=10? no, not null -> excluded
        chart("B", "closedLow", 5, 8);

        Set<String> match = newQuery()
                .build(f -> f.ch.overlaps(f.root.get("scale"), f.root.get("internationalNumber"), 10, 20))
                .chartNumbers();
        assertEquals(setOf(open), match,
                "a null internationalNumber must satisfy the open-ended upper branch of overlaps");
    }

    @Test
    @Transactional
    void overlapsTreatsNullLowerAttributeAsOpenEnded() {
        // Filter window (v1=10, v2=20). A null scale satisfies the (S <= v2 OR S IS NULL) branch.
        // [S=null, I=15] : I(15)>=10 yes ; (S IS NULL) -> matched (open-ended lower)
        String open = chart("A", "openLower", null, 15);
        // [S=25,   I=15] : I(15)>=10 yes ; S(25)<=20? no -> excluded
        chart("B", "closedHigh", 25, 15);

        Set<String> match = newQuery()
                .build(f -> f.ch.overlaps(f.root.get("scale"), f.root.get("internationalNumber"), 10, 20))
                .chartNumbers();
        assertEquals(setOf(open), match,
                "a null scale must satisfy the open-ended lower branch of overlaps");
    }

    @Test
    @Transactional
    void overlapsWithOnlyLowerFilterIgnoresUpperBranch() {
        // Only v1=10 set (v2 null) -> only the (I >= 10 OR I IS NULL) predicate is added.
        String a = chart("A", "intl12", 5, 12);   // 12 >= 10 -> matched
        String b = chart("B", "intlNull", 5, null); // null -> matched
        chart("C", "intl8", 5, 8);                  // 8 >= 10? no -> excluded

        Set<String> match = newQuery()
                .build(f -> f.ch.overlaps(f.root.get("scale"), f.root.get("internationalNumber"), 10, (Integer) null))
                .chartNumbers();
        assertEquals(setOf(a, b), match,
                "overlaps with only v1 must add a single (intl >= v1 OR intl IS NULL) predicate");
    }

    @Test
    @Transactional
    void overlapsWithBothFilterBoundsNullAddsNoPredicate() {
        String a = chart("A", "a", 5, 8);
        String b = chart("B", "b", 25, 30);

        Set<String> match = newQuery()
                .build(f -> f.ch.overlaps(f.root.get("scale"), f.root.get("internationalNumber"),
                        (Integer) null, (Integer) null))
                .chartNumbers();
        assertEquals(setOf(a, b), match, "overlaps(null, null) must add no predicate and return all in scope");
    }

    // ------------------------------------------------------------------------------------------- //
    // matchText (pseudo google-style free text)
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void matchTextPlainMultiTokenIsOredSubstring() {
        String a = namedChart("A", "alpha only");
        String b = namedChart("B", "beta only");
        String c = namedChart("C", "gamma only");

        // "alpha beta" => contains alpha OR contains beta.
        Set<String> match = newQuery()
                .build(f -> f.ch.matchText(f.root.get("name"), "alpha beta"))
                .chartNumbers();
        assertEquals(setOf(a, b), match,
                "matchText('alpha beta') must OR the tokens and exclude the gamma-only row");
    }

    @Test
    @Transactional
    void matchTextQuotedPhraseIsExactSubstring() {
        String a = namedChart("A", "the alpha beta gull");   // contains substring "alpha beta"
        namedChart("B", "alpha zzz beta");                   // contains alpha and beta but NOT adjacent

        // "\"alpha beta\"" => substring match on the whole phrase, not the individual tokens.
        Set<String> match = newQuery()
                .build(f -> f.ch.matchText(f.root.get("name"), "\"alpha beta\""))
                .chartNumbers();
        assertEquals(setOf(a), match,
                "a quoted phrase must match the contiguous substring only");
    }

    @Test
    @Transactional
    void matchTextRequiredTokenWithPlusIsAnded() {
        String a = namedChart("A", "alpha beta");   // contains both
        namedChart("B", "alpha gamma");             // missing beta
        namedChart("C", "beta delta");              // missing alpha

        // "+alpha +beta" => must contain alpha AND must contain beta.
        Set<String> match = newQuery()
                .build(f -> f.ch.matchText(f.root.get("name"), "+alpha +beta"))
                .chartNumbers();
        assertEquals(setOf(a), match, "two +tokens must both be required (AND semantics)");
    }

    @Test
    @Transactional
    void matchTextExcludedTokenWithMinusIsNegated() {
        String a = namedChart("A", "alpha clean");   // alpha, no beta -> matched
        namedChart("B", "alpha beta");               // alpha + beta -> excluded by -beta
        namedChart("C", "gamma only");               // no alpha -> excluded by +alpha

        // "+alpha -beta" => contains alpha AND NOT contains beta.
        Set<String> match = newQuery()
                .build(f -> f.ch.matchText(f.root.get("name"), "+alpha -beta"))
                .chartNumbers();
        assertEquals(setOf(a), match, "'+alpha -beta' must require alpha and exclude any name containing beta");
    }

    @Test
    @Transactional
    void matchTextLeadingMinusOnlyExcludes() {
        String a = namedChart("A", "alpha");   // no beta -> matched
        namedChart("B", "beta gull");          // has beta -> excluded

        // "-beta" => NOT contains beta.
        Set<String> match = newQuery()
                .build(f -> f.ch.matchText(f.root.get("name"), "-beta"))
                .chartNumbers();
        assertEquals(setOf(a), match, "a lone '-beta' must exclude every name containing beta");
    }

    @Test
    @Transactional
    void matchTextWildcardStarMapsToSqlPercent() {
        String a = namedChart("A", "alpha");
        String b = namedChart("B", "alphabet");
        namedChart("C", "beta");

        // "alph*" => '*' replaced with '%' and wrapped: like '%alph%%' => contains 'alph'.
        Set<String> match = newQuery()
                .build(f -> f.ch.matchText(f.root.get("name"), "alph*"))
                .chartNumbers();
        assertEquals(setOf(a, b), match, "a '*' wildcard must map to SQL '%' and match both alpha and alphabet");
    }

    @Test
    @Transactional
    void matchTextMixedRequiredOredAndExcluded() {
        // Query "+alpha beta -gamma":
        //   +alpha  -> AND contains alpha
        //   beta    -> OR contains beta (combined into the running predicate with cb.and at the +alpha step,
        //              then this plain token is OR-ed onto it)
        //   -gamma  -> AND NOT contains gamma
        // Net effect, evaluating left-to-right as the helper builds it:
        //   ((contains alpha) OR (contains beta)) AND NOT contains gamma
        String a = namedChart("A", "alpha sea");          // alpha, no gamma -> matched
        String b = namedChart("B", "beta sea");           // beta, no gamma -> matched
        namedChart("C", "alpha gamma");                   // has gamma -> excluded
        namedChart("D", "delta only");                    // neither alpha nor beta -> excluded

        Set<String> match = newQuery()
                .build(f -> f.ch.matchText(f.root.get("name"), "+alpha beta -gamma"))
                .chartNumbers();
        assertEquals(setOf(a, b), match,
                "mixed matchText must combine OR-ed tokens, a required token, and an excluded token");
    }

    @Test
    @Transactional
    void matchTextBlankValueAddsNoPredicate() {
        String a = namedChart("A", "alpha");
        String b = namedChart("B", "beta");

        Set<String> empty = newQuery().build(f -> f.ch.matchText(f.root.get("name"), "")).chartNumbers();
        assertEquals(setOf(a, b), empty, "matchText('') must add no predicate");

        Set<String> blank = newQuery().build(f -> f.ch.matchText(f.root.get("name"), "   ")).chartNumbers();
        assertEquals(setOf(a, b), blank, "matchText(whitespace) must add no predicate");
    }

    @Test
    @Transactional
    void matchTextIsCaseInsensitive() {
        String a = namedChart("A", "ALPHA Bravo");

        Set<String> match = newQuery()
                .build(f -> f.ch.matchText(f.root.get("name"), "alpha"))
                .chartNumbers();
        assertEquals(setOf(a), match, "matchText must lower-case both sides and match regardless of case");
    }

    // ------------------------------------------------------------------------------------------- //
    // Compound predicates AND together
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void multiplePredicatesAreAndedTogether() {
        // like(name,'chart') AND between(scale, 100, 1000) — both must hold.
        String hit = chart("A", "Coastal chart", 500, null);
        chart("B", "Coastal chart", 5000, null);  // matches name, out of scale range
        chart("C", "Harbour plan", 500, null);    // in scale range, wrong name

        Set<String> match = newQuery()
                .build(f -> {
                    f.ch.like(f.root.get("name"), "chart");
                    f.ch.between(f.root.get("scale"), 100, 1000);
                })
                .chartNumbers();
        assertEquals(setOf(hit), match,
                "stacked predicates must be AND-ed: only the row satisfying name AND scale survives");
    }

    // ------------------------------------------------------------------------------------------- //
    // Fluent build helper on the fixture
    // ------------------------------------------------------------------------------------------- //

    /** Functional sugar to attach predicates then return the fixture for execution. */
    @FunctionalInterface
    private interface FixtureConsumer {
        void accept(CriteriaQueryFixture fixture);
    }
}
