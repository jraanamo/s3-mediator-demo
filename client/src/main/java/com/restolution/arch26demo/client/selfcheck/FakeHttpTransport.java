package com.restolution.arch26demo.client.selfcheck;

import com.restolution.arch26demo.client.http.HttpResult;
import com.restolution.arch26demo.client.http.HttpTransport;

import java.util.ArrayDeque;
import java.util.Deque;

/** Scripted HttpTransport for self-checks: each call pops the next queued response. */
public class FakeHttpTransport implements HttpTransport {
    private final Deque<HttpResult> postResponses = new ArrayDeque<>();
    private final Deque<HttpResult> getResponses = new ArrayDeque<>();
    private final Deque<HttpResult> putResponses = new ArrayDeque<>();

    public int postCalls = 0;
    public int getCalls = 0;
    public int putCalls = 0;

    public FakeHttpTransport queuePost(HttpResult result) {
        postResponses.addLast(result);
        return this;
    }

    public FakeHttpTransport queueGet(HttpResult result) {
        getResponses.addLast(result);
        return this;
    }

    public FakeHttpTransport queuePut(HttpResult result) {
        putResponses.addLast(result);
        return this;
    }

    public int monitorEventCalls = 0;

    @Override
    public HttpResult postJson(String url, String jsonBody, String bearerToken) {
        if (url.endsWith("/monitor/events")) {
            // Monitor reporting is a separate concern from the login flow most
            // checks script — don't make every check queue responses for it too.
            monitorEventCalls++;
            return new HttpResult(204, "", null);
        }
        postCalls++;
        return pop(postResponses, "postJson");
    }

    @Override
    public HttpResult getConditional(String url, String ifNoneMatchEtag) {
        getCalls++;
        return pop(getResponses, "getConditional");
    }

    @Override
    public HttpResult putJson(String url, String jsonBody) {
        putCalls++;
        return pop(putResponses, "putJson");
    }

    private HttpResult pop(Deque<HttpResult> queue, String method) {
        HttpResult result = queue.pollFirst();
        if (result == null) {
            throw new IllegalStateException("no more scripted responses for " + method);
        }
        return result;
    }
}
