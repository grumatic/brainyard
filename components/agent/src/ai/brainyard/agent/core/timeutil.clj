;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.core.timeutil
  "Pure wall-clock helpers behind the `time$*` tools (time$now / time$add /
   time$diff). No agent/registry deps so it stays trivially unit-testable.

   Canonical instant map — every instant-returning helper returns this exact
   shape so the LLM never has to guess field names across the `time` family:

     {:iso               \"2026-06-23T14:32:17.123+09:00\"  ;; ISO-8601 offset
      :epoch-ms          1750658537123
      :epoch-sec         1750658537
      :tz-iana           \"Asia/Seoul\"
      :tz-offset-minutes 540
      :day-of-week       \"Tuesday\"}

   All instants are computed at call time (never at top level) so nothing is
   baked into the GraalVM native image. Interop is fully type-hinted to avoid
   runtime reflection."
  (:require [clojure.string :as str])
  (:import (java.time Duration Instant LocalDate LocalDateTime
                      OffsetDateTime ZoneId ZonedDateTime)
           (java.time.format DateTimeFormatter)
           (java.time.temporal ChronoUnit)))

(def ^:private ^DateTimeFormatter iso-fmt DateTimeFormatter/ISO_OFFSET_DATE_TIME)
(def ^:private ^DateTimeFormatter dow-fmt (DateTimeFormatter/ofPattern "EEEE"))

;; ============================================================================
;; Zone + parsing
;; ============================================================================

(defn resolve-zone
  "Resolve an IANA zone string to a ZoneId. Blank/nil → system default.
   Throws (DateTimeException) on an unknown zone — callers convert to {:error}."
  ^ZoneId [tz]
  (if (or (nil? tz) (and (string? tz) (str/blank? tz)))
    (ZoneId/systemDefault)
    (ZoneId/of (str tz))))

(defn- parse-string-instant
  "Best-effort parse of an ISO-ish string into an Instant. Accepts, in order:
   an instant (...Z), an offset date-time, a zoneless date-time (interpreted in
   `zone`), and a bare date (start-of-day in `zone`)."
  ^Instant [^String s ^ZoneId zone]
  (let [s (str/trim s)]
    (or (try (Instant/parse s) (catch Exception _ nil))
        (try (.toInstant (OffsetDateTime/parse s)) (catch Exception _ nil))
        (try (.toInstant (.atZone (LocalDateTime/parse s) zone)) (catch Exception _ nil))
        (try (.toInstant (.atStartOfDay (LocalDate/parse s) zone)) (catch Exception _ nil))
        (throw (ex-info (str "Unparseable time string: " s) {:value s})))))

(defn ->instant
  "Coerce a time value to an Instant. nil → now; number → epoch millis;
   Instant → itself; string → ISO-ish (parsed via `zone` for zoneless forms)."
  ^Instant [x ^ZoneId zone]
  (cond
    (nil? x)             (Instant/now)
    (instance? Instant x) x
    (number? x)          (Instant/ofEpochMilli (long x))
    (string? x)          (parse-string-instant x zone)
    :else (throw (ex-info "Unsupported time value" {:value x}))))

;; ============================================================================
;; Canonical instant map
;; ============================================================================

(defn instant->map
  "Render an Instant as the canonical instant map (see ns docstring).
   `:iso` is truncated to whole seconds (sub-second precision is noise for the
   prompt/LLM); `:epoch-ms` keeps full millisecond precision for callers."
  [^Instant inst ^ZoneId zone]
  (let [zdt    (ZonedDateTime/ofInstant inst zone)
        offset (.getOffset zdt)]
    {:iso               (.format (.truncatedTo zdt ChronoUnit/SECONDS) iso-fmt)
     :epoch-ms          (.toEpochMilli inst)
     :epoch-sec         (.getEpochSecond inst)
     :tz-iana           (str zone)
     :tz-offset-minutes (long (quot (.getTotalSeconds offset) 60))
     :day-of-week       (.format zdt dow-fmt)}))

;; ============================================================================
;; Arithmetic + diff
;; ============================================================================

(defn- shift
  "Calendar-aware add of a delta map to an Instant, evaluated in `zone` so DST,
   month lengths and leap years are honored. Date units use calendar semantics,
   time units exact. Any field may be negative."
  ^Instant [^Instant inst ^ZoneId zone
            {:keys [years months weeks days hours minutes seconds]}]
  (-> (ZonedDateTime/ofInstant inst zone)
      (.plusYears   (long (or years 0)))
      (.plusMonths  (long (or months 0)))
      (.plusWeeks   (long (or weeks 0)))
      (.plusDays    (long (or days 0)))
      (.plusHours   (long (or hours 0)))
      (.plusMinutes (long (or minutes 0)))
      (.plusSeconds (long (or seconds 0)))
      (.toInstant)))

(defn- humanize-duration
  "Compact magnitude string for a Duration, e.g. \"2d 4h 15m\". \"0s\" when zero."
  [^Duration d]
  (let [d     (.abs d)
        days  (.toDays d)
        hrs   (.toHoursPart d)
        mins  (.toMinutesPart d)
        secs  (.toSecondsPart d)
        parts (cond-> []
                (pos? days) (conj (str days "d"))
                (pos? hrs)  (conj (str hrs "h"))
                (pos? mins) (conj (str mins "m"))
                (pos? secs) (conj (str secs "s")))]
    (if (seq parts) (str/join " " parts) "0s")))

(defn- calendar-breakdown
  "Calendar Y/M/D + H/M/S magnitude between two local date-times (lo <= hi)."
  [^LocalDateTime lo ^LocalDateTime hi]
  (let [y  (.between ChronoUnit/YEARS lo hi)   a1 (.plusYears lo y)
        mo (.between ChronoUnit/MONTHS a1 hi)  a2 (.plusMonths a1 mo)
        d  (.between ChronoUnit/DAYS a2 hi)    a3 (.plusDays a2 d)
        h  (.between ChronoUnit/HOURS a3 hi)   a4 (.plusHours a3 h)
        mi (.between ChronoUnit/MINUTES a4 hi) a5 (.plusMinutes a4 mi)
        s  (.between ChronoUnit/SECONDS a5 hi)]
    {:years y :months mo :days d :hours h :minutes mi :seconds s}))

(defn- diff
  "Duration map between two Instants. Totals and :ms are SIGNED (to − from);
   :direction is \"future\" when `to` is after `from`, \"past\" when before,
   \"same\" when equal. :humanized and :calendar are magnitudes. :from/:to are
   canonical instant maps so the shape composes with time$now / time$add."
  [^Instant from ^Instant to ^ZoneId zone]
  (let [signed (Duration/between from to)
        [a b]  (if (.isNegative signed) [to from] [from to])
        lo     (.toLocalDateTime (ZonedDateTime/ofInstant ^Instant a zone))
        hi     (.toLocalDateTime (ZonedDateTime/ofInstant ^Instant b zone))]
    {:from      (instant->map from zone)
     :to        (instant->map to zone)
     :direction (cond (.isZero signed) "same" (.isNegative signed) "past" :else "future")
     :ms        (.toMillis signed)
     :seconds   (.getSeconds signed)
     :minutes   (.toMinutes signed)
     :hours     (.toHours signed)
     :days      (.toDays signed)
     :humanized (humanize-duration signed)
     :calendar  (calendar-breakdown lo hi)}))

;; ============================================================================
;; Public, tool-facing (accept a tz string; nil/blank → system zone)
;; ============================================================================

(defn now-map
  "Canonical instant map for the current wall-clock, rendered in `tz`."
  [tz]
  (instant->map (Instant/now) (resolve-zone tz)))

(defn add-map
  "Canonical instant map for `base` (nil → now) shifted by `deltas` in `tz`."
  [base tz deltas]
  (let [zone (resolve-zone tz)]
    (instant->map (shift (->instant base zone) zone deltas) zone)))

(defn diff-map
  "Duration map between `from` and `to` (nil → now) computed in `tz`."
  [from to tz]
  (let [zone (resolve-zone tz)]
    (diff (->instant from zone) (->instant to zone) zone)))
