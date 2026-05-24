# GP Radio Relay

This small relay turns the raw Grand Prix Radio MP3 stream into a short-window HLS playlist using local `ffmpeg`.

## Run

```powershell
cd c:\race-control-tv-st14n\tools\gp-radio-relay
npm start
```

Environment variables:

- `PORT`: relay port, default `8787`
- `INPUT_URL`: source radio stream, default `https://playerservices.streamtheworld.com/api/livestream-redirect/GRAND_PRIX_RADIO.mp3`
- `WORK_DIR`: directory for generated playlist and segments

## Use In The App

- Emulator: leave the relay URL empty and set `Grand Prix Radio backend` to `HLS relay`. The app defaults to `http://10.0.2.2:8787/live.m3u8` on emulators.
- Real device: open the relay on your PC, note the printed LAN playlist URL, then paste that URL into the app setting `Grand Prix Radio relay URL`.

## Endpoints

- `/live.m3u8`: HLS playlist
- `/health`: JSON health status
- `/`: plain-text usage summary