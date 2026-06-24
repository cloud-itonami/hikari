# hikari (光) — Energy Generation Tier-B Actor

**DID**: `did:web:etzhayyim.com:hikari`
**Namespace**: `com.etzhayyim.hikari.*`
**ADR**: ADR-2605261100 (R0 scaffold)
**Status**: R0 scaffold (2026-05-26) — all cells import-time RuntimeError
**Parent ADR**: ADR-2605261000 (Liberation Ladder — L2 Sustenance Tier gate)

## Overview

Distributed renewable energy actor — solar PV + small wind (≤100 kW per turbine) + geothermal micro (≤500 kW per well) + storage (battery + thermal) + grid-edge microgrid.

Powers L2 Sustenance Tier (≥3 kWh/day per adherent) + cross-cutting infrastructure (Murakumo fleet + mitate/yakushi/hagukumi facilities + mitsuho greenhouse + tatekata site + silicon Wave 2 fab partial load at R3).

## Constitutional Invariants (immutable)

- **G4**: **No nuclear at any tier ever** (fission/fusion/RTG). Council Lv7 unanimity to amend (essentially permanent).
- **G5**: **No fossil fuel at any tier ever** (coal/oil/gas/propane/peat). No fossil backup.
- **G8**: **No rare-earth permanent magnets** (NdFeB ban). Open-coil alternatives only; efficiency penalty constitutional trade-off.

## Robotics Classes

| Class | Role | Lineage | Notes |
|---|---|---|---|
| Otete | panel install + tracker service | kuni-umi | precision arm |
| Mimi | yield metrology + thermal-imaging fault detection | kuni-umi | metrology |
| Giemon | geothermal-micro drilling ≤500 m | kuni-umi | crawler + drill |
| Hizukue (日柄) (R2+) | autonomous panel-tracking + cleaning | new class | separate mech-design ADR (hanami precedent) |

## Pregel Cells (5, R0)

| Cell | Murakumo node | Phase | Input → Output |
|---|---|---|---|
| `solar_pv_install` | naphtali | site survey + panel install + commissioning | parcelDid, panelManifest → installAttestation |
| `storage_battery` | levi | battery + BMS + safety attestation | parcelDid, batteryManifest → batteryInstallAttestation |
| `grid_edge` | dan | microgrid controller + islandable inverter | siteId, loadProfile → gridEdgeStateRecord |
| `geothermal_micro` | zebulun | small-bore geothermal + heat-pump | parcelDid, geologicalSurvey → geothermalInstallAttestation |
| `consumption_audit` | levi | aggregate consumption + anomaly | siteId, billingPeriod → consumptionAuditRecord |

## Constitutional Gates (G1–G14)

See ADR-2605261100. **IMMUTABLE** per R0.

Key gates beyond constitutional invariants above:
- **G1**: Inverter + BMS + microgrid firmware open-source WASM/Rust
- **G2**: Panel sourcing Charter Rider §2(g) (no XUAR polysilicon, no conflict minerals)
- **G3**: Battery chemistry safety (LFP / NMC restricted / sodium-ion preferred; no lead-acid R2+; thermal runaway containment mandatory)
- **G6**: Generation + consumption transparency (aggregate ≥1-hour buckets; no smart-meter device surveillance)
- **G7**: ≥90% recyclable end-of-life
- **G9**: Land Trust integration (rooftop / brownfield / agrivoltaic priority; no greenfield habitat destruction)
- **G11**: Yield deterministic (Ed25519-signed per-inverter 15-min logs)
- **G13**: No commercial utility resale (surplus → local community-benefit credit only)
- **G14**: Charter Rider §2(h) light-pollution + acoustic audit

## Non-Goals (N1–N10)

- N1: No nuclear (any tier) — constitutional, Council Lv7 to amend
- N2: No fossil fuel (any tier)
- N3: No large hydroelectric (>10 MW; kuni-umi-adjacent if ever)
- N4: No biofuel from food crops
- N5: No offshore wind (Funamori marine-actor scope)
- N6: No commercial utility scale (>10 MW per site)
- N7: No smart-meter surveillance
- N8: No carbon offset trading
- N9: No rare-earth permanent magnets
- N10: No proprietary inverter firmware

## Roadmap

| Phase | Timeline | Scope | L-gate |
|---|---|---|---|
| **R0** | 2026-05-26 | Scaffold | — |
| **R1** | post-Council | Benchtop: single LANDS parcel + ≤10 kW solar + ≤30 kWh battery + islanded | future ADR |
| **R2** | post-R1 | Pilot: ≤100 kW solar + ≤500 kWh battery + grid-tie (export-to-religious-corp-load only) + first geothermal-micro well. ≥3 kWh/day × 1,000 adherents = ~170 kW + 500 kWh storage. | **L2 eligibility** |
| **R3** | post-R2 | Multi-site mesh + silicon Wave 2 fab partial load + L4-L5 ceiling | **L2→L3 + cross-actor** |

## Energy Budget (R2 target)

- L2 adherent baseline: 1,000 × 3 kWh/day = 3,000 kWh/day = ~125 kW continuous + 4-hr storage
- mitsuho R2 greenhouse + cold-store: ~50 kW continuous
- mitate R1 + yakushi R2 + tatekata R0 facility: ~20 kW continuous
- **R2 target**: ≥170 kW continuous + 500 kWh storage

R3 must scale to silicon Wave 2 fab partial load (~2 MW continuous typical; religious-corp side may batch-operate at lower duty cycle to fit hikari R3 capacity).

## Lexicons (5, deferred to R1+)

```
com.etzhayyim.hikari.{
  parcelEnergyAttestation       # resource baseline + biodiversity-no-harm
  installAttestation            # vendor + sourcing Charter Rider §2(g) audit
  generationRecord              # aggregate-only; no per-adherent device PII
  consumptionAuditRecord
  silenEnergyReview             # chemistry safety + sourcing + biodiversity
}
```

## Integration

- **Parent actor**: kuni-umi (robotics class lineage + multi-utility R3 coupling)
- **Load consumers**: Murakumo fleet, mitate/yakushi/hagukumi/manabi facilities, mitsuho greenhouse, tatekata site, silicon Wave 2 fab (R3+)
- **Land**: LANDS.md parcel (rooftop / brownfield / agrivoltaic) required for R1+

## References

- `/90-docs/adr/2605261000-labor-liberation-transition-mechanism.md` — L2 gate
- `/90-docs/adr/2605261100-hikari-energy-tier-b-actor-r0.md` — Master ADR
- `/90-docs/adr/2605192245-etzhayyim-global-land-sovereignty.md` — Land Trust
- `/90-docs/adr/2605242500-baien-iwakura-ternary-asic.md` — silicon Wave 1 (fab load)
- `/CLAUDE.md` — Religious-corp status table
