import {
  S3Client,
  GetObjectCommand,
  PutObjectCommand,
  HeadObjectCommand,
  CopyObjectCommand,
  DeleteObjectCommand,
  DeleteObjectsCommand,
  ListObjectsV2Command,
} from '@aws-sdk/client-s3';
import { getSignedUrl } from '@aws-sdk/s3-request-presigner';
import { STSClient, AssumeRoleCommand } from '@aws-sdk/client-sts';
import { config } from './config.js';

async function streamToString(stream) {
  const chunks = [];
  for await (const chunk of stream) chunks.push(chunk);
  return Buffer.concat(chunks).toString('utf-8');
}

// Thin wrapper around the S3-compatible Mediator bucket. Kept as a small
// interface (get/put/exists/copy/delete/list/presign) so CatalogPublisher and
// InboxProcessor can be exercised in tests against an in-memory fake instead.
export function createS3Store() {
  const client = new S3Client({
    endpoint: config.s3.endpoint,
    region: config.s3.region,
    forcePathStyle: true,
    credentials: {
      accessKeyId: config.s3.accessKeyId,
      secretAccessKey: config.s3.secretAccessKey,
    },
  });
  const stsClient = new STSClient({
    endpoint: config.s3.stsEndpoint,
    region: config.s3.region,
    credentials: {
      accessKeyId: config.s3.accessKeyId,
      secretAccessKey: config.s3.secretAccessKey,
    },
  });
  const bucket = config.s3.bucket;

  return {
    async exists(key) {
      try {
        await client.send(new HeadObjectCommand({ Bucket: bucket, Key: key }));
        return true;
      } catch (err) {
        if (err.name === 'NotFound' || err.$metadata?.httpStatusCode === 404) return false;
        throw err;
      }
    },

    async putJson(key, value) {
      await client.send(new PutObjectCommand({
        Bucket: bucket,
        Key: key,
        Body: JSON.stringify(value),
        ContentType: 'application/json',
      }));
    },

    async getJson(key) {
      const result = await client.send(new GetObjectCommand({ Bucket: bucket, Key: key }));
      return JSON.parse(await streamToString(result.Body));
    },

    async copy(sourceKey, destKey) {
      await client.send(new CopyObjectCommand({
        Bucket: bucket,
        CopySource: `${bucket}/${sourceKey}`,
        Key: destKey,
      }));
    },

    async delete(key) {
      await client.send(new DeleteObjectCommand({ Bucket: bucket, Key: key }));
    },

    // Batched delete (S3 DeleteObjects, up to 1000 keys per call) instead of
    // one DeleteObject per key — the real win for InboxProcessor at scale,
    // since GET/COPY have no bulk equivalent but DELETE does. UpCloud rejects
    // this request without an explicit Content-MD5, which the SDK only sends
    // when ChecksumAlgorithm is set (confirmed by live testing against the
    // real bucket — the SDK's default checksum handling for this command
    // silently omits it and UpCloud 400s with "Missing required header").
    // Returns per-key errors (not thrown) so the caller can leave failed
    // deletes for the next poll, same as a single failed DeleteObject today.
    async deleteMany(keys) {
      const errors = [];
      for (let i = 0; i < keys.length; i += 1000) {
        const chunk = keys.slice(i, i + 1000);
        const result = await client.send(new DeleteObjectsCommand({
          Bucket: bucket,
          Delete: { Objects: chunk.map((Key) => ({ Key })) },
          ChecksumAlgorithm: 'MD5',
        }));
        for (const err of result.Errors ?? []) {
          errors.push({ key: err.Key, message: err.Message });
        }
      }
      return errors;
    },

    async listPage(prefix, continuationToken, maxKeys) {
      const result = await client.send(new ListObjectsV2Command({
        Bucket: bucket,
        Prefix: prefix,
        ContinuationToken: continuationToken,
        MaxKeys: maxKeys,
      }));
      return {
        keys: (result.Contents ?? []).map((obj) => obj.Key),
        nextToken: result.IsTruncated ? result.NextContinuationToken : undefined,
      };
    },

    async presignGet(key, expiresInSeconds) {
      return getSignedUrl(client, new GetObjectCommand({ Bucket: bucket, Key: key }), { expiresIn: expiresInSeconds });
    },

    // Temporary, auto-expiring credentials scoped (via an inline session
    // policy) to s3:PutObject under `prefix` only. Lets the client sign and
    // PUT directly to the Mediator itself for the life of the credential, no
    // per-upload Backend contact. See docs/decision/client-mediator-direct-access.md.
    //
    // Requires a pre-created IAM role (S3_UPLOAD_ROLE_ARN) whose own policy
    // is at least as broad as `prefix` (the session policy can only narrow,
    // never widen, the role's own permissions) and whose trust policy allows
    // this Backend's IAM user to assume it. sessionName must be 2-64 chars
    // per UpCloud's STS validation, hence the "device-" prefix.
    async assumeUploadRole(prefix, durationSeconds, sessionName) {
      const sessionPolicy = {
        Version: '2012-10-17',
        Statement: [
          { Effect: 'Allow', Action: 's3:PutObject', Resource: `arn:aws:s3:::${bucket}/${prefix}*` },
        ],
      };
      const result = await stsClient.send(new AssumeRoleCommand({
        RoleArn: config.s3.uploadRoleArn,
        RoleSessionName: `device-${sessionName}`,
        DurationSeconds: durationSeconds,
        Policy: JSON.stringify(sessionPolicy),
      }));
      return {
        accessKeyId: result.Credentials.AccessKeyId,
        secretAccessKey: result.Credentials.SecretAccessKey,
        sessionToken: result.Credentials.SessionToken,
        expiration: result.Credentials.Expiration.toISOString(),
      };
    },
  };
}
