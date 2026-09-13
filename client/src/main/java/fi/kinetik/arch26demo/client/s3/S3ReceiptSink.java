package fi.kinetik.arch26demo.client.s3;

import fi.kinetik.arch26demo.client.UploadCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.net.URI;

/**
 * Builds a fresh S3Client per call from the credentials handed out by /login
 * (they rotate every login, so there's nothing worth caching across calls).
 */
public class S3ReceiptSink implements ReceiptSink {
    @Override
    public void put(UploadCredentials credentials, String key, String jsonBody) throws IOException {
        try (S3Client s3 = S3Client.builder()
                .endpointOverride(URI.create(credentials.endpoint()))
                .region(Region.of(credentials.region()))
                .forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(AwsSessionCredentials.create(
                        credentials.accessKeyId(), credentials.secretAccessKey(), credentials.sessionToken())))
                .build()) {
            s3.putObject(PutObjectRequest.builder()
                    .bucket(credentials.bucket())
                    .key(key)
                    .contentType("application/json")
                    .build(), RequestBody.fromString(jsonBody));
        } catch (SdkException e) {
            throw new IOException(e);
        }
    }
}
