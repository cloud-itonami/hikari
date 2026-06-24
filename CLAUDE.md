# 20-actors/hikari — CLAUDE.md

## Identity

- **Name**: hikari (光 — "light"; multi-generational solar abundance echo; sibling resonance to mitate via Yakushi Nyorai 日光菩薩)
- **DID**: `did:web:etzhayyim.com:hikari`
- **ADR**: ADR-2605261100 (R0 scaffold, 2026-05-26)
- **Parent ADR**: ADR-2605261000 (Liberation Ladder — L2 Sustenance Tier gate; cross-cutting infrastructure)
- **Status**: R0 scaffold — all cells import-time RuntimeError

## Architecture

5 Pregel cells, cross-cutting energy substrate:

```
solar_pv_install ────┐
storage_battery ─────┤
                     ├──→ grid_edge (microgrid controller) ──→ load consumers
geothermal_micro ────┤                                          (Murakumo / facilities / fab)
                     │
consumption_audit ←──┘
```

Each cell = 1 Pregel graph. Cross-cutting — hikari supplies L2 adherent baseline AND cross-actor facility power.

## Constitutional Invariants (CRITICAL — Council Lv7 unanimity floor)

These are not adjustable gates; they are constitutional structural prohibitions:

### G4: No nuclear at any tier ever
- Fission: PWR, BWR, SMR, Gen-IV — all prohibited
- Fusion: any approach — prohibited
- RTG (radioisotope thermoelectric) — prohibited
- Rationale: multi-generational waste invariant (10,000+ year stewardship inconsistent with §1.3 multi-gen + §1.11 land trust)
- Amendment: Council Lv7 unanimity (essentially permanent)

### G5: No fossil fuel at any tier ever
- Coal, oil, natural gas, propane, LPG, peat — all prohibited
- **No fossil backup generators.** Outage resilience via battery + thermal storage + multi-site mesh + load-shifting tolerance.
- Rationale: climate multi-gen + §2(c) no harmful substances
- Amendment: Council Lv7 unanimity

### G8: No rare-earth permanent magnets
- NdFeB (neodymium-iron-boron) magnets prohibited in wind turbines and motors
- Open-coil (electrically excited) alternatives only
- Efficiency penalty (~15-20% vs commercial NdFeB) accepted as constitutional trade-off
- Rationale: §2(g) supply-chain ethics (rare-earth extraction violates land/environment)

## Robotics Fleet

| Robot | Class | Function | Lineage |
|---|---|---|---|
| Otete | precision arm | panel install + tracker servicing | kuni-umi |
| Mimi | metrology | yield monitoring + thermal-imaging fault detect | kuni-umi |
| Giemon | crawler + drill | geothermal-micro drilling ≤500 m | kuni-umi |
| Hizukue (R2+) | tracker + cleaner | autonomous panel tracking + dust cleaning + thermal IR | new class; separate mech-design ADR |

## Energy Budget Coupling (cross-actor R2 target)

hikari R2 must demonstrate sufficient generation + storage for:
- L2 adherent baseline: 1,000 × 3 kWh/day = 3,000 kWh/day = ~125 kW continuous + 4-hr storage
- mitsuho R2 greenhouse + cold-store: ~50 kW continuous
- mitate R1 + yakushi R2 + tatekata R0 facility baseline: ~20 kW continuous

**R2 target: ≥170 kW continuous + 500 kWh storage.**

R3 must scale to silicon Wave 2 fab partial load (~2 MW continuous typical industry). Mitigation: silicon side batches at lower duty cycle, or hikari R3 expands to multi-site mesh.

## Lexicon Namespace

**App lexicon root**: `com.etzhayyim.hikari`

5 records (R0 stubs; full schemas R1+):

1. `parcelEnergyAttestation` — solar/wind/geothermal resource + biodiversity-no-harm baseline
2. `installAttestation` — vendor + sourcing Charter Rider §2(g) audit per lot
3. `generationRecord` — aggregate-only per-period generation (Ed25519 per-inverter 15-min)
4. `consumptionAuditRecord` — aggregate consumption (≥1-hour buckets; no smart-meter PII)
5. `silenEnergyReview` — Council attestation scope (chemistry + sourcing + biodiversity)

## Pregel Cells (R0 stub bodies)

All R0 cells raise `RuntimeError("hikari R0 scaffold: activate via Council ADR + ≥1 renewable-engineer on Council technical advisory + LANDS parcel registered")` on import.

### R1 activation triggers
1. ADR-2605261100 Council Lv6+ ratify
2. ≥1 renewable-energy engineer on Council technical advisory
3. ≥1 LANDS.md parcel registered (rooftop / brownfield / agrivoltaic priority)
4. Charter Rider §2(g) panel sourcing audit framework operational
5. Battery chemistry safety attestation framework Council-ratified (G3)

## Build & Deploy

**R0 status**: Scaffold only. All cells RuntimeError on import.

**Smoke test**:
```bash
cd 20-actors/hikari
python -c "import hikari.cells.solar_pv_install" 2>&1 | grep "R0 scaffold"
python -c "import hikari.cells.storage_battery" 2>&1 | grep "R0 scaffold"
python -c "import hikari.cells.grid_edge" 2>&1 | grep "R0 scaffold"
python -c "import hikari.cells.geothermal_micro" 2>&1 | grep "R0 scaffold"
python -c "import hikari.cells.consumption_audit" 2>&1 | grep "R0 scaffold"
```

## Related Files

- `/20-actors/hikari/manifest.jsonld`
- `/90-docs/adr/2605261100-hikari-energy-tier-b-actor-r0.md` — Master ADR
- `/90-docs/adr/2605261000-labor-liberation-transition-mechanism.md` — L2 gate
- `/90-docs/adr/2605192245-etzhayyim-global-land-sovereignty.md` — Land Trust
- `/20-actors/kuni-umi/README.md` — Robotics class lineage
- `/CLAUDE.md` — Religious-corp status table
