package com.footpred.mappers;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Tournament category buckets used by the stratified evaluation. */
public class CategoryTest {

    @Test
    public void realTournamentValuesFromDataset() {
        assertEquals("major", TrainingDataMapper.categoryOf("FIFA World Cup"));
        assertEquals("major", TrainingDataMapper.categoryOf("UEFA Euro"));
        assertEquals("major", TrainingDataMapper.categoryOf("Copa América"));
        assertEquals("major", TrainingDataMapper.categoryOf("African Cup of Nations"));
        assertEquals("major", TrainingDataMapper.categoryOf("AFC Asian Cup"));
        assertEquals("major", TrainingDataMapper.categoryOf("Gold Cup"));
        assertEquals("qual", TrainingDataMapper.categoryOf("FIFA World Cup qualification"));
        assertEquals("qual", TrainingDataMapper.categoryOf("UEFA Euro qualification"));
        assertEquals("qual", TrainingDataMapper.categoryOf("African Cup of Nations qualification"));
        assertEquals("qual", TrainingDataMapper.categoryOf("AFC Asian Cup qualification"));
        assertEquals("regional", TrainingDataMapper.categoryOf("UEFA Nations League"));
        assertEquals("regional", TrainingDataMapper.categoryOf("CONCACAF Nations League"));
        assertEquals("regional", TrainingDataMapper.categoryOf("COSAFA Cup"));
        assertEquals("regional", TrainingDataMapper.categoryOf("AFF Championship"));
        assertEquals("regional", TrainingDataMapper.categoryOf("Gulf Cup"));
        assertEquals("friendly", TrainingDataMapper.categoryOf("Friendly"));
    }

    @Test
    public void caseInsensitiveAndTrimmed() {
        assertEquals("friendly", TrainingDataMapper.categoryOf(" friendly "));
        assertEquals("major", TrainingDataMapper.categoryOf("fifa world cup"));
        assertEquals("qual", TrainingDataMapper.categoryOf("FIFA World Cup Qualification"));
        assertEquals("regional", TrainingDataMapper.categoryOf("uefa nations league"));
    }

    @Test
    public void unknownTournamentsStayExplicitOther() {
        assertEquals("other", TrainingDataMapper.categoryOf("King's Cup"));
        assertEquals("other", TrainingDataMapper.categoryOf("Baltic Cup"));
        assertEquals("other", TrainingDataMapper.categoryOf("Kirin Cup"));
        assertEquals("other", TrainingDataMapper.categoryOf(null));
        assertEquals("other", TrainingDataMapper.categoryOf(""));
        assertEquals("other", TrainingDataMapper.categoryOf("   "));
    }
}
