package com.footpred.backend.job;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ClusterGatewayTest {
    @Test void sshQuotesEachArgumentAndRejectsPathTraversal() {
        ClusterGateway g=new ClusterGateway();
        ReflectionTestUtils.setField(g,"mode","ssh"); ReflectionTestUtils.setField(g,"ssh","ssh");
        ReflectionTestUtils.setField(g,"host","192.168.85.10");ReflectionTestUtils.setField(g,"user","root");
        ReflectionTestUtils.setField(g,"port",22);ReflectionTestUtils.setField(g,"identity","");
        ReflectionTestUtils.setField(g,"root","/footpred/web-uploads");
        List<String> cmd=g.command(Arrays.asList("hadoop","jar","/job/app.jar","x; touch /tmp/unwanted", "a'b"));
        assertTrue(cmd.contains("BatchMode=yes"));assertTrue(cmd.contains("StrictHostKeyChecking=yes"));
        assertEquals("bash -lc "+ClusterGateway.quote("'hadoop' 'jar' '/job/app.jar' 'x; touch /tmp/unwanted' "+ClusterGateway.quote("a'b")),cmd.get(cmd.size()-1));
        String id="12345678-1234-1234-1234-123456789abc";
        assertEquals("/footpred/web-uploads/"+id+"/league/season.csv",g.uploadPath(id,"league/season.csv"));
        for(String path:Arrays.asList("../data.csv","/data.csv","a/../../data.csv","a\\b.csv","a/$(id).csv"))
            assertThrows(IllegalArgumentException.class,()->g.uploadPath(id,path));
    }
}
