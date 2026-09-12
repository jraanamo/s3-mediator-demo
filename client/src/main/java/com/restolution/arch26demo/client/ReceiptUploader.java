package com.restolution.arch26demo.client;

import com.restolution.arch26demo.client.http.HttpResult;
import com.restolution.arch26demo.client.http.HttpTransport;
import com.restolution.arch26demo.client.model.Receipt;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Batches queued receipts through /upload-receipts and PUTs each body to its
 * presigned URL, retrying failures with exponential backoff and dropping a
 * receipt once retry.max-attempts is exceeded.
 */
public class ReceiptUploader {
    private static final Logger LOG = LogManager.getLogger(ReceiptUploader.class);

    private record QueuedReceipt(Receipt receipt, int attempts, Instant nextAttemptAt) {
    }

    private final AuthClient authClient;
    private final HttpTransport http;
    private final String backendUrl;
    private final int batchSize;
    private final long baseDelaySeconds;
    private final long maxDelaySeconds;
    private final int maxAttempts;

    private final Deque<QueuedReceipt> queue = new ArrayDeque<>();

    public ReceiptUploader(AuthClient authClient, HttpTransport http, String backendUrl,
                            int batchSize, long baseDelaySeconds, long maxDelaySeconds, int maxAttempts) {
        this.authClient = authClient;
        this.http = http;
        this.backendUrl = backendUrl;
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

        try {
            JSONArray requestArray = new JSONArray();
            batch.forEach(q -> requestArray.put(new JSONObject().put("receiptId", q.receipt().receiptId())));

            String token = authClient.currentToken();
            HttpResult result = http.postJson(backendUrl + "/upload-receipts", requestArray.toString(), token);
            if (result.status() == 401) {
                LOG.warn("upload-receipts got 401, forcing re-login and retrying once");
                authClient.invalidate();
                token = authClient.currentToken();
                result = http.postJson(backendUrl + "/upload-receipts", requestArray.toString(), token);
            }
            if (result.status() != 200) {
                LOG.error("upload-receipts failed: HTTP {}", result.status());
                batch.forEach(this::retryOrDrop);
                return;
            }

            Map<String, String> uploadUrls = new HashMap<>();
            JSONArray responses = new JSONArray(result.body());
            for (int i = 0; i < responses.length(); i++) {
                JSONObject entry = responses.getJSONObject(i);
                uploadUrls.put(entry.getString("receiptId"), entry.getString("uploadUrl"));
            }

            for (QueuedReceipt queued : batch) {
                uploadOne(queued, uploadUrls.get(queued.receipt().receiptId()));
            }
        } catch (IOException e) {
            LOG.error("upload batch failed", e);
            batch.forEach(this::retryOrDrop);
        }
    }

    private void uploadOne(QueuedReceipt queued, String uploadUrl) {
        if (uploadUrl == null) {
            LOG.error("no uploadUrl returned for receipt {}", queued.receipt().receiptId());
            retryOrDrop(queued);
            return;
        }
        try {
            HttpResult result = http.putJson(uploadUrl, queued.receipt().toJson());
            if (result.status() >= 200 && result.status() < 300) {
                LOG.info("uploaded receipt {}", queued.receipt().receiptId());
            } else {
                LOG.error("PUT failed for receipt {}: HTTP {}", queued.receipt().receiptId(), result.status());
                retryOrDrop(queued);
            }
        } catch (IOException e) {
            LOG.error("PUT failed for receipt " + queued.receipt().receiptId(), e);
            retryOrDrop(queued);
        }
    }

    private void retryOrDrop(QueuedReceipt queued) {
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
