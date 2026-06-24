# hikari 光 — Maturity

**Stage: R0** (scaffold) — ADR-2605261100. Energy gen/storage/grid-edge actor (L2 Sustenance;
himawari feeds its PV modules). Renewable-only — no nuclear, no fossil, no rare-earth magnets.

| Dimension | State |
|---|---|
| Lexicons | ✅ 5 under `com.etzhayyim.hikari.*` (install/generation/consumptionAudit/parcelEnergy/silenEnergyReview) |
| Cells | 🟡 path-reserved (generation → storage → grid-edge, R0 import-time RuntimeError) |
| Manifest | ✅ `manifest.jsonld` — `constitutionalGates` (G1–G14) machine-readable |
| Tests | ✅ `methods/test_charter_gates.cljc` — **7 tests, green** (added 2026-06-17); `./run_tests.sh` |
| Methods | 🟡 offline engine = R1 |

## Charter gates pinned by the new charter-gate test

- **Full gate set** — manifest declares exactly G1–G14.
- **G4/G5 no nuclear/fossil** — `installAttestation.componentType` is exactly {solar-pv,
  battery-bank, inverter, wind-turbine, geothermal-well, heat-pump}; no fossil/nuclear/reactor
  component representable.
- **G8 no rare-earth magnets** — `magnetAttestation` ∈ {open-coil-electrically-excited,
  ferrite, none-not-applicable} (no NdFeB).
- **G3 battery chemistry** — `chemistryAttestation` ⊆ {LFP, NMC-restricted, sodium-ion, none-not-battery}.
- **G2 sourcing** — `installAttestation` requires `sourcingAuditCid` + `attestingEngineerDid` +
  `attestingRobots`.
- **G9 parcel** — `parcelEnergyAttestation` requires `biodiversityNoHarmAttestationCid` +
  `landsRegistryCid`; greenfield is the Council-attested parcel class.
- **generation provenance** — `generationRecord` requires `signingInverterDids`.

## R0 → R1 gate

silenEnergyReview + Council Lv6+ + ≥1 renewable-energy engineer on technical advisory + LANDS
parcel registered + battery-chemistry safety attestation; cells import-gated until then.

> **2026-06-17 substrate-native migration (ADR-2606160842):** the charter-gate test above was ported Python→Clojure (`methods/test_charter_gates.py` → `methods/test_charter_gates.cljc`, ns `hikari.methods.test-charter-gates`, reads the lexicons via cheshire/edn) and the Python was pruned. Run via `./run_tests.sh` (now `exec bb`) or `bb run test:charter` (all 34 charter suites; 244 tests / 924 assertions green). Assertions unchanged (1:1 port).
