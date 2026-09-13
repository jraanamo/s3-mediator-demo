package fi.kinetik.arch26demo.client.selfcheck;

import fi.kinetik.arch26demo.client.UploadCredentials;
import fi.kinetik.arch26demo.client.s3.ReceiptSink;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;

/** Scripted ReceiptSink for self-checks: each call pops the next queued outcome. */
public class FakeReceiptSink implements ReceiptSink {
    private final Deque<Optional<IOException>> outcomes = new ArrayDeque<>();
    public int putCalls = 0;

    /** Queue a successful put. */
    public FakeReceiptSink queueOk() {
        outcomes.addLast(Optional.empty());
        return this;
    }

    /** Queue a failing put. */
    public FakeReceiptSink queueFailure() {
        outcomes.addLast(Optional.of(new IOException("simulated failure")));
        return this;
    }

    @Override
    public void put(UploadCredentials credentials, String key, String jsonBody) throws IOException {
        putCalls++;
        Optional<IOException> outcome = outcomes.pollFirst();
        if (outcome == null) {
            throw new IllegalStateException("no more scripted outcomes for put");
        }
        if (outcome.isPresent()) {
            throw outcome.get();
        }
    }
}
