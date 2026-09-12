import {
  S3Client,
  GetObjectCommand,
  PutObjectCommand,
  HeadObjectCommand,
  CopyObjectCommand,
  DeleteObjectCommand,
  ListObjectsV2Command,
} from '@aws-sdk/client-s3';
import { getSignedUrl } from '@aws-sdk/s3-request-presigner';
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

    async presignPut(key, expiresInSeconds) {
      return getSignedUrl(client, new PutObjectCommand({ Bucket: bucket, Key: key }), { expiresIn: expiresInSeconds });
    },
  };
}
