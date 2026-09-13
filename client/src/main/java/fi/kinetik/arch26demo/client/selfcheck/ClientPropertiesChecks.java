package fi.kinetik.arch26demo.client.selfcheck;

import fi.kinetik.arch26demo.client.ClientProperties;

import java.util.Properties;

public final class ClientPropertiesChecks {
    private ClientPropertiesChecks() {
    }

    public static void generatesIdWhenBlank() {
        Properties properties = new Properties();
        properties.setProperty("client.id", "");

        String id = ClientProperties.ensureClientId(properties);

        Check.that(id != null && !id.isBlank(), "a non-blank id should be generated");
        Check.equal(id, properties.getProperty("client.id"), "generated id should be written back into the properties");
    }

    public static void reusesExistingId() {
        Properties properties = new Properties();
        properties.setProperty("client.id", "existing-id-123");

        String id = ClientProperties.ensureClientId(properties);

        Check.equal("existing-id-123", id, "an already-set client.id should be reused, not regenerated");
    }
}
