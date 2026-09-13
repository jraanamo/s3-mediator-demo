package fi.kinetik.arch26demo.client;

import fi.kinetik.arch26demo.client.http.HttpTransport;
import org.json.JSONObject;

/**
 * Fire-and-forget reporting to POST /monitor/events for activity the Backend
 * cannot observe itself (direct client-Mediator calls). Never blocks or
 * propagates failure to the caller — see docs/spec/monitor.md "Client Reporting".
 */
public class MonitorReporter {
    private final AuthClient authClient;
    private final HttpTransport http;
    private final String backendUrl;

    public MonitorReporter(AuthClient authClient, HttpTransport http, String backendUrl) {
        this.authClient = authClient;
        this.http = http;
        this.backendUrl = backendUrl;
    }

    public void report(String type, String outcome, String receiptId, String detail) {
        report(type, outcome, receiptId, detail, null);
    }

    /** durationMs is the elapsed time of the network call this event represents, or null if not measured. */
    public void report(String type, String outcome, String receiptId, String detail, Long durationMs) {
        try {
            String token = authClient.currentToken();
            JSONObject body = new JSONObject()
                    .put("type", type)
                    .put("clientId", authClient.clientId())
                    .put("outcome", outcome);
            if (receiptId != null) {
                body.put("receiptId", receiptId);
            }
            if (detail != null) {
                body.put("detail", detail);
            }
            if (durationMs != null) {
                body.put("durationMs", durationMs);
            }
            http.postJson(backendUrl + "/monitor/events", body.toString(), token);
        } catch (Exception e) {
            // Best-effort instrumentation only: swallow everything, never surface to the caller.
        }
    }
}
