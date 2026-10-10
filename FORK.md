# Fit3 App - "mods" fork

This is a fork of [yuriyurin/Fit3-App](https://github.com/yuriyurin/Fit3-App) (GPL-3.0). All credit for the app, the SAP protocol work, the flasher and the
firmware validation goes to **yuriyurin**. This fork keeps the GPL-3.0 license and the original copyright notices. It is unofficial and unaffiliated with Samsung.

## What this fork changes (relative to upstream 0.7.0b12)

- **Ready-made firmwares in the Firmware screen.** A new card lists packages shipped inside the APK (the original stock package plus modified packages). Tapping one
  rebuilds it from `assets/fw/` (stock + a small delta), checks its SHA-256 and sends it through the unchanged upstream flasher
  (same validation, same confirmations, same battery/version checks).
  Files: `ble/FirmwareBundle.kt`, `FirmwareScreen.kt`, `MainActivity.kt` (a few lines), `FirmwareFlasher.kt` (file-name fallback).
- **Home screen shortcut.** A "Firmwares" button on the home screen opens the flasher directly, instead of only through Settings > Debug > Flasher.
- **Serial-link fallback.** The transfer in `FirmwareFlasher.kt` now tries up to 4 ways to open the classic RFCOMM link before giving up (secure/insecure pairing, service UUID or fixed channel 29), only while no data has been sent yet. Helps on bands whose Bluetooth SPP service record is missing or stale.
- **Classic-Bluetooth pairing button**, in the Firmware screen: requests a classic (BR/EDR) bond with the band from inside the app. Samsung's system Bluetooth settings redirect a "Galaxy Fit3" pairing attempt into Galaxy Wearable, which never creates the classic bond this flasher's RFCOMM transfer needs; this button asks for that bond directly.
- `applicationId` is `io.github.yuriyurin.fit3companion.mods`, so it installs next to the original app. Do not control the same band with both apps at once.

## Background: two access paths with different trust levels

The flasher's RFCOMM channel (custom service UUID `db764ac8-4b08-7f25-aafe-59d03c27bae3`, also reachable on a fixed channel) is a plain classic-Bluetooth file-transfer/debug channel with no cryptographic handshake of its own beyond standard BT pairing. By contrast, the official Galaxy Wearable companion app pairs over BLE using the Samsung Accessory Protocol (SAP) with a public-key-based provisioning step (`pkiPublicKey` / `mSaPublic`, visible in that app's own code -- not reverse engineered beyond reading strings). That is a separate, more strongly authenticated path. This asymmetry -- and the fact that the firmware's own update-image check is CRC32 only, not a cryptographic signature -- was reported by the fork author to Samsung; it is not something this fork fixes (see the Warning below).

## Building

`assets/fw/` is not committed (it contains Samsung's original firmware). Generate it with `mkdelta.py` from the Fit3 mods project:

```
python mkdelta.py <folder with the modified .bin packages> <stock-aza3.bin>
# writes fw_assets/stock.bin, *.dlt and fw_list.txt
copy fw_assets\* app\src\main\assets\fw\
gradlew assembleDebug
```

Without `assets/fw/` the app builds and works exactly like upstream (the card is simply hidden).

## Warning

Only the Galaxy Fit3 (SM-R390) running R390XXU0AZA3 is supported. Flashing can leave the watch unusable (reboot loop). Keep the stock package and read the upstream README. The RFCOMM debug channel this flasher uses accepts unsigned firmware images (no cryptographic signature check on the device) -- treat any package you flash with the same care you would treat running untrusted code on the watch.
