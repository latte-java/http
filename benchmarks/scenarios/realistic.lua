-- realistic.lua
-- Browser-like request behind a proxy: GET /
-- Exercises Accept-Encoding parsing (q-value sort), Accept-Language locale parsing,
-- and X-Forwarded-* comma splitting on every request.
-- wrk flags: -t12 -c100 -d30s

wrk.method = "GET"
wrk.headers["Accept"] = "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
wrk.headers["Accept-Encoding"] = "gzip, deflate, br, zstd"
wrk.headers["Accept-Language"] = "en-US,en;q=0.9,fr;q=0.8"
wrk.headers["User-Agent"] = "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_4) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Safari/605.1.15"
wrk.headers["X-Forwarded-For"] = "203.0.113.42, 198.51.100.7"
wrk.headers["X-Forwarded-Host"] = "www.example.com"
wrk.headers["X-Forwarded-Proto"] = "https"

dofile(os.getenv("SCENARIO_DIR") .. "/json-report.lua")
