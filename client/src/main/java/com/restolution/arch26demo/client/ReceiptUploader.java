package com.restolution.arch26demo.client;

import com.restolution.arch26demo.client.model.Receipt;
import com.restolution.arch26demo.client.s3.ReceiptSink;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Uploads queued receipts directly to the Mediator using the prefix-scoped
 * STS credentials handed out by /login, retrying failures with exponential
 * backoff and dropping a receipt once retry.max-attempts is exceeded. No
 * per-upload Backend contact — see docs/decision/client-mediator-direct-access.md.
 */
public class ReceiptUploader {
    private static final Logger LOG = LogManager.getLogger(ReceiptUploader.class);

    private record QueuedReceipt(Receipt receipt, int attempts, Instant nextAttemptAt) {
    }

    private final AuthClient authClient;
    private final ReceiptSink sink;
    private final MonitorReporter reporter;
    private final int batchSize;
    private final long baseDelaySeconds;
    private final long maxDelaySeconds;
    private final int maxAttempts;

    private final Deque<QueuedReceipt> queue = new ArrayDeque<>();

    public ReceiptUploader(AuthClient authClient, ReceiptSink sink, MonitorReporter reporter,
                            int batchSize, long baseDelaySeconds, long maxDelaySeconds, int maxAttempts) {
        this.authClient = authClient;
        this.sink = sink;
        this.reporter = reporter;
        this.batchSize = batchSize;
        this.baseDelaySeconds = baseDelaySeconds;
        this.maxDelaySeconds = maxDelaySeconds;
        this.maxAttempts = maxAttempts;
    }

    public synchronized void enqueue(Receipt receipt) {
        queue.addLast(new QueuedReceipt(receipt, 0, Instant.EPOCH));
    }

    public synchronized int queueSize() {
        return queue.size();
    }

    public void uploadBatch() {
        List<QueuedReceipt> batch = drainReady();
        if (batch.isEmpty()) {
            return;
        }

        UploadCredentials upload;
        try {
            upload = authClient.currentUpload();
        } catch (IOException e) {
            LOG.error("could not obtain upload credentials", e);
            batch.forEach(q -> retryOrDrop(q, null));
            return;
        }

        for (QueuedReceipt queued : batch) {
            uploadOne(queued, upload);
        }
    }

    private void uploadOne(QueuedReceipt queued, UploadCredentials upload) {
        String key = upload.keyPrefix() + queued.receipt().receiptId() + ".json";
        long startedAt = System.nanoTime();
        try {
            sink.put(upload, key, queued.receipt().toJson());
            long durationMs = (System.nanoTime() - startedAt) / 1_000_000;
            LOG.info("uploaded receipt {}", queued.receipt().receiptId());
            reporter.report("receipt-uploaded", "ok", queued.receipt().receiptId(), null, durationMs);
        } catch (IOException e) {
            long durationMs = (System.nanoTime() - startedAt) / 1_000_000;
            LOG.error("upload failed for receipt " + queued.receipt().receiptId(), e);
            retryOrDrop(queued, durationMs);
        }
    }

    private void retryOrDrop(QueuedReceipt queued, Long durationMs) {
        reporter.report("receipt-uploaded", "error", queued.receipt().receiptId(), null, durationMs);
        int attempts = queued.attempts() + 1;
        if (attempts > maxAttempts) {
            LOG.error("dropping receipt {} after {} attempts", queued.receipt().receiptId(), attempts - 1);
            return;
        }
        long delaySeconds = Math.min(baseDelaySeconds * (1L << (attempts - 1)), maxDelaySeconds);
        Instant nextAttemptAt = Instant.now().plusSeconds(delaySeconds);
        synchronized (this) {
            queue.addLast(new QueuedReceipt(queued.receipt(), attempts, nextAttemptAt));
        }
        LOG.warn("requeuing receipt {} (attempt {}), next try in {}s", queued.receipt().receiptId(), attempts, delaySeconds);
    }

    private synchronized List<QueuedReceipt> drainReady() {
        Instant now = Instant.now();
        List<QueuedReceipt> ready = new ArrayList<>();
        List<QueuedReceipt> notReady = new ArrayList<>();
        while (!queue.isEmpty() && ready.size() < batchSize) {
            QueuedReceipt candidate = queue.pollFirst();
            if (!candidate.nextAttemptAt().isAfter(now)) {
                ready.add(candidate);
            } else {
                notReady.add(candidate);
            }
        }
        notReady.forEach(queue::addLast);
        return ready;
    }
}
