(ns hikari.cells.consumption-audit.state-machine
  "cljc port of cells/consumption_audit/cell.py (ADR-2605261100).
  R0 scaffold — aggregate energy consumption + anomaly detection.
  .solve() raises until Council activation (G6 anti-surveillance:
  aggregate ≥1-hour buckets only; no smart-meter device PII N7).")

(defn solve [_state]
  (throw (ex-info "hikari R0 scaffold: consumption_audit cell not activated. Requires ADR-2605261100 Council ratify + R2+ phase + aggregate-only consumption reporting schema production (G6 anti-surveillance)."
                  {:cell :consumption-audit :actor :hikari :status :r0-scaffold})))
