package com.restolution.arch26demo.client;

import com.restolution.arch26demo.client.http.JdkHttpTransport;
import com.restolution.arch26demo.client.model.Receipt;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class Main {
    private static final Logger LOG = LogManager.getLogger(Main.class);

    public static void main(String[] args) throws Exception {
        Path propertiesFile = args.length > 0 ? Path.of(args[0]) : Path.of("client.properties");
        ClientProperties props = ClientProperties.loadOrCreate(propertiesFile);
        String clientId = props.clientId();
        LOG.info("starting POS client simulator, client.id={}", clientId);

        // Strip any trailing slash(es) so backendUrl + "/login" etc. never produces a
        // double slash (which 404s — Fastify treats "//login" as a different route).
        String backendUrl = props.get("backend.url", "http://localhost:3000").replaceAll("/+$", "");
        JdkHttpTransport http = new JdkHttpTransport();
        AuthClient authClient = new AuthClient(backendUrl, clientId, http);
        MonitorReporter reporter = new MonitorReporter(authClient, http, backendUrl);
        CatalogSyncService catalogSync = new CatalogSyncService(authClient, http, reporter);
        ReceiptSimulator simulator = new ReceiptSimulator(catalogSync, clientId);
        ReceiptUploader uploader = new ReceiptUploader(
                authClient, http, reporter, backendUrl,
                props.getInt("upload.batch-size", 10),
                props.getInt("retry.base-delay-seconds", 2),
                props.getInt("retry.max-delay-seconds", 60),
                props.getInt("retry.max-attempts", 5));

        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);

        scheduler.scheduleWithFixedDelay(() -> {
            try {
                authClient.currentToken();
            } catch (Exception e) {
                LOG.error("proactive login check failed", e);
            }
        }, 0, props.getInt("backend.login-check-interval-seconds", 60), TimeUnit.SECONDS);

        scheduler.scheduleWithFixedDelay(catalogSync::poll, 0,
                props.getInt("catalog.poll-interval-seconds", 60), TimeUnit.SECONDS);

        scheduler.scheduleWithFixedDelay(() -> {
            Receipt receipt = simulator.simulate();
            if (receipt != null) {
                uploader.enqueue(receipt);
            }
        }, props.getInt("simulate.interval-seconds", 10),
                props.getInt("simulate.interval-seconds", 10), TimeUnit.SECONDS);

        scheduler.scheduleWithFixedDelay(uploader::uploadBatch,
                props.getInt("upload.interval-seconds", 20),
                props.getInt("upload.interval-seconds", 20), TimeUnit.SECONDS);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOG.info("shutting down");
            scheduler.shutdownNow();
        }));
    }
}
