/**
 * Re-validates the local `localhost.crt` before anything is asked to trust it.
 *
 * This runs inside the pinned Playwright Linux image, twice and for two
 * different reasons. Once in a throwaway network-isolated container that also
 * has the private key, so the key-match can be proven without the key ever
 * being visible to a browser. Once more inside the browser container itself,
 * immediately before `certutil -A`, so the bytes being trusted are the bytes
 * that were just checked rather than the ones checked a step earlier.
 *
 * Nothing here prints certificate or key material: a failure names the rule
 * that was broken and exits non-zero, and the caller stops.
 *
 * Usage: node verify-localhost-certificate.mjs <cert.pem> [key.pem]
 */
import { readFileSync } from "node:fs";
import { createPrivateKey, X509Certificate } from "node:crypto";
import process from "node:process";

/** The exact extended key usage a TLS server leaf may claim, and nothing else. */
const SERVER_AUTH_OID = "1.3.6.1.5.5.7.3.1";
const MAX_LIFETIME_MS = 30 * 24 * 60 * 60 * 1000;
const MIN_RSA_MODULUS_BITS = 3072;

function reject(rule) {
  process.stderr.write(`localhost certificate rejected: ${rule}\n`);
  process.exit(1);
}

function mustHold(condition, rule) {
  if (!condition) {
    reject(rule);
  }
}

const [certPath, keyPath] = process.argv.slice(2);
mustHold(typeof certPath === "string" && certPath !== "", "no certificate path was given");

let certificatePem;
try {
  certificatePem = readFileSync(certPath, "utf8");
} catch {
  reject("the certificate file could not be read");
}

// A public certificate file that also carries a key is not the file this
// deployment generates, and it must never be handed to a browser container.
mustHold(!/PRIVATE KEY/u.test(certificatePem), "the certificate file contains a private key");

let certificate;
try {
  certificate = new X509Certificate(certificatePem);
} catch {
  reject("the certificate could not be parsed");
}

mustHold(certificate.ca === false, "the certificate claims to be a certificate authority");
mustHold(certificate.subject === certificate.issuer, "the certificate is not self-issued");
// The whole point of a trusted leaf: it vouches for itself, so its signature
// has to verify under its own public key.
mustHold(certificate.verify(certificate.publicKey), "the certificate is not validly self-signed");

mustHold(certificate.subjectAltName === "DNS:localhost", "the subject alternative name is not exactly DNS:localhost");

const extendedKeyUsage = certificate.keyUsage ?? [];
mustHold(
  extendedKeyUsage.length === 1 && extendedKeyUsage[0] === SERVER_AUTH_OID,
  "the extended key usage is not exactly serverAuth",
);

mustHold(certificate.publicKey.asymmetricKeyType === "rsa", "the public key is not RSA");
mustHold(
  (certificate.publicKey.asymmetricKeyDetails?.modulusLength ?? 0) >= MIN_RSA_MODULUS_BITS,
  "the RSA public key is too small",
);

const notBefore = Date.parse(certificate.validFrom);
const notAfter = Date.parse(certificate.validTo);
mustHold(Number.isFinite(notBefore) && Number.isFinite(notAfter), "the validity window could not be read");
const now = Date.now();
mustHold(notBefore <= now && now < notAfter, "the certificate is not currently valid");
mustHold(notAfter - notBefore <= MAX_LIFETIME_MS, "the certificate lifetime exceeds 30 days");

if (typeof keyPath === "string" && keyPath !== "") {
  let privateKey;
  try {
    privateKey = createPrivateKey(readFileSync(keyPath));
  } catch {
    reject("the private key could not be read");
  }
  mustHold(certificate.checkPrivateKey(privateKey), "the certificate does not match its private key");
}

process.stdout.write(
  `localhost certificate accepted${keyPath ? " with its private key" : ""}: self-signed serverAuth leaf for DNS:localhost\n`,
);
