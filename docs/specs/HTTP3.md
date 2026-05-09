# HTTP/3 Status — latte-java HTTP Server

HTTP/3 is **not implemented** in latte-java and is out of scope for the foreseeable future. This document explains why, surveys how peer Java servers handle HTTP/3 today, and lists the conditions under which we would reconsider.

## Legend

- ✅ **Implemented** — covered by code and tests
- ⚠️ **Partial** — works for the common case, has known gaps or missing tests
- ❌ **Missing** — not implemented; would need work to claim conformance
- 🚫 **Out of scope** — handler responsibility, or deliberately not implemented

---

## 1. Why HTTP/3 is hard for pure Java

### HTTP/3 = HTTP semantics over QUIC, not TCP

HTTP/3 (RFC 9114) carries HTTP semantics over QUIC (RFC 9000) instead of TCP. QUIC is a full transport protocol, not a thin wrapper:

- **Connection establishment with TLS 1.3 baked in** — QUIC does not use TLS as a separate layer; TLS 1.3 handshake messages are carried inside QUIC packets. There is no way to bolt the JDK's `SSLEngine` on top; the crypto must be woven into the QUIC state machine.
- **Multiple independent streams over UDP** — stream multiplexing happens in QUIC itself, eliminating TCP's head-of-line blocking problem. This requires a complete stream multiplexer on top of raw UDP sockets.
- **Congestion control** — full CUBIC/BBR implementations (sender-side); not delegated to the OS as with TCP.
- **Packet loss recovery and retransmission** — QUIC handles its own packet numbering, ACKs, and retransmission in user space.
- **0-RTT** — session resumption that allows data in the very first packet; has its own replay-attack surface.
- **Connection migration** — connections survive IP address changes (e.g., mobile devices switching networks). Requires tracking connection IDs independent of the 4-tuple.

Each of these is a substantial engineering problem. Together they constitute a transport stack that rivals the TCP/IP stack itself in complexity.

### QUIC implementations in production

Production QUIC is universally implemented in languages with access to system crypto libraries and fine-grained memory control:

| Implementation | Language | Notes |
|---|---|---|
| Quiche | Rust (Cloudflare) | Powers Cloudflare's edge; JNI bindings available |
| msquic | C (Microsoft) | Reference implementation used by Windows, .NET |
| lsquic | C (LiteSpeed) | Used in LiteSpeed Web Server |
| quic-go | Go | Most widely deployed pure-language QUIC |
| ngtcp2 | C | Used by curl, nginx (experimental) |

### Pure-Java QUIC

KWIK (https://github.com/ptrd/kwik) is a pure-Java QUIC implementation. It is research-grade: actively maintained by a single contributor, not deployed at production scale, and not available via standard package repositories. There is no widely-used, production-ready pure-Java QUIC library.

The JDK has no built-in QUIC API. Discussions around a `Net.QuicSocket` or similar JEP have been raised but no JEP has been delivered as of Java 21. The ecosystem is monitoring Project Loom integration patterns but nothing is finalized.

---

## 2. Latte-http stance

| Constraint | Impact |
|---|---|
| Zero-dependency, pure-Java policy | Precludes JNI bindings to Quiche, msquic, lsquic, or any native QUIC library |
| No JDK QUIC API | No standard hook to wire into; would require raw UDP + full QUIC state machine |
| Implementation scope | Parser + packet state machine + crypto integration + congestion control + retransmission = multi-thousand-line subsystem independent of HTTP framing |
| Realistic timeline | Deferred until JDK ships a standard QUIC API **or** project policy explicitly opens up to a curated native dependency |

HTTP/3 is not a feature we are working toward under the current project constraints. It is genuinely out of scope, not merely deferred to a future milestone.

---

## 3. Peer comparison (RFC 9114 / HTTP/3 / QUIC)

Status snapshot as of 2026. The HTTP/3 ecosystem is moving; check each project's release notes for current status.

| Server | HTTP/3 status | Mechanism | Notes |
|---|---|---|---|
| Latte http | 🚫 | — | Out of scope per zero-dep policy |
| Jetty 12 | ✅ | Quiche via JNI | Available as an add-on connector (`jetty-quic`); requires Quiche native library. Experimental, not enabled by default. |
| Tomcat 11 | ⚠️ | Tomcat Native + OpenSSL with QUIC | Experimental; requires Tomcat Native (JNI) built against OpenSSL with QUIC support. |
| Netty 4 | ⚠️ | `netty-incubator-codec-quic` + `netty-incubator-codec-http3` | Incubating modules; requires Quiche via JNI. Not part of the mainline Netty release. |
| Undertow | ❌ | — | No HTTP/3 support; no announced roadmap item as of this writing. |
| Helidon Níma 4 | ⚠️ | — | On roadmap; not GA as of this writing. |
| JDK HttpServer | ❌ | — | No HTTP/3 support. |

**Pattern:** every Java server that has HTTP/3 today uses JNI bindings to a native QUIC library (almost universally Quiche). There is no Java HTTP server shipping production HTTP/3 in pure Java.

---

## 4. Roadmap / re-evaluation triggers

We will revisit HTTP/3 support if any of the following occur:

1. **JDK ships a standard QUIC API** — a delivered JEP that provides `QuicSocket` or equivalent, analogous to what `SSLSocket`/`SSLEngine` did for TLS. This would give us a JDK-native hook without violating the zero-dependency policy.
2. **A mature pure-Java QUIC library emerges** — production-ready, widely deployed, available via a standard release channel. KWIK is not there yet; this condition requires ecosystem maturity comparable to what Netty achieved for NIO.
3. **Project policy explicitly allows curated native dependencies** — if the project decides to permit a narrow, vetted native dep (as Jetty does with Quiche), HTTP/3 becomes feasible through a JNI adapter. This is a policy decision, not a technical one.

Until one of these triggers is met, HTTP/3 remains out of scope. There is no partial or experimental implementation path that fits the current project constraints.

---

## 5. Out of scope — feature details

| Feature | Status | Notes |
|---|---|---|
| HTTP/3 server | 🚫 | Requires QUIC transport; see §§1–2 above |
| HTTP/3 client | 🚫 | Latte-http has no HTTP client at all |
| Server push (RFC 9114 §4.6) | 🚫 | Deprecated trend ecosystem-wide; removed from browsers; even if h3 were added, push would not be |
| 0-RTT | 🚫 | Replay-attack surface; even QUIC libraries default-disable it in server mode |
| Connection migration | 🚫 | Requires QUIC connection-ID tracking; not applicable over TCP |
| QPACK (RFC 9204) | 🚫 | HTTP/3 header compression; analogous to HPACK but QUIC-specific; not implemented |
| HTTP/3 Alt-Svc advertisement | 🚫 | No QUIC listener to advertise |
