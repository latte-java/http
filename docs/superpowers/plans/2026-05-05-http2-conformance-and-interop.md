# HTTP/2 Conformance + Interop Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Validate the HTTP/2 implementation against external reference clients. Two layers: (1) RFC 9113 conformance via [`h2spec`](https://github.com/summerwind/h2spec) — a battle-tested test suite that exercises edge cases the in-tree tests miss; (2) gRPC interop via `grpc-java` to prove the implementation is good enough for real production traffic. After this plan: any conformance bug found by `h2spec` or `grpc-java` is filed and fixed.

**Architecture:** A new Latte target `int-h2spec` boots a server on a random port and runs the `h2spec` binary against it. A separate `GRPCInteropTest` adds a test-only `grpc-java` dependency and round-trips the four canonical streaming patterns. Both run inside the existing `latte test` invocation when invoked with the right switches.

**Tech Stack:** `h2spec` (external Go binary, called via `Runtime.exec`); `io.grpc:grpc-netty` and `io.grpc:grpc-protobuf` as test-only dependencies.

**Reference spec:** `docs/superpowers/specs/2026-05-05-http2-design.md` §"Test plan" layers 4 and 5.

**Depends on:** Plan D (HTTP/2 wire-up) merged.

---

## Important note on scope

Until this plan starts, the exact failure profile from `h2spec` is unknown. Many Java HTTP/2 servers have a backlog of small spec violations on first run. **The bug ledger that comes out of Task 2 is the actual scope of Task 4** — we cannot enumerate the fixes ahead of time.

This plan therefore looks different from Plans A–D: Tasks 1–2 set up the harness; Task 3 is the discovery run; Task 4 is "iterate until clean," which the engineer expands into specific bug-fix tasks based on the actual failures.

---

## File Structure

| File | Status | Responsibility |
|---|---|---|
| `project.latte` | Modify | Add `grpc-java` test deps; new `int-h2spec` target |
| `tools/install-h2spec.sh` | Create | Download the right `h2spec` binary for the host platform |
| `src/test/java/org/lattejava/http/tests/server/H2SpecHarness.java` | Create | Boot a server, exec `h2spec`, parse JSON output, fail on any failed case |
| `src/test/java/org/lattejava/http/tests/server/GRPCInteropTest.java` | Create | Adapter wiring `HTTPHandler` to a `grpc-java` server-side dispatch; four streaming patterns |
| `src/test/proto/echo.proto` | Create | Protobuf service for the interop tests |
| `docs/specs/HTTP2.md` | Modify | Cite passing `h2spec` run; bump conformance status |

---

## Phase 1 — h2spec harness

### Task 1: Add `tools/install-h2spec.sh`

**Files:**
- Create: `tools/install-h2spec.sh`

A small shell script that downloads the matching `h2spec` release binary into a known location (e.g. `build/h2spec`), idempotent.

- [ ] **Step 1: Write the script**

```bash
#!/usr/bin/env bash
# Download h2spec for the host platform into build/h2spec.
set -euo pipefail

VERSION="${H2SPEC_VERSION:-2.6.1}"
DIR="build"
BIN="${DIR}/h2spec"

if [[ -x "${BIN}" ]]; then
  echo "h2spec already present at ${BIN}"
  exit 0
fi

mkdir -p "${DIR}"

case "$(uname -s)-$(uname -m)" in
  Darwin-arm64|Darwin-x86_64) ASSET="h2spec_darwin_amd64.tar.gz" ;;
  Linux-x86_64) ASSET="h2spec_linux_amd64.tar.gz" ;;
  *) echo "unsupported platform $(uname -s)-$(uname -m)" >&2; exit 1 ;;
esac

URL="https://github.com/summerwind/h2spec/releases/download/v${VERSION}/${ASSET}"
echo "Downloading ${URL}"
curl -fsSL "${URL}" -o "${DIR}/h2spec.tar.gz"
tar -xzf "${DIR}/h2spec.tar.gz" -C "${DIR}"
rm "${DIR}/h2spec.tar.gz"
chmod +x "${BIN}"
echo "Installed h2spec ${VERSION} at ${BIN}"
```

- [ ] **Step 2: Make executable + test run**

```bash
chmod +x tools/install-h2spec.sh
./tools/install-h2spec.sh
build/h2spec --version
```

Expected: prints version `2.6.1` (or whatever pinned).

- [ ] **Step 3: Commit (do not commit `build/h2spec` — it's a build artifact)**

```bash
echo "/build/h2spec" >> .gitignore
git add tools/install-h2spec.sh .gitignore
git commit -m "Add h2spec installer script"
```

---

### Task 2: `H2SpecHarness` test

**Files:**
- Create: `src/test/java/org/lattejava/http/tests/server/H2SpecHarness.java`

- [ ] **Step 1: Write the harness**

```java
/*
 * Copyright (c) 2026, The Latte Project
 */
package org.lattejava.http.tests.server;

import module java.base;
import module org.lattejava.http;
import module org.testng;

import static org.testng.Assert.*;

/**
 * Boots a minimal h2c server on a random port and runs h2spec against it. Marked with the "h2spec" group so it can be excluded from the normal `latte test` run; included by the `int-h2spec` target.
 *
 * @author Daniel DeGroff
 */
public class H2SpecHarness extends BaseTest {
  private static final Path H2SPEC_BIN = Path.of("build/h2spec");

  @Test(groups = "h2spec")
  public void run_h2spec() throws Exception {
    if (!Files.isExecutable(H2SPEC_BIN)) {
      throw new SkipException("h2spec not installed at " + H2SPEC_BIN + " — run tools/install-h2spec.sh");
    }

    HTTPHandler handler = (req, res) -> {
      res.setStatus(200);
      try (var os = res.getOutputStream()) {
        os.write("ok".getBytes());
      }
    };

    var listener = new HTTPListenerConfiguration(0).withH2cPriorKnowledgeEnabled(true);
    try (var server = makeServerWithListener(listener, handler).start()) {
      int port = server.getActualPort();
      var pb = new ProcessBuilder(
          H2SPEC_BIN.toString(),
          "-h", "127.0.0.1",
          "-p", String.valueOf(port),
          "--strict",
          "--junit-report", "build/h2spec-report.xml"
      );
      pb.redirectErrorStream(true);
      Process p = pb.start();
      String output = new String(p.getInputStream().readAllBytes());
      int exit = p.waitFor();

      System.out.println(output);

      if (exit != 0) {
        // The JUnit report at build/h2spec-report.xml lists the specific failures.
        fail("h2spec reported failures (exit=" + exit + "). See build/h2spec-report.xml. Output above.");
      }
    }
  }
}
```

- [ ] **Step 2: Add an `int-h2spec` Latte target**

Edit `project.latte`. Add after the existing `test` target:

```groovy
target(name: "int-h2spec", description: "Runs the h2spec conformance suite", dependsOn: ["build"]) {
  exec(["./tools/install-h2spec.sh"])
  javaTestNG.test(groups: "h2spec")
}
```

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "Add h2spec harness and int-h2spec Latte target"
```

---

### Task 3: First `h2spec` run — capture failures into a bug ledger

**Files:**
- Modify: `docs/specs/HTTP2.md` (`Bug ledger` section)

- [ ] **Step 1: Run**

Run: `latte int-h2spec`

The `h2spec` suite covers ~150 test cases across the RFC 9113 spec, RFC 7541 (HPACK), and the HTTP/2 generic semantics. **Some failures are expected on first run.**

- [ ] **Step 2: For each failure, file a bug ledger entry**

Open `docs/specs/HTTP2.md`. Find the `## Bug ledger` section. For each failed case, add an entry:

```markdown
- **[h2spec 6.5.3/2]** SETTINGS frame with empty payload should be acknowledged. **Failure mode:** [brief description from h2spec output]. **Fix path:** [hypothesis about the offending code].
```

(The h2spec output contains the case ID, the expected/actual behavior, and a citation to the RFC section.)

- [ ] **Step 3: Commit the bug ledger**

```bash
git add docs/specs/HTTP2.md
git commit -m "Capture h2spec first-run failures into HTTP2.md bug ledger"
```

---

### Task 4: Iterate to clean

**For each entry in the bug ledger, do the following:**

- [ ] **Step 1: Read the h2spec test source for the failing case**

`h2spec`'s test source is at https://github.com/summerwind/h2spec/tree/master/spec — find the case by ID. Understand exactly what frame/state it expects.

- [ ] **Step 2: Write a tighter local test mirroring the case**

Add to `HTTP2RawFrameTest` (or a new test class if the failure is HPACK-specific). Reproducing the case locally turns "h2spec is angry" into "I have a failing TestNG test pointing at one specific frame interaction" — which is much easier to fix.

- [ ] **Step 3: Fix the offending code**

The fix usually lives in `HTTP2Connection.runFrameLoop` or the per-handler methods, the HPACK decoder, or the frame reader. Often it's a single missing validation.

- [ ] **Step 4: Re-run `latte int-h2spec`**

Confirm the case now passes. Move on to the next bug ledger entry.

- [ ] **Step 5: Commit per fix**

One commit per bug for traceable history:
```bash
git commit -m "Fix [h2spec 6.5.3/2]: SETTINGS empty payload must be ACKed"
```

- [ ] **Step 6: Bug ledger empty → tag**

```bash
git tag -a http2-h2spec-clean -m "h2spec --strict run is clean against latte-java"
```

---

## Phase 2 — gRPC interop

### Task 5: Add `grpc-java` test dependencies

**Files:**
- Modify: `project.latte`

- [ ] **Step 1: Add the deps**

In the `test-compile` group. Note `export: false` on the group — these dependencies are only on the test classpath. The library itself remains zero-dependency in production; `grpc-netty` (which transitively pulls Netty) does not leak into shipped jars.

```groovy
group(name: "test-compile", export: false) {
  // existing...
  dependency(id: "io.grpc:grpc-stub:1.63.0")
  dependency(id: "io.grpc:grpc-protobuf:1.63.0")
  dependency(id: "io.grpc:grpc-netty:1.63.0")
  dependency(id: "com.google.protobuf:protobuf-java:3.25.0")
}
```

(Use the latest compatible versions. `grpc-netty` is needed for the *client* side only — we connect with a gRPC client to our HTTP/2 server.)

- [ ] **Step 2: Compile to confirm dependency resolution**

Run: `latte clean build`
Expected: SUCCESS, with new transitive deps appearing on the classpath.

- [ ] **Step 3: Commit**

```bash
git add project.latte
git commit -m "Add grpc-java test dependencies"
```

---

### Task 6: Define the proto + generate stubs

**Files:**
- Create: `src/test/proto/echo.proto`

- [ ] **Step 1: Write the proto**

```proto
syntax = "proto3";
package latte.echo;

option java_package = "org.lattejava.http.tests.grpc";
option java_outer_classname = "EchoProto";

service Echo {
  rpc Unary(EchoRequest) returns (EchoResponse);
  rpc ServerStream(EchoRequest) returns (stream EchoResponse);
  rpc ClientStream(stream EchoRequest) returns (EchoResponse);
  rpc BidiStream(stream EchoRequest) returns (stream EchoResponse);
}

message EchoRequest { string message = 1; }
message EchoResponse { string message = 1; }
```

- [ ] **Step 2: Generate Java stubs**

`grpc-java` ships a `protoc` plugin. Either:
- (a) Vendor pre-generated stubs into `src/test/java/org/lattejava/http/tests/grpc/` and document the regen command, or
- (b) Add a Latte target that runs `protoc` at build time.

**Recommended:** (a) — protoc setup is environment-dependent and the proto rarely changes. The test stubs will be ~3 generated files.

Run `protoc` once locally:
```bash
protoc --java_out=src/test/java --grpc-java_out=src/test/java src/test/proto/echo.proto
```

Commit the generated files. Add a `README` next to the .proto:
```
src/test/proto/echo.proto.README:
> Proto stubs are pre-generated and checked in. Regenerate with:
> protoc --java_out=src/test/java --grpc-java_out=src/test/java src/test/proto/echo.proto
```

- [ ] **Step 3: Compile**

Run: `latte clean build`
Expected: SUCCESS — generated stubs compile.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "Add echo.proto and generated grpc stubs"
```

---

### Task 7: Server-side adapter — wire `HTTPHandler` to a gRPC server

This is the trickiest piece. gRPC-over-HTTP/2 has specific framing conventions on top of bare h2:
- Path: `/<service>/<method>`
- Content-Type: `application/grpc`
- Per-message: 1-byte compressed flag + 4-byte length prefix + protobuf body
- Trailers: `grpc-status` + optional `grpc-message`

**Two paths:**
- **Heavy path:** implement a real gRPC dispatcher inside `HTTPHandler` (`BindableService` integration). Lots of work; not the goal of this plan.
- **Light path:** for *each* of the four streaming patterns, write a hand-rolled handler that knows the proto framing. Sufficient for interop verification.

Use the light path.

**What this proves and what it does not.** Passing tests prove our HTTP/2 framing, HPACK, flow control, and trailer semantics are correct enough that `grpc-java`'s client side can talk to a gRPC handler running on our server. That is the v1 milestone. **It does not prove** that you can drop a `grpc-java` `BindableService` directly into `HTTPHandler` and get a working server — that integration (server-side `BindableService` adapter, full status/metadata mapping, deadlines, etc.) is a separate piece of work. Make this distinction explicit in `HTTP2.md` peer-comparison wording: "gRPC interop tested" means "framing-compatible with `grpc-java` clients," not "drop-in `grpc-java` server-side."

**Files:**
- Create: `src/test/java/org/lattejava/http/tests/server/GRPCInteropTest.java`

- [ ] **Step 1: Write the four interop tests**

```java
/*
 * Copyright (c) 2026, The Latte Project
 */
package org.lattejava.http.tests.server;

import module java.base;
import module org.lattejava.http;
import module org.testng;

import io.grpc.ManagedChannel;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.StreamObserver;
import io.grpc.netty.GrpcSslContexts;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;

import org.lattejava.http.tests.grpc.EchoGrpc;
import org.lattejava.http.tests.grpc.EchoProto.EchoRequest;
import org.lattejava.http.tests.grpc.EchoProto.EchoResponse;

import static org.testng.Assert.*;

public class GRPCInteropTest extends BaseTest {
  @Test
  public void unary() throws Exception {
    HTTPHandler handler = grpcUnaryAdapter(req -> EchoResponse.newBuilder().setMessage("hello, " + req.getMessage()).build());

    var listener = new HTTPListenerConfiguration(0).withH2cPriorKnowledgeEnabled(true);
    try (var server = makeServerWithListener(listener, handler).start()) {
      ManagedChannel channel = NettyChannelBuilder.forAddress("127.0.0.1", server.getActualPort())
          .usePlaintext()
          .build();
      try {
        var stub = EchoGrpc.newBlockingStub(channel);
        var resp = stub.unary(EchoRequest.newBuilder().setMessage("world").build());
        assertEquals(resp.getMessage(), "hello, world");
      } finally {
        channel.shutdown();
      }
    }
  }

  @Test
  public void server_stream() throws Exception { /* similar shape, server emits 5 responses */ }

  @Test
  public void client_stream() throws Exception { /* client sends 5 requests; server returns single combined response */ }

  @Test
  public void bidi_stream() throws Exception { /* both directions concurrent */ }

  // gRPC-on-HTTP/2 framing helpers
  private static HTTPHandler grpcUnaryAdapter(java.util.function.Function<EchoRequest, EchoResponse> impl) {
    return (req, res) -> {
      // Read length-prefixed proto message from req body
      var in = req.getInputStream();
      int compressed = in.read();
      int len = (in.read() << 24) | (in.read() << 16) | (in.read() << 8) | in.read();
      byte[] payload = in.readNBytes(len);
      EchoRequest grpcReq = EchoRequest.parseFrom(payload);

      // Invoke handler
      EchoResponse grpcResp = impl.apply(grpcReq);
      byte[] respBytes = grpcResp.toByteArray();

      // Write headers
      res.setStatus(200);
      res.setHeader("content-type", "application/grpc");
      var out = res.getOutputStream();

      // Write framed response
      out.write(0); // not compressed
      out.write(new byte[]{(byte)((respBytes.length >> 24) & 0xFF), (byte)((respBytes.length >> 16) & 0xFF), (byte)((respBytes.length >> 8) & 0xFF), (byte)(respBytes.length & 0xFF)});
      out.write(respBytes);

      // Write grpc-status trailer
      res.setTrailer("grpc-status", "0");
      out.close();
    };
  }
}
```

(Server-stream / client-stream / bidi-stream follow the same shape with appropriate read/write loops; ~30 LOC each. Reference: gRPC HTTP/2 spec at https://github.com/grpc/grpc/blob/master/doc/PROTOCOL-HTTP2.md.)

- [ ] **Step 2: Run**

Run: `latte test --test=GRPCInteropTest`
Expected: ALL FOUR PASS.

- [ ] **Step 3: Common failure modes & fixes**

If unary works but streaming doesn't, the failure is almost certainly in the trailer emission (h2 trailers ≠ h1 trailers — see Plan B/D for the response-side h2 emission). If unary itself fails, the issue is more likely in the basic framing, headers, or the `application/grpc` content-type handling.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "Add gRPC interop tests for all four streaming patterns"
```

---

### Task 8: TLS/h2 + gRPC interop

The Task 7 tests run over h2c (cleartext). Production gRPC almost always uses TLS. Add one test that hits the same `Echo.unary` over h2-via-ALPN.

**Files:**
- Modify: `src/test/java/org/lattejava/http/tests/server/GRPCInteropTest.java`

- [ ] **Step 1: Add a TLS variant**

```java
@Test
public void unary_over_tls() throws Exception {
  HTTPHandler handler = grpcUnaryAdapter(req -> EchoResponse.newBuilder().setMessage("tls-" + req.getMessage()).build());

  try (var server = makeServer("https", handler).start()) { // TLS server with self-signed cert
    SslContext ssl = GrpcSslContexts.forClient()
        .trustManager(InsecureTrustManagerFactory.INSTANCE)
        .build();
    ManagedChannel channel = NettyChannelBuilder.forAddress("local.lattejava.org", server.getActualPort())
        .sslContext(ssl)
        .build();
    try {
      var stub = EchoGrpc.newBlockingStub(channel);
      var resp = stub.unary(EchoRequest.newBuilder().setMessage("hi").build());
      assertEquals(resp.getMessage(), "tls-hi");
    } finally {
      channel.shutdown();
    }
  }
}
```

(Requires the `/etc/hosts` entry `127.0.0.1 local.lattejava.org` already documented in `CLAUDE.md`.)

- [ ] **Step 2: Run, commit**

```bash
git add -A
git commit -m "Add TLS-over-h2 gRPC interop test"
```

---

## Phase 3 — Documentation

### Task 9: Update `HTTP2.md` peer comparison + conformance

**Files:**
- Modify: `docs/specs/HTTP2.md`

- [ ] **Step 1: Update the peer comparison table**

Flip these to ✅:
- "h2spec clean run" — add a new row if not present
- "gRPC interop tested" — flip to ✅ (in-tree)

- [ ] **Step 2: Update the bug ledger**

If all bug ledger entries are resolved, replace the section content with:
```markdown
## Bug ledger

No open issues. h2spec --strict run is clean (last verified: 2026-MM-DD against h2spec v2.6.1).
```

- [ ] **Step 3: Commit**

```bash
git add docs/specs/HTTP2.md
git commit -m "HTTP2.md: h2spec clean and gRPC interop in-tree"
```

---

## Self-review checklist

- ✅ `h2spec` runs as a Latte target so CI can guard regressions
- ✅ Bug ledger drives Task 4's iteration — finite scope per fix
- ✅ All four gRPC streaming patterns covered
- ✅ Both cleartext (h2c prior-knowledge) and TLS (h2 + ALPN) gRPC paths tested
- ⚠️ Task 4's per-fix subtasks are not enumerated — the engineer must expand them based on actual h2spec output
- ⚠️ The light-path gRPC adapter is hand-rolled; if you later want a real `BindableService` integration, that's a separate spec
