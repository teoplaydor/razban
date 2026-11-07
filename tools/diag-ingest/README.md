# Isolated diagnostic ingest

HTTPS receiver for allowlisted Razban test-app events.

- Listen: TCP 9446 only
- Does not bind or change 443, xray, amnezia, or 9444
- Per-device HMAC enrolled over TLS
- Events are filtered to: stage, error_code, duration_ms, app_version, network_type, session_id
