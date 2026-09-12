package com.restolution.arch26demo.client;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.UUID;

/**
 * Loads client.properties, generating and persisting a client.id when blank
 * (see docs/decision/client-identity.md) instead of requiring one to be
 * pre-assigned.
 */
public final class ClientProperties {
    private static final String DEFAULT_RESOURCE = "/client.properties.default";

    private final Properties properties;
    private final Path file;

    private ClientProperties(Properties properties, Path file) {
        this.properties = properties;
        this.file = file;
    }

    public static ClientProperties loadOrCreate(Path file) throws IOException {
        Properties properties = new Properties();
        if (Files.exists(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                properties.load(in);
            }
        } else {
            try (InputStream defaults = ClientProperties.class.getResourceAsStream(DEFAULT_RESOURCE)) {
                properties.load(defaults);
            }
        }

        ClientProperties result = new ClientProperties(properties, file);
        result.ensureClientId();
        result.save();
        return result;
    }

    /** Exposed for self-checks: applies the ensure-id logic to an already-loaded Properties without touching disk. */
    public static String ensureClientId(Properties properties) {
        String id = properties.getProperty("client.id", "").trim();
        if (id.isEmpty()) {
            id = UUID.randomUUID().toString();
            properties.setProperty("client.id", id);
        }
        return id;
    }

    private void ensureClientId() {
        ensureClientId(properties);
    }

    private void save() throws IOException {
        try (FileOutputStream out = new FileOutputStream(file.toFile())) {
            properties.store(out, "arch26-demo POS client configuration");
        }
    }

    public String get(String key, String defaultValue) {
        return properties.getProperty(key, defaultValue);
    }

    public int getInt(String key, int defaultValue) {
        return Integer.parseInt(properties.getProperty(key, String.valueOf(defaultValue)));
    }

    public String clientId() {
        return properties.getProperty("client.id");
    }
}
