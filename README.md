## BetaCellPlugin — Physiological β-cell pancreas model for AAPS

### What this PR does

Adds a new APS algorithm (`BetaCellPlugin`) that models the insulin secretion
behaviour of a healthy pancreatic β-cell in response to continuous glucose
monitor (CGM) readings.

Two selectable calculation modes (switched via `useNonLinear` preference):

| Mode | Formula | Use case |
|------|---------|----------|
| **Linear** (default) | `β = (BG − target) / ISF × dt` | Open loop validation, onboarding |
| **Sigmoid²+CaState** | `β = maxSecretion × σ²(BG) × Ca²⁺ × dt` | Physiological model with calcium memory |

---

### Impact on loop and APS calculation

#### APS pipeline (every 5-minute cycle)

```
invoke()
  ├─ IsfCalibrator  — rolling SD of bgReadings → dynamic ISF [isfMin, isfMax]
  └─ calcBetaSecretion()
       ├─ Guard 1: bg < hypoBg           → rate=0, smb=0  (absolute safety floor)
       ├─ Guard 2: bgIn30min < hypoAlert → rate=0, smb=0  (predictive safety)
       ├─ basalFactor gradient [0.0→1.0] — soft transition near hypo threshold
       ├─ braked = slope < slopeBrakeT   — partial attenuation on falling BG
       ├─ recentBolus (<3 min)           → SMB suspended  (IOB DB latency guard)
       └─ switch useNonLinear
            ├─ calcLinear()     → systemicInsulin = β × (1 − hepatic)
            └─ calcNonLinear()  → caState memory + dynamic hepaticEffective
```

#### IOB calculation

- Uses **native AAPS IOB** (`calculateIobFromBolus` + `calculateIobFromTempBasals`)
  with full DIA curve support — no custom IOB model in the loop path.
- `IobTotal.lastBolusTime` (field updated in `calculateIobFromBolusToTime`) is
  used to detect recent manual boluses and suspend SMB for 3 minutes, covering
  the DB write latency window.
- `IobCalculatorBeta` (exponential τ=90 min) exists as a reference/debug class
  but is **not** used in the active loop path.

#### SMB conditions (6 guards, both modes)

```kotlin
smbAllowed = smbEnabled
    && !recentBolus          // manual bolus < 3 min ago → DB not yet written
    && bg > targetBg + smbOffset
    && bg < hyperBg          // no SMB during severe hyperglycaemia
    && bgIn30min > hypoAlert // 30-min projection safe
    && iobTotal < smbMax * 3.0
```

#### Safety design

| Mechanism | Protection |
|-----------|-----------|
| `hypoBg.coerceAtLeast(55.0)` | Hard floor — cannot be set below 55 mg/dL |
| Guard 1 (absolute hypo) | Immediate rate=0, smb=0 regardless of other params |
| Guard 2 (predictive hypo) | bgIn30 + IOB + rapid slope OR condition |
| `openLoopOnly = true` (default) | Decisions logged but never applied to pump |
| `recentBolus` (<3 min) | SMB suspended during IOB DB write latency window |
| `bg < hyperBg` in smbAllowed | No SMB stacking during severe hyperglycaemia |
| `basalFactor` gradient | Smooth 0→100% basal near hypo threshold (not binary) |

#### Files modified in existing AAPS code

| File | Change | Impact |
|------|--------|--------|
| `core/keys/BooleanKey.kt` | +4 BetaCell entries | New preference keys only — no existing key affected |
| `core/keys/DoubleKey.kt` | +16 BetaCell entries | New preference keys only — no existing key affected |
| `core/keys/IntKey.kt` | +1 BetaCell entry | New preference key only |
| `plugins/aps/di/ApsModule.kt` | +1 Fragment binding | Adds BetaCellFragment to injection graph — no existing binding changed |
| `app/di/PluginsListModule.kt` | +1 plugin binding `@IntKey(222)` | Registers plugin — uses unused key 222, no conflict |

All new keys use the `betacell_` prefix — no collision with existing preferences.
The plugin is disabled by default and set to open-loop-only mode.

---

### New files

| File | Description |
|------|-------------|
| `BetaCellPlugin.kt` | Main APS plugin — invoke(), calcBetaSecretion(), calcLinear(), calcNonLinear() |
| `BetaCellApsResult.kt` | APSResult implementation with β-cell specific fields |
| `BetaCellPrefs.kt` | Immutable preference snapshot per cycle |
| `BetaCellFragment.kt` | UI fragment (OpenAPSFragment subclass) |
| `IsfCalibrator.kt` | Rolling SD → dynamic ISF calibration |
| `IobCalculatorBeta.kt` | Reference exponential IOB model (not in loop path) |
| `pref_betacell.xml` | Preference screen XML |
| `strings_betacell.xml` | String resources |
| `BetaCellPluginTest.kt` | 18 JUnit unit tests |

---

### How to test

```bash
# Unit tests
./gradlew :plugins:aps:test --tests "app.aaps.plugins.aps.betacell.*"

# Enable plugin in AAPS → Config Builder → APS → BetaCell
# Keep openLoopOnly = true during validation
# Monitor reason string in OpenAPS Fragment:
#   LINEAR  BG=145 tgt=110 ISF=42.3 slope=-0.30 BGin30=136 IOB=0.12U ...
#   SIGMOID2 BG=145 ... act=0.612 Ca=0.341 caDecay=0.85 hepEff=0.45 ...
```

---

### Checklist

- [x] Open loop only by default (`betacell_open_loop_only = true`)
- [x] No modification to existing APS algorithms
- [x] No modification to existing preference keys
- [x] IOB uses native AAPS calculation (not custom model)
- [x] SMB suspended 3 min after any bolus (DB latency guard)
- [x] Hard hypo floor at 55 mg/dL (`coerceAtLeast`)
- [x] Unit tests pass
- [ ] Clinical validation by healthcare professional before closed-loop use
