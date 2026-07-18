(ns hikari.cells.consumption-audit.cell
  "ConsumptionAuditCell — hikari R0 scaffold per ADR-2605261100.
  1:1 port of cells/consumption_audit/cell.py.

  R0 scaffold. Per-site + aggregate consumption monitoring + anomaly
  detection. G6 anti-surveillance: aggregate ≥1-hour buckets only;
  no smart-meter device PII (N7).
  .solve() raises at R0 (Council-gated R1 implementation).")

(defn solve
  [_input-state]
  (throw (ex-info (str "hikari R0 scaffold: consumption_audit cell not activated. "
                       "Requires ADR-2605261100 Council ratify + R2+ phase + aggregate-"
                       "only consumption reporting schema production (G6 anti-surveillance).")
                  {:scaffold true :cell :consumption-audit})))
