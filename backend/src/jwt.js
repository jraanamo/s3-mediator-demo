import jwt from 'jsonwebtoken';
import { config } from './config.js';

export function issueToken(deviceId) {
  const token = jwt.sign({ deviceId }, config.jwtSecret, { expiresIn: config.jwtExpirySeconds });
  const expiresAt = new Date(Date.now() + config.jwtExpirySeconds * 1000).toISOString();
  return { token, expiresAt };
}

export function verifyToken(token) {
  return jwt.verify(token, config.jwtSecret);
}
