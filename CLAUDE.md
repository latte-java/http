# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Zero-dependency HTTP server library for Java 21+ using virtual threads and blocking I/O (not NIO). Forked from FusionAuth's java-http. The HTTP client is not yet implemented.

## Build System

This project uses the **Latte** build tool, not Maven or Gradle. The build file is `project.latte`.

```bash
latte clean build          # Clean and compile
latte test                 # Run all tests (depends on build)
latte clean int            # Full integration build (clean + build + test)
latte clean int --excludePerformance --excludeTimeouts   # CI build (skip slow tests)
```

Tests use **TestNG** (not JUnit). The `java-testng` plugin supports these switches:

```bash
latte test --test=CoreTest           # Run a specific test class
latte test --onlyFailed              # Re-run only previously failed tests
latte test --onlyChanges             # Run tests affected by local changes
latte test --commitRange=HEAD~3      # Run tests affected by recent commits
latte test --skipTests               # Skip all tests (useful with int target)
```

## Key Build Details

- Java 21 required (specified in `project.latte`)
- Test JVM needs `--add-exports java.base/sun.security.x509=ALL-UNNAMED --add-exports java.base/sun.security.util=ALL-UNNAMED` (for self-signed cert generation in tests)
- Tests use a custom TestNG listener: `org.lattejava.http.BaseTest$TestListener`
- Zero production dependencies; test deps: jackson5, restify, testng, slf4j-nop

## Architecture

The server processes requests on this path:

1. **HTTPServer** — top-level entry point, manages listeners and the handler
2. **HTTPServerThread** — one per listener, accepts socket connections
3. Each connection spawns a **virtual thread** that reads/parses the request, calls the handler, and writes the response
4. **HTTPHandler** — single functional interface: `void handle(HTTPRequest req, HTTPResponse res)`

Key classes:
- `HTTPServer` / `HTTPServerConfiguration` — server lifecycle and config (builder pattern via `with*` methods)
- `HTTPListenerConfiguration` — port binding and optional TLS (certificate + private key as PEM strings)
- `HTTPRequest` / `HTTPResponse` — mutable request/response objects
- `HTTPValues` — all HTTP constants (headers, methods, status codes, content types)
- `HTTPTools` — parsing utilities (headers, query strings, URL decoding)

I/O layer (`org.lattejava.http.io`):
- `ChunkedInputStream` / `ChunkedOutputStream` — chunked transfer encoding
- `MultipartStream` / `MultipartStreamProcessor` — multipart form parsing
- `HTTPOutputStream` — response output with optional compression and chunking

The library has its own logging abstraction (`org.lattejava.http.log`) — no SLF4J/Log4j dependency.

## Testing

Tests live in `src/test/java/org/lattejava/http/`. `BaseTest` provides:
- Self-signed certificate generation via `sun.security.x509` APIs
- Server lifecycle helpers (start/stop with port allocation)
- Uses the JDK HTTP Client (`java.net.http.HttpClient`) for making test requests

HTTPS tests require a `/etc/hosts` entry: `127.0.0.1 local.lattejava.org`

## Java Module System

The project uses `module-info.java`. All public packages are exported — no internal packages are exposed to consumers. The `server/internal/` package exists but is not exported.
