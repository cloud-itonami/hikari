(ns hikari.cells.geothermal-micro.cell
  "GeothermalMicroCell — hikari R0 scaffold per ADR-2605261100.
  1:1 port of cells/geothermal_micro/cell.py.

  R0 scaffold. Small-bore geothermal (≤500 m depth, ≤500 kW per well) +
  heat-pump integration. R2+ activation; provides 24h baseload complement to
  solar+battery. G9 land-trust biodiversity-no-harm + G14 acoustic audit.
  .solve() raises at R0 (Council-gated R1 implementation).")

(defn solve
  [_input-state]
  (throw (ex-info (str "hikari R0 scaffold: geothermal_micro cell not activated. "
                       "Requires ADR-2605261100 Council ratify + R2+ phase + geological "
                       "survey + biodiversity-no-harm attestation.")
                  {:scaffold true :cell :geothermal-micro})))
