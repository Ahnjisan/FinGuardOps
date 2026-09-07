/**
 * Makes the host's loopback listeners answer on the container's own loopback,
 * under the same port numbers.
 *
 * The browser has to reach the application at exactly `http://localhost:5173`
 * and the Authorization Server at exactly `https://localhost:8443`: those
 * strings are the OIDC issuer, the allowlisted redirect URI and the allowlisted
 * post-logout URI, and a browser that reached them under any other name would
 * be testing a different contract.
 *
 * Those two are the whole list, and the list is in the code. There is no
 * argument, no environment variable and no configuration file that can extend
 * it, name a different host or move a relay to another port, so the reachable
 * surface is decided here and cannot be widened by whoever starts the process.
 * The Backend management listener on 8081, the Keycloak HTTP listener on 8082
 * and the Keycloak management listener on 9000 have no relay and no way to
 * acquire one.
 *
 * This is a byte-for-byte TCP relay and nothing more. It terminates no TLS,
 * reads no certificate and rewrites no bytes, so Chromium still performs the
 * full handshake and hostname check against `localhost` end to end.
 *
 * Usage: node loopback-forwarder.mjs
 */
import net from "node:net";
import process from "node:process";

const UPSTREAM_HOST = "host.docker.internal";

/**
 * The complete relay table. Each entry listens on the container's loopback and
 * connects to the same port on the host, because the port number is part of the
 * origin the browser must see.
 */
const RELAY_PORTS = Object.freeze([5173, 8443]);

// An argument could only ever ask for something this relay does not do, so the
// only safe answer is to refuse to start rather than to ignore it.
if (process.argv.length > 2) {
  process.stderr.write("loopback-forwarder: this relay takes no arguments\n");
  process.exit(1);
}

for (const port of RELAY_PORTS) {
  const server = net.createServer((client) => {
    const upstream = net.connect(port, UPSTREAM_HOST);
    // Either side going away tears down the pair. A relay that outlived its
    // peer would leave the browser waiting on a socket nobody will answer.
    const destroy = () => {
      client.destroy();
      upstream.destroy();
    };
    client.on("error", destroy);
    upstream.on("error", (error) => {
      // Named rather than swallowed: a refused upstream reaches the browser as
      // an empty response, which is indistinguishable from an application bug
      // unless the reason is written down here.
      process.stderr.write(`loopback-forwarder: ${UPSTREAM_HOST}:${port} ${error.message}
`);
      destroy();
    });
    client.on("close", destroy);
    upstream.on("close", destroy);
    client.pipe(upstream);
    upstream.pipe(client);
  });

  server.on("error", (error) => {
    process.stderr.write(`loopback-forwarder: port ${port} failed: ${error.message}\n`);
    process.exit(1);
  });

  server.listen(port, "127.0.0.1", () => {
    process.stdout.write(`loopback-forwarder: 127.0.0.1:${port} -> ${UPSTREAM_HOST}:${port}\n`);
  });
}
