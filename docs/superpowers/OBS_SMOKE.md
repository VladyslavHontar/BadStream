# OBS Remote — On-Device Smoke Test

## Prerequisites
- OBS Studio running on a LAN machine with obs-websocket v5 enabled  
  (Tools → WebSocket Server Settings → Enable WebSocket server; note port 4455 + password)
- Phone on the same LAN

## Steps
1. Open PlohoyStream → Settings → OBS Remote
2. Enter OBS Host (LAN IP), Port (4455), Password → Status shows "Connecting…" then "Connected"
3. Scene list appears; tap a scene → OBS switches to it
4. Tap **Start OBS Stream** → OBS begins streaming (button switches to Stop)
5. Tap **Stop OBS Stream** → OBS stops streaming
6. Set Main Scene = your main scene name, BRB Scene = your BRB scene name, enable Auto-switch
7. Go Live on the phone; once live, disconnect the phone's WiFi → OBS flips to BRB scene automatically
8. Reconnect WiFi → phone reconnects → OBS flips back to Main scene

## Expected outcome
All steps pass without the phone's own stream being affected by OBS being down or slow.
This is the manual acceptance gate for the OBS remote feature.
