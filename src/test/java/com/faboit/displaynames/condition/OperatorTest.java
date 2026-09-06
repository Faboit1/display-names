package com.faboit.displaynames.condition;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OperatorTest {

    @Test
    void aLongerOperatorIsAlwaysDeclaredBeforeOneItStartsWith() {
        // The parser takes the first operator that matches at a position, so this ordering is the
        // only thing stopping "!=" from parsing as "!" plus "=" and "!<-" as "!" plus "<-".
        for (Operator longer : Operator.VALUES) {
            for (Operator shorter : Operator.VALUES) {
                if (longer == shorter || !longer.token().startsWith(shorter.token())) continue;
                assertTrue(longer.ordinal() < shorter.ordinal(),
                        longer.token() + " must be declared before " + shorter.token());
            }
        }
    }

    @Test
    void textComparisonsIgnoreCase() {
        // A deliberate difference from TAB: placeholder output casing is not ours to control, and
        // a condition that silently never matches is the worst failure mode this feature has.
        assertTrue(Operator.EQUALS.matches("YES", "yes"));
        assertFalse(Operator.NOT_EQUALS.matches("YES", "yes"));
        assertTrue(Operator.CONTAINS.matches("Survival Mode", "vival"));
        assertTrue(Operator.STARTS_WITH.matches("Owner", "own"));
        assertTrue(Operator.ENDS_WITH.matches("world_nether", "NETHER"));
    }

    @Test
    void theNegatedTextOperatorsAreTheExactOpposites() {
        String[][] pairs = {{"abc", "b"}, {"abc", "z"}, {"abc", "abc"}, {"abc", ""}};
        for (String[] pair : pairs) {
            assertEquals(!Operator.CONTAINS.matches(pair[0], pair[1]),
                    Operator.NOT_CONTAINS.matches(pair[0], pair[1]));
            assertEquals(!Operator.STARTS_WITH.matches(pair[0], pair[1]),
                    Operator.NOT_STARTS_WITH.matches(pair[0], pair[1]));
            assertEquals(!Operator.ENDS_WITH.matches(pair[0], pair[1]),
                    Operator.NOT_ENDS_WITH.matches(pair[0], pair[1]));
        }
    }

    @Test
    void numbersCompareAsNumbersRatherThanAsText() {
        // "9" > "100" as text, which is exactly the bug this avoids.
        assertTrue(Operator.LESS.matches("9", "100"));
        assertTrue(Operator.GREATER_OR_EQUAL.matches("100", "100"));
        assertTrue(Operator.GREATER.matches("20.5", "20"));
        assertTrue(Operator.LESS_OR_EQUAL.matches("-3", "0"));
    }

    @Test
    void aNumericComparisonAgainstNonNumbersIsFalseRatherThanAnError() {
        // The usual source is a missing expansion, so this happens per player per refresh; a
        // thrown NumberFormatException there would cost more than the whole nametag.
        for (Operator operator : Operator.VALUES) {
            if (!operator.numeric()) continue;
            assertFalse(operator.matches("%vault_eco_balance%", "100"), operator.token());
            assertFalse(operator.matches("100", "%vault_eco_balance%"), operator.token());
        }
    }

    @Test
    void onlyPlainNumbersParse() {
        assertEquals(12.5D, Operator.number("12.5"));
        assertEquals(-4.0D, Operator.number("-4"));
        assertEquals(1000.0D, Operator.number("1e3"));
        assertTrue(Double.isNaN(Operator.number("")));
        assertTrue(Double.isNaN(Operator.number("1,000")));
        assertTrue(Double.isNaN(Operator.number("12 ")));
        // Survives the cheap character pre-scan but is still not a number.
        assertTrue(Double.isNaN(Operator.number("1.2.3")));
        assertTrue(Double.isNaN(Operator.number("-")));
    }

    @Test
    void everyOperatorHasADistinctToken() {
        for (Operator one : Operator.VALUES) {
            for (Operator other : Operator.VALUES) {
                if (one == other) continue;
                assertFalse(one.token().equals(other.token()), one + " and " + other + " share a token");
            }
        }
    }
}
