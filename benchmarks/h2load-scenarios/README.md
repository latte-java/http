# h2load benchmark scenarios

HTTP/2 equivalents of the `wrk`-based scenarios in `../scenarios/`. The h2 protocol can't be measured by `wrk` (no h2 support), so we use h2load from nghttp2.

Currently this directory has `hello.sh` as a starting point. Full coverage of all the wrk scenarios is a follow-up — running benchmarks is user-gated due to CPU cost.

To run:
1. `./tools/install-h2load.sh` (verify h2load is installed)
2. Boot the latte-http server with h2c prior-knowledge enabled (see `benchmarks/self/`)
3. `./benchmarks/h2load-scenarios/hello.sh http://127.0.0.1:8080 10 10 100`

Args: `<host>` `<duration>` `<connections>` `<streams_per_conn>`.
