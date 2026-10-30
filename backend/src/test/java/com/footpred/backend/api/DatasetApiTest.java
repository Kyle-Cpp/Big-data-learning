package com.footpred.backend.api;
import com.footpred.backend.job.ClusterGateway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import java.io.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"footpred.upload.enabled=true","footpred.job.enabled=false","footpred.mysql.enabled=false"})
@AutoConfigureMockMvc
class DatasetApiTest {
    @Autowired MockMvc mvc;
    @MockBean ClusterGateway cluster;
    @Test void uploadIsAcknowledgedOnlyAfterClusterPutAndFailureIsNotSuccess() throws Exception {
        String id="12345678-1234-1234-1234-123456789abc";
        when(cluster.uploadPath(id,"results.csv")).thenReturn("/uploads/"+id+"/results.csv");
        when(cluster.datasetRoot(id)).thenReturn("/uploads/"+id);
        MockMultipartFile file=new MockMultipartFile("file","results.csv","text/csv","date,home,away\n".getBytes("UTF-8"));
        mvc.perform(multipart("/api/datasets").file(file).param("datasetId",id).param("relativePath","results.csv"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.uploaded").value(true))
                .andExpect(jsonPath("$.path").value("/uploads/"+id+"/results.csv"));
        verify(cluster).put(eq(id),eq("results.csv"),any(InputStream.class));
        doThrow(new IOException("cluster offline")).when(cluster).put(eq(id),eq("results.csv"),any(InputStream.class));
        mvc.perform(multipart("/api/datasets").file(file).param("datasetId",id).param("relativePath","results.csv"))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.uploaded").value(false));
        mvc.perform(get("/api/datasets/capabilities")).andExpect(status().isOk())
                .andExpect(jsonPath("$.jobEnabled").value(false)).andExpect(jsonPath("$.trainingEnabled").value(false));
    }
}
