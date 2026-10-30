package com.footpred.mappers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** P6.8a league label mapping and P6.5b event extraction on JSON literals. */
public class LineupFlattenMapperTest {

    @Test
    public void kebabDirNamesMapToDisplayNames() {
        assertEquals("La Liga", LineupFlattenMapper.leagueLabel("la-liga"));
        assertEquals("Premier League", LineupFlattenMapper.leagueLabel("premier-league"));
        assertEquals("Serie A", LineupFlattenMapper.leagueLabel("serie-a"));
        assertEquals("Bundesliga", LineupFlattenMapper.leagueLabel("bundesliga"));
        assertEquals("Ligue 1", LineupFlattenMapper.leagueLabel("ligue-1"));
        assertEquals("fifa-world-cup", LineupFlattenMapper.leagueLabel("fifa-world-cup"));
    }

    @Test
    public void messiEventExtractionCountsShotsGoalsXgAssists() throws Exception {
        ObjectMapper om = new ObjectMapper();
        Map<String, String> ev = new LinkedHashMap<String, String>();
        ev.put("pass",
                "{\"goal_assist\":true,\"recipient\":{\"id\":637,\"name\":\"Suárez\"}}");
        String json = "["
                // unrelated player's shot - ignored
                + "{\"type\":{\"name\":\"Shot\"},\"player\":{\"id\":637},"
                + "\"shot\":{\"outcome\":{\"name\":\"Saved\"},\"statsbomb_xg\":0.31}},"
                // Messi shot, goal, open play
                + "{\"type\":{\"name\":\"Shot\"},\"player\":{\"id\":5503},"
                + "\"shot\":{\"outcome\":{\"name\":\"Goal\"},\"type\":{\"name\":\"Open Play\"},"
                + "\"statsbomb_xg\":0.24}},"
                // Messi shot, penalty goal
                + "{\"type\":{\"name\":\"Shot\"},\"player\":{\"id\":5503},"
                + "\"shot\":{\"outcome\":{\"name\":\"Goal\"},\"type\":{\"name\":\"Penalty\"},"
                + "\"statsbomb_xg\":0.76}},"
                // Messi shot, off target
                + "{\"type\":{\"name\":\"Shot\"},\"player\":{\"id\":5503},"
                + "\"shot\":{\"outcome\":{\"name\":\"Off T\"},\"statsbomb_xg\":0.09}},"
                // Messi assist pass
                + "{\"type\":{\"name\":\"Pass\"},\"player\":{\"id\":5503},"
                + "\"pass\":{\"goal_assist\":true}},"
                // Messi pass without assist
                + "{\"type\":{\"name\":\"Pass\"},\"player\":{\"id\":5503},"
                + "\"pass\":{\"length\":12.0}}"
                + "]";
        JsonNode root = om.readTree(json);
        double[] v = MessiEventsMapper.extractStats(root, 5503L);
        assertEquals(1.0, v[MessiEventsMapper.OFF_APPS], 0.0);
        assertEquals(3.0, v[MessiEventsMapper.OFF_SHOTS], 0.0);
        assertEquals(2.0, v[MessiEventsMapper.OFF_GOALS], 0.0);
        assertEquals(1.09, v[MessiEventsMapper.OFF_XG], 1e-9);
        assertEquals(1.0, v[MessiEventsMapper.OFF_ASSISTS], 0.0);
        assertEquals(1.0, v[MessiEventsMapper.OFF_PEN_GOALS], 0.0);
    }

    @Test
    public void absentPlayerYieldsZeroApps() throws Exception {
        ObjectMapper om = new ObjectMapper();
        JsonNode root = om.readTree(
                "[{\"type\":{\"name\":\"Shot\"},\"player\":{\"id\":637},"
                        + "\"shot\":{\"outcome\":{\"name\":\"Goal\"}}}]");
        double[] v = MessiEventsMapper.extractStats(root, 5503L);
        assertEquals(0.0, v[MessiEventsMapper.OFF_APPS], 0.0);
        assertTrue(v[MessiEventsMapper.OFF_SHOTS] == 0.0);
    }
}
