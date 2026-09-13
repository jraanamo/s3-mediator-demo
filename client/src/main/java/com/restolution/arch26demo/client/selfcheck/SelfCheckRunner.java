package com.restolution.arch26demo.client.selfcheck;

import java.util.List;

/** Framework-free self-checks (see docs/spec/client.md "Testing"). Run via `./gradlew selfCheck`. */
public final class SelfCheckRunner {
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private record NamedCheck(String name, ThrowingRunnable run) {
    }

    public static void main(String[] args) {
        List<NamedCheck> checks = List.of(
                new NamedCheck("ClientProperties: generates and persists client.id when blank", ClientPropertiesChecks::generatesIdWhenBlank),
                new NamedCheck("ClientProperties: reuses an existing client.id", ClientPropertiesChecks::reusesExistingId),
                new NamedCheck("AuthClient: reuses token until near expiry", AuthClientChecks::reusesTokenUntilExpiry),
                new NamedCheck("AuthClient: invalidate() forces a fresh re-login", AuthClientChecks::invalidateTriggersRelogin),
                new NamedCheck("AuthClient: exposes upload credentials from login response", AuthClientChecks::exposesUploadCredentials),
                new NamedCheck("CatalogSyncService: 304 is a no-op, 200 replaces state", CatalogSyncServiceChecks::conditionalGetReplacesOnlyOnChange),
                new NamedCheck("ReceiptUploader: happy path empties the queue", ReceiptUploaderChecks::happyPathEmptiesQueue),
                new NamedCheck("ReceiptUploader: retries with backoff then drops after max attempts", ReceiptUploaderChecks::retryBackoffThenDropAfterMaxAttempts),
                new NamedCheck("MonitorReporter: a reporting failure never propagates to the caller", MonitorReporterChecks::reportNeverPropagatesFailure)
        );

        int failures = 0;
        for (NamedCheck check : checks) {
            try {
                check.run().run();
                System.out.println("PASS - " + check.name());
            } catch (Throwable t) {
                failures++;
                System.out.println("FAIL - " + check.name() + ": " + t.getMessage());
            }
        }

        System.out.println((checks.size() - failures) + "/" + checks.size() + " passed");
        if (failures > 0) {
            System.exit(1);
        }
    }
}
