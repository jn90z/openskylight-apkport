# Required companion application

The Android port must preserve the upstream LAN companion web app, not remove it as a memory optimization.

## Architecture
- Reuse the existing src/companion React UI and its API contract.
- Package its separately built static assets alongside the main renderer.
- Implement a lightweight embedded Android HTTP server, with bounded worker threads and request sizes. No Node.js or second browser runtime on the tablet.
- Both the Capacitor bridge and companion HTTP routes call the same native service layer and SQLite database. Preserve transactions, validation, authorization and change notifications.
- Preserve upstream pairing, token revocation and parent authorization semantics; protect credentials and tokens, avoid logging them, restrict exposure to trusted LAN, and evaluate transport security before release.
- Preserve companion functionality and test against upstream routes, including calendars, chores and lists. No external server required for LAN operation.
- Keep companion reachable while the display sleeps when enabled; evaluate Android foreground service and power-management requirements with user-visible notification as appropriate. Do not assume unrestricted background execution.
- Use event-driven invalidation, bounded caches, no duplicate database, no continuous busy polling. Measure combined WebView and server memory on the 2 GB SM-T510.

## Acceptance
Test pairing from a second device, authorized read/write, changes reflected on both screens, token revocation, restart persistence, screen-off access, network changes and 24-hour resource usage.

Status: architecture requirement only; server, companion asset packaging and native services are not yet implemented.
