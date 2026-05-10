# Benchmark Certificate Fixtures

This directory contains a static self-signed certificate and private key used exclusively by the benchmark servers to test TLS+ALPN HTTP/2 scenarios. These files are **not for production use**.

## Files

| File | Description |
|------|-------------|
| `server.crt` | Self-signed X.509 certificate (PEM), valid 10 years, CN=127.0.0.1, SAN=DNS:localhost,IP:127.0.0.1 |
| `server.key` | RSA 2048-bit private key (PEM, PKCS8 unencrypted) |
| `keystore.p12` | PKCS12 keystore containing the cert+key (password: `benchmark`), used by Jetty |

## Regenerating

```bash
cd benchmarks/certs

# PEM cert + key
openssl req -x509 -newkey rsa:2048 -nodes -days 3650 \
  -keyout server.key -out server.crt \
  -subj "/CN=127.0.0.1" \
  -addext "subjectAltName=DNS:localhost,IP:127.0.0.1"

# PKCS12 keystore for Jetty
openssl pkcs12 -export -out keystore.p12 \
  -inkey server.key -in server.crt \
  -password pass:benchmark
```

## Security note

These are fixed, publicly-committed benchmark fixtures. The private key is intentionally not secret. Never use these files for anything other than local benchmark testing.
