;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.schedule
  "First-class scheduler (R2 — docs/design/hermes-comparison.md).

   Persists cron-or-`fire-at` job specs under
   `<project>/.brainyard/schedule/<id>/spec.edn` (atomic writes, mirroring the
   `task` subsystem), and runs due jobs in-process while a `by` session is open
   via a daemon ticker thread. Each fired job runs its stored prompt through the
   configured agent and routes the output to a delivery sink (file artifact +
   stdout for now; pluggable so R3's channels slot in later).

   Surfaces:
   - `schedule$add/list/remove/enable/disable/run-now/run-due` commands.
   - `ensure-scheduler!` — starts the daemon ticker once per process (gated by
     `:enable-scheduler`, default false; opt-in so no background LLM spend
     happens by surprise). `run-due` is also callable directly.

   The executor is a pluggable seam (`*execute-job*`) so the orchestration
   (store + cron + due-selection + delivery) is fully testable without an LLM."
  (:require [ai.brainyard.agent.core.config :as config]
            [ai.brainyard.agent.core.tool :as tool :refer [defcommand]]
            [ai.brainyard.mulog.interface :as mulog]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io File]
           [java.time Instant ZoneId ZonedDateTime]))

;; ============================================================================
;; Cron engine — 5-field (min hour day-of-month month day-of-week)
;; ============================================================================

(def ^:private field-bounds
  {:min [0 59] :hour [0 23] :dom [1 31] :mon [1 12] :dow [0 6]})

(defn- parse-part
  "Parse one comma-free cron token for a field bounded [lo hi] into a set."
  [token lo hi]
  (cond
    (= "*" token) (set (range lo (inc hi)))
    (str/starts-with? token "*/")
    (let [step (parse-long (subs token 2))]
      (when (and step (pos? step)) (set (range lo (inc hi) step))))
    (str/includes? token "-")
    (let [[a b] (map parse-long (str/split token #"-" 2))]
      (when (and a b (<= a b)) (set (range a (inc b)))))
    :else (when-let [n (parse-long token)] #{n})))

(defn- parse-field [s lo hi]
  (let [parts (map #(parse-part % lo hi) (str/split s #","))]
    (when (every? some? parts)
      (let [u (reduce into #{} parts)]
        ;; cron dow: 7 is also Sunday (0)
        (when (every? #(<= lo % (if (= [lo hi] [0 6]) 7 hi)) u)
          (into #{} (map #(if (and (= [lo hi] [0 6]) (= 7 %)) 0 %)) u))))))

(defn parse-cron
  "Parse a 5-field cron string into {:min :hour :dom :mon :dow} (each a set), or
   nil when malformed."
  [s]
  (let [fields (when (string? s) (str/split (str/trim s) #"\s+"))]
    (when (= 5 (count fields))
      (let [[mn hr dom mon dow] fields
            m (parse-field mn 0 59)
            h (parse-field hr 0 23)
            d (parse-field dom 1 31)
            mo (parse-field mon 1 12)
            w (parse-field dow 0 6)]
        (when (every? some? [m h d mo w])
          {:min m :hour h :dom d :mon mo :dow w})))))

(defn- epoch->zdt ^ZonedDateTime [ms]
  (ZonedDateTime/ofInstant (Instant/ofEpochMilli ms) (ZoneId/systemDefault)))

(defn matches-cron?
  "True when the cron map matches the wall-clock minute of `zdt`. Uses AND
   semantics across all fields (including dom/dow — predictable; the
   standard's dom-OR-dow quirk is intentionally not modeled)."
  [cron ^ZonedDateTime zdt]
  (and (contains? (:min cron) (.getMinute zdt))
       (contains? (:hour cron) (.getHour zdt))
       (contains? (:mon cron) (.getMonthValue zdt))
       (contains? (:dom cron) (.getDayOfMonth zdt))
       (contains? (:dow cron) (mod (.getValue (.getDayOfWeek zdt)) 7))))

(defn next-fire-after
  "Epoch-ms of the next minute strictly after `from-ms` that matches `cron`, or
   nil if none within ~366 days (impossible spec)."
  [cron from-ms]
  (let [start (-> (epoch->zdt (+ from-ms 60000)) (.withSecond 0) (.withNano 0))]
    (loop [zdt start n 0]
      (cond
        (> n (* 366 1440)) nil
        (matches-cron? cron zdt) (.toEpochMilli (.toInstant zdt))
        :else (recur (.plusMinutes zdt 1) (inc n))))))

;; ============================================================================
;; Store — .brainyard/schedule/<id>/spec.edn (atomic)
;; ============================================================================

(defn ^File schedule-root [project-dir]
  (io/file (str project-dir) ".brainyard" "schedule"))

(defn ^File spec-dir [project-dir id]
  (io/file (schedule-root project-dir) id))

(defn- ^File spec-file [project-dir id]
  (io/file (spec-dir project-dir id) "spec.edn"))

(def ^:private id-re #"^[a-z0-9][a-z0-9-]*$")
(defn valid-id? [id] (boolean (and (string? id) (re-matches id-re id))))

(defn write-spec!
  "Atomically write a spec map (must carry :id). Returns the spec."
  [project-dir {:keys [id] :as spec}]
  (when-not (valid-id? id)
    (throw (ex-info (str "invalid schedule id: " (pr-str id)) {:id id})))
  (let [^File dir (spec-dir project-dir id)]
    (.mkdirs dir)
    (let [tmp (io/file dir "spec.edn.tmp")
          dst (io/file dir "spec.edn")]
      (spit tmp (pr-str spec))
      (.renameTo tmp dst))
    spec))

(defn read-spec [project-dir id]
  (let [^File f (spec-file project-dir id)]
    (when (.exists f)
      (try (edn/read-string (slurp f))
           (catch Exception e (mulog/warn ::read-spec-failed :id id :exception e) nil)))))

(defn list-specs [project-dir]
  (let [^File root (schedule-root project-dir)]
    (if-not (.isDirectory root)
      []
      (->> (.listFiles root)
           (filter #(.isDirectory ^File %))
           (keep #(read-spec project-dir (.getName ^File %)))
           (sort-by :created)
           vec))))

(defn delete-spec! [project-dir id]
  (when (valid-id? id)
    (let [^File dir (spec-dir project-dir id)]
      (when (.isDirectory dir)
        (doseq [^File f (file-seq dir) :when (.isFile f)] (.delete f))
        (doseq [^File f (reverse (file-seq dir))] (.delete f))
        true))))

;; ============================================================================
;; Delivery sinks
;; ============================================================================

(defn deliver-output!
  "Route a job's output to its sink. :file (default) writes
   .brainyard/schedule/<id>/runs/<ts>.log and returns the path; :stdout also
   prints. Returns the run-log path (or nil for stdout-only)."
  [project-dir spec output now-ms]
  (let [sink (or (some-> (:sink spec) keyword) :file)
        ^File runs (io/file (spec-dir project-dir (:id spec)) "runs")
        path (str (io/file runs (str now-ms ".log")))]
    (when (#{:file :stdout} sink)
      (.mkdirs runs)
      (spit path (str output "\n")))
    (when (= :stdout sink)
      (println output))
    path))

;; ============================================================================
;; Executor (pluggable seam)
;; ============================================================================

(defn default-execute-job
  "Run a spec's prompt through its configured agent in-process. Returns
   {:answer s} or {:error s}. Mirrors the memory essence-capture hook: a bare
   `invoke-tool` with an explicit `:agent-session` (no current agent bound on
   the ticker thread)."
  [spec]
  (try
    (let [agent-kw (keyword (or (not-empty (str (:agent spec))) "coact-agent"))
          uid      (or (config/get-config :user-id) "by-user")
          sid      (str "schedule-" (:id spec))
          raw      (tool/invoke-tool agent-kw
                                     :question (str (:prompt spec))
                                     :agent-session {:user-id uid :session-id sid}
                                     :auto-close? true)
          out      (tool/resolve-agent-ref raw)]
      {:answer (cond (map? out)    (or (:answer out) (pr-str out))
                     (string? out) out
                     :else         (pr-str out))})
    (catch Exception e
      (mulog/warn ::execute-job-failed :id (:id spec) :exception e)
      {:error (.getMessage e)})))

(def ^:dynamic *execute-job*
  "Job executor — `(fn [spec] -> {:answer s} | {:error s})`. Rebind in tests."
  default-execute-job)

;; ============================================================================
;; Firing
;; ============================================================================

(defn- advance-spec
  "Compute a spec's post-run state: recompute :next-fire for cron jobs; disable
   one-shot :fire-at jobs after they run."
  [spec now-ms]
  (cond
    (:cron spec)
    (assoc spec :next-fire (some-> (parse-cron (:cron spec)) (next-fire-after now-ms)))
    (:fire-at spec)
    (assoc spec :enabled false :next-fire nil)
    :else spec))

(defn run-spec!
  "Execute one spec now, deliver output, persist run state. Returns the updated
   spec. `claim?` advances :next-fire BEFORE executing (so a concurrent ticker
   is less likely to double-fire); run-now passes false."
  [project-dir spec now-ms claim?]
  (let [claimed (cond-> (assoc spec :last-run now-ms :last-status :running)
                  claim? (advance-spec now-ms))]
    (when claim? (write-spec! project-dir claimed))
    (let [result (*execute-job* spec)
          output (or (:answer result) (str "ERROR: " (:error result)))
          path   (deliver-output! project-dir spec output now-ms)
          ;; `claimed` already advanced :next-fire iff claim? — run-now
          ;; (claim?=false) deliberately leaves the schedule untouched.
          final  (assoc claimed
                        :last-status (if (:error result) :error :ok)
                        :last-output path)]
      (write-spec! project-dir final)
      (mulog/log ::ran :id (:id spec) :status (:last-status final))
      final)))

(defn due?
  "True when an enabled spec is due at `now-ms`."
  [spec now-ms]
  (and (:enabled spec)
       (number? (:next-fire spec))
       (<= (:next-fire spec) now-ms)))

(defn run-due!
  "Run every due spec under `project-dir` as of `now-ms`. Returns
   {:fired [ids] :count n}."
  [project-dir now-ms]
  (let [fired (->> (list-specs project-dir)
                   (filter #(due? % now-ms))
                   (mapv (fn [spec]
                           (run-spec! project-dir spec now-ms true)
                           (:id spec))))]
    {:fired fired :count (count fired)}))

;; ============================================================================
;; Ticker (in-process, daemon)
;; ============================================================================

(defonce ^:private !ticker (atom nil))

(defn ensure-scheduler!
  "Start the daemon ticker once per process (runtime-only; gated by
   `:enable-scheduler`). Captures the project-dir from `agent` so the thread
   doesn't depend on a bound current-agent. Safe to call every turn."
  [agent]
  (when (and agent (config/get-config agent :enable-scheduler))
    (when (compare-and-set! !ticker nil :starting)
      (let [pdir (config/project-dir agent)
            tick (long (or (config/get-config agent :scheduler-tick-ms) 60000))
            t    (Thread.
                  ^Runnable
                  (fn []
                    (loop []
                      (try
                        (Thread/sleep tick)
                        (run-due! pdir (System/currentTimeMillis))
                        (catch InterruptedException _ nil)
                        (catch Throwable e
                          (mulog/warn ::ticker-error :exception e)))
                      (recur)))
                  "by-scheduler")]
        (.setDaemon t true)
        (.start t)
        (reset! !ticker t)
        (mulog/info ::scheduler-started :project-dir pdir :tick-ms tick))))
  nil)

;; ============================================================================
;; Commands
;; ============================================================================

(defn- gen-id [title now-ms]
  (let [slug (-> (str (or (not-empty (str title)) "job"))
                 str/lower-case
                 (str/replace #"[^a-z0-9]+" "-")
                 (str/replace #"(^-+|-+$)" ""))
        slug (if (str/blank? slug) "job" slug)]
    (str (subs slug 0 (min 24 (count slug))) "-" now-ms)))

(defcommand schedule$add
  "Schedule a prompt to run unattended on a cron expression or one-shot time. Args: :prompt + (:cron | :at)."
  (fn [& {:keys [prompt cron at title agent model provider sink enabled]}]
    (let [now (System/currentTimeMillis)]
      (cond
        (str/blank? prompt) {:error ":prompt is required"}
        (and (str/blank? cron) (nil? at)) {:error "provide :cron \"m h dom mon dow\" or :at <epoch-ms>"}
        (and (not (str/blank? cron)) (nil? (parse-cron cron)))
        {:error (str "invalid :cron '" cron "' — expected 5 fields 'min hour day-of-month month day-of-week'")}
        :else
        (let [pdir (config/project-dir)
              id   (gen-id title now)
              next (if (not (str/blank? cron))
                     (next-fire-after (parse-cron cron) now)
                     (long at))
              spec (cond-> {:id id
                            :title (or (not-empty (str title)) prompt)
                            :prompt prompt
                            :agent (or (not-empty (str agent)) "coact-agent")
                            :sink (or (not-empty (str sink)) "file")
                            :enabled (if (some? enabled) (boolean enabled) true)
                            :next-fire next
                            :last-run nil :last-status nil
                            :created now}
                     (not (str/blank? cron)) (assoc :cron cron)
                     (nil? cron)             (assoc :fire-at (long at))
                     (not-empty (str model))    (assoc :model model)
                     (not-empty (str provider)) (assoc :provider provider))]
          (write-spec! pdir spec)
          (cond-> {:id id :next-fire next :enabled (:enabled spec)}
            (not (config/get-config :enable-scheduler))
            (assoc :note "Scheduler ticker is OFF — set :enable-scheduler true to fire unattended, or call (schedule$run-due) / (schedule$run-now :id …)."))))))
  :input-schema  [:map
                  [:prompt   [:string {:desc "The prompt to run at fire time"}]]
                  [:cron     {:optional true} [:string {:desc "5-field cron: 'min hour day-of-month month day-of-week' (e.g. '0 9 * * 1-5')"}]]
                  [:at       {:optional true} [:int {:desc "One-shot fire time, epoch-ms"}]]
                  [:title    {:optional true} [:string {:desc "Human label (also seeds the id)"}]]
                  [:agent    {:optional true} [:string {:desc "Agent id to run (default coact-agent)"}]]
                  [:model    {:optional true} [:string {:desc "Model override (stored)"}]]
                  [:provider {:optional true} [:string {:desc "Provider override (stored)"}]]
                  [:sink     {:optional true} [:string {:desc "Delivery sink: file (default) | stdout"}]]
                  [:enabled  {:optional true} [:boolean {:desc "Start enabled (default true)"}]]]
  :output-schema [:map
                  [:id        {:optional true} [:string {:desc "Schedule id"}]]
                  [:next-fire {:optional true} [:int {:desc "Next fire time, epoch-ms"}]]
                  [:enabled   {:optional true} [:boolean {:desc "Enabled?"}]]
                  [:note      {:optional true} [:string {:desc "Advisory note"}]]
                  [:error     {:optional true} [:string {:desc "Error if invalid"}]]])

(defcommand schedule$list
  "List scheduled jobs and their next fire time / last run status."
  (fn [& _]
    {:schedules (mapv #(select-keys % [:id :title :cron :fire-at :enabled
                                       :next-fire :last-run :last-status :sink])
                      (list-specs (config/project-dir)))})
  :input-schema  [:map]
  :output-schema [:map [:schedules [:vector {:desc "Schedule summaries"} :any]]])

(defcommand schedule$remove
  "Remove a scheduled job (and its run logs)."
  (fn [& {:keys [id]}]
    (if (delete-spec! (config/project-dir) id)
      {:removed id}
      {:error (str "no schedule '" id "'")}))
  :input-schema  [:map [:id [:string {:desc "Schedule id"}]]]
  :output-schema [:map
                  [:removed {:optional true} [:string {:desc "Removed id"}]]
                  [:error   {:optional true} [:string {:desc "Error if absent"}]]])

(defn- set-enabled! [id enabled?]
  (let [pdir (config/project-dir)]
    (if-let [spec (read-spec pdir id)]
      (let [now (System/currentTimeMillis)
            spec' (cond-> (assoc spec :enabled enabled?)
                    ;; recompute next-fire when (re)enabling a cron job
                    (and enabled? (:cron spec))
                    (assoc :next-fire (some-> (parse-cron (:cron spec)) (next-fire-after now))))]
        (write-spec! pdir spec')
        {:id id :enabled enabled? :next-fire (:next-fire spec')})
      {:error (str "no schedule '" id "'")})))

(defcommand schedule$enable
  "Enable a scheduled job (recomputes its next fire time)."
  (fn [& {:keys [id]}] (set-enabled! id true))
  :input-schema  [:map [:id [:string {:desc "Schedule id"}]]]
  :output-schema [:map [:id {:optional true} :string] [:enabled {:optional true} :boolean]
                  [:next-fire {:optional true} :int] [:error {:optional true} :string]])

(defcommand schedule$disable
  "Disable a scheduled job (keeps it; stops it firing)."
  (fn [& {:keys [id]}] (set-enabled! id false))
  :input-schema  [:map [:id [:string {:desc "Schedule id"}]]]
  :output-schema [:map [:id {:optional true} :string] [:enabled {:optional true} :boolean]
                  [:error {:optional true} :string]])

(defcommand schedule$run-now
  "Run a scheduled job immediately (ignores its schedule; does not advance next-fire)."
  (fn [& {:keys [id]}]
    (let [pdir (config/project-dir)]
      (if-let [spec (read-spec pdir id)]
        (let [final (run-spec! pdir spec (System/currentTimeMillis) false)]
          {:id id :status (:last-status final) :output (:last-output final)})
        {:error (str "no schedule '" id "'")})))
  :input-schema  [:map [:id [:string {:desc "Schedule id"}]]]
  :output-schema [:map
                  [:id     {:optional true} :string]
                  [:status {:optional true} [:any {:desc ":ok | :error"}]]
                  [:output {:optional true} [:string {:desc "Run-log path"}]]
                  [:error  {:optional true} :string]])

(defcommand schedule$run-due
  "Run every job currently due (the same pass the background ticker runs)."
  (fn [& _] (run-due! (config/project-dir) (System/currentTimeMillis)))
  :input-schema  [:map]
  :output-schema [:map
                  [:fired [:vector {:desc "Ids fired"} :string]]
                  [:count [:int {:desc "How many fired"}]]])

(def schedule-commands
  "Scheduler command family, bound into the common roster."
  [#'schedule$add #'schedule$list #'schedule$remove
   #'schedule$enable #'schedule$disable #'schedule$run-now #'schedule$run-due])
