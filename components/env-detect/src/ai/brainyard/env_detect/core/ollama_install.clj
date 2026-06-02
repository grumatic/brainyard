;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.env-detect.core.ollama-install
  "Install / start / pull helpers for the Ollama rung of the bootstrap ladder.
   Side-effecting; callers must confirm with the user before invoking these.
   `install-instructions` is pure and safe to call any time."
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io BufferedReader InputStreamReader]
           [java.net HttpURLConnection URL]))

(defn recommended-default-model
  "Centralised default model for rung (e). Changing this updates both the
   bootstrap ladder and the docs that quote it."
  []
  "glm-4.5-air")

(defn cloud-fallback-model
  "Disk-free fallback when local pull is too heavy. Requires `ollama signin`."
  []
  "glm-5:cloud")

;; ============================================================================
;; OS detection helpers (kept local — providers.clj's `which` is private)
;; ============================================================================

(defn- which [binary]
  (try
    (let [proc (.start (ProcessBuilder. ^"[Ljava.lang.String;" (into-array String ["which" binary])))
          out  (str/trim (slurp (.getInputStream proc)))
          exit (.waitFor proc)]
      (when (and (zero? exit) (not (str/blank? out)))
        out))
    (catch Exception _ nil)))

(defn- detect-linux-distro
  "Parse /etc/os-release for the `ID=` line. Returns kw like :ubuntu, :debian,
   :fedora, :arch, or :unknown."
  []
  (let [f (io/file "/etc/os-release")]
    (if (.exists f)
      (let [content (slurp f)
            id-match (re-find #"(?m)^ID=\"?([^\"\n]+)\"?$" content)]
        (if id-match
          (keyword (str/lower-case (second id-match)))
          :unknown))
      :unknown)))

;; ============================================================================
;; install-instructions — pure, OS-aware
;; ============================================================================

(defn install-instructions
  "Return per-OS guidance for installing Ollama. Pure function.
   `os-info` is the map returned by env-detect/detect-os.

   Returns
   {:command           str-or-nil   ;; runnable command if auto-installable
    :url               str          ;; manual download / docs URL
    :size-mb           int-or-nil   ;; rough installer size
    :note              str          ;; copy shown to the user
    :auto-installable? bool}        ;; whether the wizard can run :command"
  [{:keys [name] :as _os-info}]
  (let [os-name (some-> name str/lower-case)]
    (cond
      (and os-name (or (str/includes? os-name "mac") (str/includes? os-name "darwin")))
      (let [brew? (some? (which "brew"))]
        (if brew?
          {:command           "brew install ollama"
           :url               "https://ollama.com/download"
           :size-mb           500
           :note              "Homebrew install (~500 MB). Wizard will run it on confirmation."
           :auto-installable? true}
          {:command           nil
           :url               "https://ollama.com/download/Ollama-darwin.zip"
           :size-mb           500
           :note              (str "No Homebrew detected. Download the .zip from the URL, "
                                   "install Ollama.app, then re-run `bb tui config`.")
           :auto-installable? false}))

      (and os-name (str/includes? os-name "linux"))
      (let [distro (detect-linux-distro)]
        {:command           nil
         :url               "https://ollama.com/install.sh"
         :size-mb           700
         :note              (str "Detected Linux (" (clojure.core/name distro) "). "
                                 "Review and run:\n"
                                 "  curl -fsSL https://ollama.com/install.sh -o /tmp/ollama-install.sh\n"
                                 "  less /tmp/ollama-install.sh   # review first\n"
                                 "  sh /tmp/ollama-install.sh\n"
                                 "Wizard will not pipe curl to sh without explicit consent.")
         :auto-installable? false})

      :else
      {:command           nil
       :url               "https://ollama.com/download"
       :size-mb           nil
       :note              (str "OS \"" name "\" is not supported for automated install. "
                               "Visit the URL for instructions.")
       :auto-installable? false})))

;; ============================================================================
;; install-ollama! — only on auto-installable OS, after user confirmation
;; ============================================================================

(defn install-ollama!
  "Run the install command returned by `install-instructions` (e.g. `brew install ollama`).
   Inherits stdio so the user sees package-manager progress.
   Returns {:ok? :exit :duration-ms :detail}.
   Caller must confirm with the user first and check :auto-installable? in advance."
  [command]
  (let [start (System/currentTimeMillis)]
    (try
      (let [pb (doto (ProcessBuilder. ^java.util.List (str/split command #"\s+"))
                 (.inheritIO))
            proc (.start pb)
            exit (.waitFor proc)]
        {:ok?         (zero? exit)
         :exit        exit
         :duration-ms (- (System/currentTimeMillis) start)
         :detail      (if (zero? exit)
                        (str "ran: " command)
                        (str "exited " exit ": " command))})
      (catch Exception e
        {:ok?         false
         :exit        -1
         :duration-ms (- (System/currentTimeMillis) start)
         :detail      (str "install failed: " (.getMessage e))}))))

;; ============================================================================
;; start-daemon! — backgrounds `ollama serve`, polls for readiness
;; ============================================================================

(defn- daemon-reachable?
  "Quick reachability probe — 500ms timeout for polling."
  []
  (try
    (let [conn (doto ^HttpURLConnection (.openConnection (URL. "http://localhost:11434"))
                 (.setConnectTimeout 500)
                 (.setReadTimeout 500)
                 (.setRequestMethod "GET"))
          code (.getResponseCode conn)]
      (.disconnect conn)
      (= 200 code))
    (catch Exception _ false)))

(defn start-daemon!
  "Start the ollama daemon in the background and poll until it answers.
   Uses `nohup ollama serve` via /bin/sh so the daemon outlives the wizard process.
   Returns {:ok? :elapsed-ms :detail}.
   Polls for up to 10 seconds (per design §5.1 step 2)."
  []
  (let [start (System/currentTimeMillis)]
    (if (daemon-reachable?)
      {:ok? true :elapsed-ms 0 :detail "ollama daemon already running"}
      (try
        (let [pb (ProcessBuilder. ^"[Ljava.lang.String;" (into-array String ["/bin/sh" "-c" "nohup ollama serve >/dev/null 2>&1 &"]))
              proc (.start pb)]
          (.waitFor proc))
        (loop [waited 0]
          (cond
            (daemon-reachable?)
            {:ok? true
             :elapsed-ms (- (System/currentTimeMillis) start)
             :detail "ollama daemon started"}

            (>= waited 10000)
            {:ok? false
             :elapsed-ms waited
             :detail "ollama daemon did not respond within 10s"}

            :else
            (do (Thread/sleep 500)
                (recur (+ waited 500)))))
        (catch Exception e
          {:ok?         false
           :elapsed-ms  (- (System/currentTimeMillis) start)
           :detail      (str "failed to start daemon: " (.getMessage e))})))))

;; ============================================================================
;; pull-model! — runs `ollama pull <model>`, streams progress
;; ============================================================================

(defn- parse-percent
  "Pick a 0-100 percent value out of an `ollama pull` progress line, or nil."
  [^String line]
  (when-let [[_ pct] (re-find #"\b(\d{1,3})\s*%" line)]
    (try
      (let [n (Integer/parseInt pct)]
        (when (<= 0 n 100) n))
      (catch Exception _ nil))))

(defn pull-model!
  "Run `ollama pull <model>`, streaming progress segments to `on-progress`.

   `on-progress` is called with a map per output segment:
     {:line str :percent int-or-nil :phase :downloading}
   and once at completion with {:line \"\" :percent 100 :phase :done}.

   `ollama pull` uses both \\r (in-place progress updates) and \\n (phase
   transitions). This fn splits on either, so the caller sees one event per
   visible line.

   Returns {:ok? :model :duration-ms :detail}."
  [model on-progress]
  (let [start (System/currentTimeMillis)
        on-progress (or on-progress (constantly nil))]
    (try
      (let [pb   (doto (ProcessBuilder. ^"[Ljava.lang.String;" (into-array String ["ollama" "pull" model]))
                   (.redirectErrorStream true))
            proc (.start pb)
            in   (BufferedReader. (InputStreamReader. (.getInputStream proc)))]
        (loop [sb (StringBuilder.)]
          (let [c (.read in)]
            (cond
              (= c -1)
              (let [line (str sb)]
                (when-not (str/blank? line)
                  (on-progress {:line line :percent (parse-percent line) :phase :downloading})))

              (or (= c (int \newline)) (= c (int \return)))
              (let [line (str sb)]
                (when-not (str/blank? line)
                  (on-progress {:line line :percent (parse-percent line) :phase :downloading}))
                (recur (StringBuilder.)))

              :else
              (do (.append sb (char c))
                  (recur sb)))))
        (let [exit (.waitFor proc)]
          (on-progress {:line "" :percent 100 :phase :done})
          {:ok?         (zero? exit)
           :model       model
           :duration-ms (- (System/currentTimeMillis) start)
           :detail      (if (zero? exit)
                          (str "pulled " model)
                          (str "ollama pull exited " exit))}))
      (catch Exception e
        {:ok?         false
         :model       model
         :duration-ms (- (System/currentTimeMillis) start)
         :detail      (str "pull failed: " (.getMessage e))}))))

;; ============================================================================
;; signin! — for the glm-5:cloud option
;; ============================================================================

(defn signin!
  "Run `ollama signin` with inherited stdio so the user can complete the flow
   (likely a printed URL + browser auth). Returns exit code; 0 means signed in."
  []
  (try
    (let [pb   (doto (ProcessBuilder. ^"[Ljava.lang.String;" (into-array String ["ollama" "signin"])) (.inheritIO))
          proc (.start pb)]
      (.waitFor proc))
    (catch Exception _ 1)))
