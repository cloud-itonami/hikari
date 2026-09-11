(ns hikari.cells.storage-battery.state-machine
  "cljc port of cells/storage_battery/cell.py (ADR-2605261100).
  R0 scaffold — battery bank install + BMS config + safety attestation.
  G3 chemistry safety: LFP / NMC restricted / sodium-ion preferred;
  no lead-acid stationary R2+; thermal runaway containment mandatory.")

(defn solve [_state]
  (throw (ex-info "hikari R0 scaffold: storage_battery cell not activated. Requires ADR-2605261100 Council ratify + G3 battery chemistry safety attestation framework Council-ratified."
                  {:cell :storage-battery :actor :hikari :status :r0-scaffold})))
