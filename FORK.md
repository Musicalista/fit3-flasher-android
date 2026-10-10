# Fit3 App - "mods" fork

This is a fork of [yuriyurin/Fit3-App](https://github.com/yuriyurin/Fit3-App) (GPL-3.0). All credit for the app, the SAP protocol work, the flasher and the
firmware validation goes to **yuriyurin**. This fork keeps the GPL-3.0 license and the original copyright notices. It is unofficial and unaffiliated with Samsung.

## What this fork changes (relative to upstream 0.7.0b12)

- **Ready-made firmwares in the Firmware screen.** A new card lists packages shipped inside the APK (the original stock package plus modified packages). Tapping one
  rebuilds it from `assets/fw/` (stock + a small delta), checks its SHA-256 and sends it through the unchanged upstream flasher
  (same validation, same confirmations, same battery/version checks).
  Files: `ble/FirmwareBundle.kt`, `FirmwareScreen.kt`, `MainActivity.kt` (a few lines), `FirmwareFlasher.kt` (file-name fallback).
- `applicationId` is `io.github.yuriyurin.fit3companion.mods`, so it installs next to the original app. Do not control the same band with both apps at once.

## Building

`assets/fw/` is not committed (it contains Samsung's original firmware). Generate it with `mkdelta.py` from the Fit3 mods project:

```
python mkdelta.py <folder with the modified .bin packages> <stock-aza3.bin>      # writes fw_assets/stock.bin, *.dlt and fw_list.txt
copy fw_assets\* app\src\mainssetswgradlew assembleDebug
```

Without `assets/fw/` the app builds and works exactly like upstream (the card is simply hidden).

## Warning

Only the Galaxy Fit3 (SM-R390) running R390XXU0AZA3 is supported. Flashing can leave the watch unusable (reboot loop). Keep the stock package and read the upstream README.
