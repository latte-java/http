# Test proto stubs

Generated stubs are checked into `src/test/java/org/lattejava/http/tests/grpc/` so the build doesn't need a `protoc` invocation. Regenerate when the .proto changes:

```
protoc --java_out=src/test/java --grpc-java_out=src/test/java --proto_path=src/test/proto src/test/proto/echo.proto
```
