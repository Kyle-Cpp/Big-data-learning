package com.footpred.common;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class MatchParserTest {

    private static final String HEADER =
            "date,home_team,away_team,home_score,away_score,tournament,city,country,neutral";

    @Test
    public void parsesWellFormedRow() {
        RawMatch m = MatchParser.parse(
                "2022-12-18,Argentina,France,3,3,FIFA World Cup,Lusail,Qatar,TRUE");
        assertNotNull(m);
        assertEquals("2022-12-18", m.date().toString());
        assertEquals("Argentina", m.homeTeam);
        assertEquals("France", m.awayTeam);
        assertEquals(3, m.homeScore);
        assertEquals(3, m.awayScore);
        assertEquals("FIFA World Cup", m.tournament);
        assertTrue(m.neutral);
        assertEquals(java.time.LocalDate.parse("2022-12-18").toEpochDay(), m.epochDay);
    }

    @Test
    public void parsesNonNeutralFriendly() {
        RawMatch m = MatchParser.parse(
                "2019-06-08,Russia,San Marino,9,0,UEFA Euro qualification,Saratov,Russia,FALSE");
        assertNotNull(m);
        assertEquals("UEFA Euro qualification", m.tournament);
        assertTrue(!m.neutral);
        assertTrue(!m.isFriendly());
    }

    @Test
    public void headerRowReturnsNull() {
        assertNull(MatchParser.parse(HEADER));
    }

    @Test
    public void blankOrNullReturnsNull() {
        assertNull(MatchParser.parse(null));
        assertNull(MatchParser.parse(""));
        assertNull(MatchParser.parse("   "));
    }

    @Test
    public void badDateReturnsNull() {
        assertNull(MatchParser.parse(
                "2022-13-45,Argentina,France,3,3,FIFA World Cup,Lusail,Qatar,TRUE"));
        assertNull(MatchParser.parse(
                "18/12/2022,Argentina,France,3,3,FIFA World Cup,Lusail,Qatar,TRUE"));
    }

    @Test
    public void badScoreReturnsNull() {
        assertNull(MatchParser.parse(
                "2022-12-18,Argentina,France,x,3,FIFA World Cup,Lusail,Qatar,TRUE"));
        assertNull(MatchParser.parse(
                "2022-12-18,Argentina,France,-1,3,FIFA World Cup,Lusail,Qatar,TRUE"));
        assertNull(MatchParser.parse(
                "2022-12-18,Argentina,France,,3,FIFA World Cup,Lusail,Qatar,TRUE"));
    }

    @Test
    public void badNeutralFlagReturnsNull() {
        assertNull(MatchParser.parse(
                "2022-12-18,Argentina,France,3,3,FIFA World Cup,Lusail,Qatar,MAYBE"));
    }

    @Test
    public void missingFieldsReturnNull() {
        assertNull(MatchParser.parse("2022-12-18,Argentina,France,3,3"));
    }

    @Test
    public void friendlyDetectionIsCaseInsensitive() {
        RawMatch m = MatchParser.parse(
                "2018-03-27,Germany,Brazil,0,1,Friendly,Berlin,Germany,FALSE");
        assertNotNull(m);
        assertTrue(m.isFriendly());
    }

    @Test
    public void handlesWhitespaceAroundFields() {
        RawMatch m = MatchParser.parse(
                " 2016-06-10 , France , Romania , 2,1 , UEFA Euro , Saint-Denis , France , FALSE ");
        assertNotNull(m);
        assertEquals("France", m.homeTeam);
        assertEquals(2, m.homeScore);
    }
}
