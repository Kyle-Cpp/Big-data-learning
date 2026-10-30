package com.footpred.common;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

/** HDFS log tee semantics: prefix filtering, CRLF, partial lines, degradation. */
public class FilterTeeStreamTest {

    private static void feed(OutputStream stream, String text) throws IOException {
        stream.write(text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void onlyPrefixedLinesReachTheFileStream() throws IOException {
        ByteArrayOutputStream console = new ByteArrayOutputStream();
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        FilterTeeStream tee = new FilterTeeStream(console, file);

        feed(tee, "[footpred] train start\n");
        feed(tee, "2026-10-12 INFO mapreduce.Job: map 0%\n");
        feed(tee, "[footpred] iter=0 NLL=8028.6446\n");
        feed(tee, "2026-10-12 WARN some hadoop noise\n");
        feed(tee, "[footpred] converged\n");
        tee.close();

        String consoleText = console.toString("UTF-8");
        assertTrue("console sees everything", consoleText.contains("mapreduce.Job"));
        assertTrue("console sees our lines", consoleText.contains("[footpred] iter=0"));

        String fileText = file.toString("UTF-8");
        assertEquals("[footpred] train start\n"
                + "[footpred] iter=0 NLL=8028.6446\n"
                + "[footpred] converged\n", fileText);
        assertFalse("hadoop INFO lines are not mirrored",
                fileText.contains("mapreduce"));
    }

    @Test
    public void crlfInputProducesCleanLines() throws IOException {
        ByteArrayOutputStream console = new ByteArrayOutputStream();
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        FilterTeeStream tee = new FilterTeeStream(console, file);

        feed(tee, "[footpred] try=1 accept\r\n[footpred] try=2 reject\r\n");
        tee.close();

        assertEquals("[footpred] try=1 accept\n[footpred] try=2 reject\n",
                file.toString("UTF-8"));
    }

    @Test
    public void partialTrailingLineIsNotForwarded() throws IOException {
        ByteArrayOutputStream console = new ByteArrayOutputStream();
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        FilterTeeStream tee = new FilterTeeStream(console, file);

        feed(tee, "[footpred] complete\n[footpred] truncated-without-newline");
        tee.close();

        assertEquals("[footpred] complete\n", file.toString("UTF-8"));
        assertTrue("console still got the partial text",
                console.toString("UTF-8").endsWith("truncated-without-newline"));
    }

    @Test
    public void nonPrefixedNoiseAroundPrefixedLines() throws IOException {
        ByteArrayOutputStream console = new ByteArrayOutputStream();
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        FilterTeeStream tee = new FilterTeeStream(console, file, "[mine]");

        feed(tee, "[mine] a\n[other] b\nprefixed but not ours: [mine] c\n[mine] d\n");
        tee.close();

        assertEquals("[mine] a\n[mine] d\n", file.toString("UTF-8"));
    }

    @Test
    public void brokenFileStreamDemotesAndConsoleSurvives() throws IOException {
        ByteArrayOutputStream console = new ByteArrayOutputStream();
        OutputStream broken = new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                throw new IOException("simulated HDFS failure");
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                throw new IOException("simulated HDFS failure");
            }
        };
        FilterTeeStream tee = new FilterTeeStream(console, broken);

        feed(tee, "[footpred] first\n");
        feed(tee, "[footpred] second\n");
        feed(tee, "[footpred] third\n");
        tee.close();

        assertTrue("file stream marked broken", tee.isFileBroken());
        String consoleText = console.toString("UTF-8");
        assertTrue("warning emitted", consoleText.contains("log file stream failed"));
        assertTrue("console still received later lines", consoleText.contains("third"));
    }

    @Test
    public void emptyInputWritesNothing() throws IOException {
        ByteArrayOutputStream console = new ByteArrayOutputStream();
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        FilterTeeStream tee = new FilterTeeStream(console, file);
        tee.close();
        assertEquals("", file.toString("UTF-8"));
        assertEquals("", console.toString("UTF-8"));
    }
}