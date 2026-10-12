# Rust Unix-socket smoke test (rust.md section 10)

Run once per framework (Oxide, Carbon) on a disposable Rust server before trusting the Unix transport.
`PlayerManagerSmoke.cs` is not part of any release.

## What it checks

1. `typeof(UnixDomainSocketEndPoint).Assembly.FullName` and `Socket.OSSupportsUnixDomainSockets` (logged).
2. A `Socket(AddressFamily.Unix, Stream)` binds `.playermanager/smoke.sock` (absolute path if it is under 108 bytes, otherwise the relative path), listens, accepts on a worker thread and echoes length-prefixed frames.
3. The echo works from inside the container and through the Wings tunnel.
4. `NextTick` fires, `timer.Once` fires (logged).
5. The `wear` container capacity of a connecting player (spec: 8; also logs `main`, `belt`).
6. `smoke.sock` is deleted on plugin unload.
7. `Socket` and `UnixDomainSocketEndPoint` compile without any `// Reference:` line (the file has none; a compile error here is itself the answer).

## Oxide

1. Server with Oxide installed, started once (so `oxide/plugins/` exists).
2. Copy `PlayerManagerSmoke.cs` to `oxide/plugins/`.
3. Watch the console. Expect lines prefixed `[smoke]`: framework, protocol, `UnixDomainSocketEndPoint assembly = ...`, `OSSupportsUnixDomainSockets = ...`, `NextTick drain FIRED`, `bound + listening on ...`.
4. Join the server with a client (or have a bot join): expect `wear capacity = N`.
5. Inside the container (console of the server container, or `docker exec`), in the server root:
   `python3 plugins/rust/smoke/smoke-client.py .playermanager/smoke.sock` (copy `smoke-client.py` to the server first; the container may only have `python3` if the image has it, otherwise do step 6 from the host against the volume path).
   Expect `PASS: echoed b'hello'`, and `[smoke] echoed 5 bytes` in the console.
6. Through the Wings tunnel: the panel's tunnel is `GET /api/servers/{server}/ws/socket?path=.playermanager/smoke.sock` (websocket carrying raw bytes). Send one binary message `00 00 00 05 68 65 6c 6c 6f`; expect the same 9 bytes back as one or more binary messages. Any websocket tool works, for example `websocat -b --header "Authorization: Bearer <token>" "wss://<wings-host>/api/servers/<uuid>/ws/socket?path=.playermanager/smoke.sock"` with the bytes piped in (`printf '\x00\x00\x00\x05hello' | websocat -b -n1 ...`). Wings authentication details are Wings'; use whatever mechanism the panel uses.
7. Reload the plugin (`oxide.reload PlayerManagerSmoke`, then `oxide.unload PlayerManagerSmoke`): expect `socket file deleted on unload` and `.playermanager/smoke.sock` gone (`ls -la .playermanager`).

## Carbon

Same steps with the file in `carbon/plugins/` and commands `c.reload PlayerManagerSmoke` / `c.unload PlayerManagerSmoke` (older builds: `carbon.reload`). The same file serves both; `#if CARBON` only changes the first log line.

## What to record in NOTES.md (section 5, Rust)

For each framework (Oxide / Carbon):
- Framework version and Rust build (`Rust.Protocol.printable` is logged; also the output of `version`).
- Assembly name printed for `UnixDomainSocketEndPoint` and for `Socket`.
- `OSSupportsUnixDomainSockets` value (or "not present").
- Whether the file compiled with no `// Reference:`.
- Echo result in-container: pass/fail, exception text if any.
- Echo result through the Wings tunnel: pass/fail.
- `NextTick` fired: yes/no and the delay.
- `wear` capacity (and main/belt).
- Socket file removed on unload: yes/no.
- Decision: Unix transport ok / ship TCP fallback (any failure above means TCP is the default, see rust.md section 10 step 7).
