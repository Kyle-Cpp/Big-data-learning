package com.footpred.mappers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** P6.5a parsers: worldcup column offsets and flag conventions. */
public class CareerCsvMapperTest {

    @Test
    public void wcSeasonExtractsFirstFourDigitYear() {
        String[] f = new String[]{"", "", "1970 FIFA Men's World Cup"};
        assertEquals("WC-1970", CareerCsvMapper.wcSeason(f, 2));
        String[] g = new String[]{"", "", "", "1991 FIFA Women's World Cup"};
        assertEquals("WC-1991", CareerCsvMapper.wcSeason(g, 3));
        assertNull(CareerCsvMapper.wcSeason(new String[]{"", "", "no year here"}, 2));
    }

    @Test
    public void wcGoalRespectsOwnGoalAndPenaltyInts() {
        // goals.csv shape: tournament_name(3), family(15), given(16),
        // own_goal(25), penalty(26) - normal goal
        double[] v = new double[CareerCsvMapper.DIM];
        assertTrue(CareerCsvMapper.parseWcGoal(cols(3, "1986 FIFA Men's World Cup",
                "Maradona", "Diego", "0", "0"), v));
        assertEquals(1.0, v[CareerCsvMapper.OFF_GOALS], 0.0);
        assertEquals(0.0, v[CareerCsvMapper.OFF_PENS], 0.0);
        assertEquals(0.0, v[CareerCsvMapper.OFF_OGS], 0.0);

        // penalty goal
        double[] p = new double[CareerCsvMapper.DIM];
        assertTrue(CareerCsvMapper.parseWcGoal(cols(3, "2014 FIFA Men's World Cup",
                "Müller", "Thomas", "0", "1"), p));
        assertEquals(1.0, p[CareerCsvMapper.OFF_GOALS], 0.0);
        assertEquals(1.0, p[CareerCsvMapper.OFF_PENS], 0.0);

        // own goal: not counted as the scorer's goal
        double[] o = new double[CareerCsvMapper.DIM];
        assertTrue(CareerCsvMapper.parseWcGoal(cols(3, "2018 FIFA Men's World Cup",
                "Bianchi", "Cristian", "1", "0"), o));
        assertEquals(0.0, o[CareerCsvMapper.OFF_GOALS], 0.0);
        assertEquals(1.0, o[CareerCsvMapper.OFF_OGS], 0.0);
    }

    @Test
    public void intlGoalRespectsTrueFalseFlags() {
        double[] v = new double[CareerCsvMapper.DIM];
        assertTrue(CareerCsvMapper.parseIntlGoal(
                new String[]{"2006-06-16", "Serbia and Montenegro", "Argentina",
                        "Argentina", "Lionel Messi", "74", "FALSE", "FALSE"}, v));
        assertEquals(1.0, v[CareerCsvMapper.OFF_GOALS], 0.0);

        double[] p = new double[CareerCsvMapper.DIM];
        assertTrue(CareerCsvMapper.parseIntlGoal(
                new String[]{"2022-12-18", "Argentina", "France",
                        "Argentina", "Lionel Messi", "23", "FALSE", "TRUE"}, p));
        assertEquals(1.0, p[CareerCsvMapper.OFF_PENS], 0.0);

        double[] o = new double[CareerCsvMapper.DIM];
        assertTrue(CareerCsvMapper.parseIntlGoal(
                new String[]{"1916-07-02", "Chile", "Uruguay",
                        "Uruguay", "José Piendibene", "44", "TRUE", "FALSE"}, o));
        assertEquals(1.0, o[CareerCsvMapper.OFF_OGS], 0.0);
        assertEquals(0.0, o[CareerCsvMapper.OFF_GOALS], 0.0);

        assertFalse(CareerCsvMapper.parseIntlGoal(
                new String[]{"date", "home", "away", "team", "", "90", "FALSE", "FALSE"},
                new double[4]));
        assertFalse(CareerCsvMapper.parseIntlGoal(
                new String[]{"bad", "home", "away", "team", "x", "90", "FALSE", "FALSE"},
                new double[4]));
    }

    /** Builds a sparse goals.csv-shaped array with the flag columns set. */
    private static String[] cols(int tournamentCol, String tournament,
                                 String family, String given, String own, String pen) {
        String[] f = new String[Math.max(27, tournamentCol + 1)];
        f[tournamentCol] = tournament;
        f[15] = family;
        f[16] = given;
        f[25] = own;
        f[26] = pen;
        return f;
    }
}
