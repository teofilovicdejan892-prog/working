import nacl from "tweetnacl";
import * as naclUtil from "tweetnacl-util";

export type KeyPair = {
  privateKey: Uint8Array;
  publicKey: Uint8Array;
  privateKeyBase64: string;
  publicKeyBase64: string;
};

/**
 * Generate a new Ed25519 key pair
 */
export function generateKeyPair(): KeyPair {
  const keyPair = nacl.sign.keyPair();

  return {
    privateKey: keyPair.secretKey, // 64 bytes (private + public)
    publicKey: keyPair.publicKey, // 32 bytes
    privateKeyBase64: naclUtil.encodeBase64(keyPair.secretKey),
    publicKeyBase64: naclUtil.encodeBase64(keyPair.publicKey),
  };
}

/**
 * Sign a message using Ed25519 private key
 */
export function signMessage(message: string, privateKey: Uint8Array): string {
  const msgUint8 = naclUtil.decodeUTF8(message);
  const signature = nacl.sign.detached(msgUint8, privateKey);
  return naclUtil.encodeBase64(signature);
}

/**
 * Verify a signature using Ed25519 public key
 */
export function verifySignature(
  message: string,
  signatureBase64: string,
  publicKey: Uint8Array
): boolean {
  const msgUint8 = naclUtil.decodeUTF8(message);
  const signature = naclUtil.decodeBase64(signatureBase64);
  return nacl.sign.detached.verify(msgUint8, signature, publicKey);
}
