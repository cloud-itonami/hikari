# hikari 光 — Maturity

**Stage: R0** (scaffold) — ADR-2605261100. Energy gen/storage/grid-edge actor (L2 Sustenance;
himawari feeds its PV modules). Renewable-only — no nuclear, no fossil, no rare-earth magnets.

| Dimension | State |
|---|---|
| Lexicons | ✅ 5 under `com.etzhayyim.hikari.*` (install/generation/consumptionAudit/parcelEnergy/silenEnergyReview) |
| Cells | 🟡 path-reserved (generation → storage → grid-edge, R0 import-time RuntimeError) |
| Manifest | ✅ `manifest.jsonld` — `constitutionalGates` (G1–G14) machine-readable |
| Tests | ✅ `nbb run_tests.cljk` — 13 suites under `test/`, **71 tests / 1374 assertions green** (2026-09-11); mutation-checked by the superproject `scripts/maturity-loop` |
| Methods | 🟡 offline engine = R1 |

## Charter gates pinned by the descriptor test

> **2026-09-11:** the charter-gate test described below was written against the etzhayyim
> monorepo's `00-contracts/lexicons/com/etzhayyim/hikari/*.json`. The lexicons in this
> standalone repo (`lexicons/**/*.json`, the projection of `lex/*.edn`) never carried the
> `knownValues` vocabulary it asserted, so ported as written it was 6 red of 7. It was
> removed; `test/hikari/descriptor_test.cljk` keeps the one assertion that holds here (the
> full G1–G14 gate set) and pins the seams this tree actually has (manifest.jsonld ↔
> manifest.edn ↔ cells/*.edn ↔ lex ↔ lexicons ↔ `hikari.murakumo` ↔ `hikari.methods.agent`).
> The bullets below are the record of what the monorepo-era test checked, not of what
> this repo's lexicons say today.

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

> **2026-06-17 substrate-native migration (ADR-2606160842):** the charter-gate test above was ported Python→Clojure (`methods/test_charter_gates.py` → `methods/test_charter_gates.cljc`, ns `hikari.methods.test-charter-gates`, reads the lexicons via cheshire/edn) and the Python was pruned. It ran via `./run_tests.sh` (`exec bb`) or `bb run test:charter` from the monorepo root.
>
> **2026-09-11 standalone runner:** neither path survived the 2026-07-18 standalone migration — `run_tests.sh` did `cd ../..` into the (absent) monorepo and bb stopped at `Could not locate hikari/methods/test_microgrid.bb ... on classpath` (exit 1, 0 tests), so the 11 cljc suites under `methods/` and `cells/` had not run once in this repo. They now live under `src/hikari/**` and `test/hikari/**` (where `deps.edn` `:paths` / `:test` already looked), the runner is `nbb run_tests.cljk` (0 tests → exit 2 REFUSED, never green), and `nbb.edn` carries the `kotoba.lang.text` coordinate copied from `deps.edn`.
