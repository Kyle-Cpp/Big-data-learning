package com.footpred.backend.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the two identifier normalisers against the values actually present in
 * the delivered artefacts.
 */
class CodeNormalisationTest {

    @Test
    void compactSeasonBecomesLabel() {
        assertEquals("2015-16", SeasonCode.canonical("1516"));
        assertEquals("2025-26", SeasonCode.canonical("2526"));
        assertEquals("2020-21", SeasonCode.canonical("2021"));
    }

    @Test
    void labelSeasonStaysUnchanged() {
        assertEquals("2015-16", SeasonCode.canonical("2015-16"));
        assertEquals("2025-26", SeasonCode.canonical("2025-26"));
    }

    @Test
    void canonicalRoundTripsBackToCompact() {
        assertEquals("1516", SeasonCode.compact("2015-16"));
        assertEquals("1516", SeasonCode.compact("1516"));
        assertEquals("2526", SeasonCode.compact("2025-26"));
    }

    @Test
    void nonSeasonKeysPassThrough() {
        // P6.5 career rows use a "WC-2022" style key that must not be rewritten.
        assertEquals("WC-2022", SeasonCode.canonical("WC-2022"));
        assertFalse(SeasonCode.isKnown("WC-2022"));
        assertNull(SeasonCode.canonical(null));
    }

    @Test
    void seasonStartYearSupportsOrdering() {
        assertEquals(2015, SeasonCode.startYear("1516"));
        assertEquals(2015, SeasonCode.startYear("2015-16"));
        assertTrue(SeasonCode.startYear("2526") > SeasonCode.startYear("1516"));
    }

    @Test
    void leagueNormalisesBothDirections() {
        assertEquals("Premier League", LeagueCode.canonical("premier-league"));
        assertEquals("Premier League", LeagueCode.canonical("Premier League"));
        assertEquals("Premier League", LeagueCode.canonical("PREMIER LEAGUE"));
        assertEquals("premier-league", LeagueCode.kebab("Premier League"));
        assertEquals("la-liga", LeagueCode.kebab("La Liga"));
    }

    @Test
    void unknownLeagueIsPreserved() {
        // The partner package also carries international competitions, which are
        // not one of the five leagues, so they must not be coerced.
        assertEquals("Friendly", LeagueCode.canonical("Friendly"));
        assertFalse(LeagueCode.isKnown("Friendly"));
    }
}
