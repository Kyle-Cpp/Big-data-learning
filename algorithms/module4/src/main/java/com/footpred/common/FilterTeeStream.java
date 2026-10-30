package com.footpred.common;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Byte-level tee that forwards everything to the console stream and copies
 * only the lines starting with a configured prefix (default "[footpred]") to
 * a secondary stream (an HDFS file in production). Pure IO - unit-testable
 * without any Hadoop dependency.
 *
 * Behaviour details:
 *  - lines are recognised on '\n'; '\r' is ignored so CRLF input is clean
 *  - a partial trailing line is not forwarded (it has no terminator yet)
 *  - if the secondary stream starts failing, it is demoted ("broken") and
 *    the console keeps working - logging must never take the job down
 */
public class FilterTeeStream extends OutputStream {

    public static final String DEFAULT_PREFIX = "[footpred]";

    private final OutputStream console;
    private final OutputStream file;
    private final String prefix;
    private final ByteArrayOutputStream lineBuffer = new ByteArrayOutputStream(128);
    private volatile boolean fileBroken;

    public FilterTeeStream(OutputStream console, OutputStream file) {
        this(console, file, DEFAULT_PREFIX);
    }

    public FilterTeeStream(OutputStream console, OutputStream file, String prefix) {
        this.console = console;
        this.file = file;
        this.prefix = prefix;
    }

    @Override
    public synchronized void write(int b) throws IOException {
        console.write(b);
        if (fileBroken) {
            return;
        }
        if (b == '\n') {
            String line = lineBuffer.toString();
            lineBuffer.reset();
            if (line.startsWith(prefix)) {
                try {
                    file.write((line + "\n").getBytes("UTF-8"));
                } catch (IOException e) {
                    demote(e);
                }
            }
        } else if (b != '\r') {
            if (lineBuffer.size() < 4096) {
                lineBuffer.write(b);
            }
        }
    }

    @Override
    public synchronized void flush() throws IOException {
        console.flush();
        if (!fileBroken) {
            try {
                file.flush();
            } catch (IOException e) {
                demote(e);
            }
        }
    }

    @Override
    public synchronized void close() throws IOException {
        flush();
        try {
            if (!fileBroken) {
                file.close();
            }
        } catch (IOException e) {
            demote(e);
        }
    }

    public boolean isFileBroken() {
        return fileBroken;
    }

    private void demote(IOException cause) {
        fileBroken = true;
        try {
            console.write(("[footpred] WARNING: log file stream failed, "
                    + "continuing without it: " + cause.getMessage() + "\n")
                    .getBytes("UTF-8"));
            console.flush();
        } catch (IOException ignored) {
            // console is broken as well - nothing left to do
        }
    }
}