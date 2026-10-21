# Bypass charging

Quick Settings tile for Evolution X on the POCO F7 (onyx): while the charger is plugged in, the battery stops
charging and the charger powers the phone. No root.

Two modes, chosen on the page that opens with a long press on the tile:

- **Smart**: charge up to 80%, then run from the charger; charging resumes at 77%.
- **Bypass**: hold the battery at the level it had when the tile was turned on. The battery only tops up again
  once it is 3% lower (when the phone draws more than the charger gives). Charging control only accepts limits
  from 70 to 100 (`CHARGING_CONTROL_LIMIT_VALIDATOR`), so below 70% the battery charges to 70% first.

Bypass turns itself off after every reboot (`BootReceiver`, on `LOCKED_BOOT_COMPLETED`), and the charging control
settings from before it was turned on come back whenever it is turned off.

## Cool charging

On by default (switch under Fast charging). The charger driver's JEITA tables keep full current up to 48 °C,
so a phone charging at 90 W reaches about 47 °C before it slows down. Cool charging steps the battery current
down instead, with 0.5 °C of hysteresis:

| battery | limit |
|---|---|
| below 40 °C | none |
| 40 °C | 10 A |
| 41 °C | 7 A |
| 42 °C | 4.5 A |
| 43 °C and up | 2.5 A |

It is its own voter (`coolcharging`) on the MCA `ichg_limit` node, next to the power limit (`bypasscharging`).
The driver takes the lowest vote, so neither can make charging faster than the other or than the thermal limits.
The app's process is persistent and follows `ACTION_BATTERY_CHANGED`; the vote is dropped on unplug.

## How it works

The app only uses the Lineage SDK: it reads charging control through `lineageos.health.HealthInterface` and
switches Settings > Battery > Charging control to limit mode with the limit above by writing
`LineageSettings.System` for its own user, which `ChargingControlController` observes. (The `HealthInterface`
setters write for `USER_CURRENT` with the caller's identity, which needs `INTERACT_ACROSS_USERS_FULL`; on user
builds that platform signature permission is not granted to an app updated outside the system image.) The Lineage Health HAL then writes the MCA `smart_chg`
node, configured in the device tree:

| `smart_chg` value | meaning |
|---|---|
| `65553` (`0x10011`) | enable + `endurance_pro` (bit 4) + `soc_limit` 1: stop charging the battery now, at any level |
| `16` | `endurance_pro` off: charge normally |

`smart_chg` reads back a feature mask rather than the written value, so the device overlay sets
`config_chargingControlToggleSetAlways`, and `config_chargingControlBatteryRechargeMargin` is 3.

It replaces a root-only tile (`com.qs.bypass.charging`) that wrote `smart_night`, which the kernel only honours
at a fixed 80%.

## Build

In the device tree:

```
PRODUCT_PACKAGES += BypassCharging
```

Platform-signed, installed to `system_ext`. Needs `vendor.lineage.health-service.default` with charging control
on `smart_chg` (see `device.mk` in `fakhrads/android_device_xiaomi_onyx`, branch `evo-cnb`).
