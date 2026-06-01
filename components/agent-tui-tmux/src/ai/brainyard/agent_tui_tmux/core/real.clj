;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.agent-tui-tmux.core.real
  "Real tmux backend — shells out to /usr/bin/tmux (or `:tmux-binary`)."
  (:require [ai.brainyard.agent-tui-tmux.core.protocol :as p]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]))

(def ^:private default-binary "tmux")

(defn- sh!
  "Invoke tmux with the given args.  Returns `{:exit :stdout :stderr}`."
  ([binary socket args]
   (let [args (concat (cond->> (vec args)
                        socket (concat ["-L" socket]))
                      [])
         {:keys [exit out err]} (apply shell/sh binary args)]
     {:exit exit :stdout (or out "") :stderr (or err "")})))

(defn- ok? [{:keys [exit]}] (zero? exit))

(defn- ensure-ok!
  [{:keys [exit stderr] :as r} subject]
  (when-not (zero? exit)
    (throw (ex-info (str "tmux " subject " failed: " (str/trim stderr))
                    {:subject subject :result r})))
  r)

(defn- parse-version
  "Tmux's `-V` prints something like `tmux 3.4`."
  [stdout]
  (when-let [[_ major minor] (re-find #"(\d+)\.(\d+)" (or stdout ""))]
    [(Long/parseLong major) (Long/parseLong minor)]))

(defn- key-segment
  "Render a single key to its tmux send-keys argument."
  [k]
  (cond
    (string? k)  k
    (keyword? k) (name k)
    (symbol? k)  (str k)
    :else        (str k)))

(defrecord RealTmux [binary socket]
  p/Tmux
  (version [_]
    (let [{:keys [exit stdout]} (sh! binary socket ["-V"])]
      (when (zero? exit) (str/trim stdout))))

  (probe-version [this]
    (parse-version (p/version this)))

  (running? [_]
    (ok? (sh! binary socket ["has-session"])))

  (new-session! [_ {:keys [name detached command width height]
                    :or {detached true}}]
    (let [args (cond-> ["new-session"]
                 detached  (conj "-d")
                 name      (concat ["-s" name])
                 width     (concat ["-x" (str width)])
                 height    (concat ["-y" (str height)])
                 command   (concat (if (sequential? command)
                                     command
                                     [command])))]
      (ensure-ok! (sh! binary socket args) "new-session")
      name))

  (kill-session! [_ name]
    (sh! binary socket ["kill-session" "-t" name]))

  (list-sessions [_]
    (let [{:keys [exit stdout]} (sh! binary socket
                                     ["list-sessions" "-F" "#{session_name}"])]
      (if (zero? exit)
        (vec (->> (str/split-lines stdout)
                  (map str/trim)
                  (remove str/blank?)))
        [])))

  (new-window! [_ {:keys [target name command]}]
    ;; `-a` inserts at the next free index after the current window in the
    ;; target session.  Without it, tmux interprets `-t <session>` as an
    ;; explicit index and fails with "index N in use" when that slot is
    ;; taken (e.g. by an orphaned window from a prior run).  `-P -F …`
    ;; asks tmux to print the new window's id so we can use it as a target
    ;; for subsequent splits.
    (let [args (cond-> ["new-window" "-d" "-a" "-t" target
                        "-P" "-F" "#{window_id}"]
                 name    (concat ["-n" name])
                 command (concat (if (sequential? command)
                                   command
                                   [command])))
          {:keys [exit stdout]} (sh! binary socket args)]
      (when-not (zero? exit)
        (ensure-ok! {:exit exit :stdout stdout :stderr stdout} "new-window"))
      (clojure.string/trim (or stdout ""))))

  (kill-window! [_ target]
    (sh! binary socket ["kill-window" "-t" target]))

  (kill-pane! [_ target]
    (sh! binary socket ["kill-pane" "-t" target]))

  (rename-window! [_ target new-name]
    (sh! binary socket ["rename-window" "-t" target new-name]))

  (split-pane! [_ {:keys [target orientation percentage size command]
                   :or {orientation :v}}]
    ;; All tmux flags must come BEFORE the shell-command argument; tmux's
    ;; getopt parser stops at the first non-flag argument and treats
    ;; everything that follows as the command + its args.  Putting `-P -F`
    ;; after the command made tmux pass them to the command (e.g. cat) and
    ;; the split silently no-op'd from tmux's perspective on pane-id output.
    ;;
    ;; `-l <N>` sets the new pane to an absolute size (rows for vertical
    ;; splits, cols for horizontal).  When `:size` is given, it wins over
    ;; `:percentage` so callers don't accidentally use both.
    (let [orient-flag (if (= orientation :h) "-h" "-v")
          flag-args (cond-> ["split-window" "-d" orient-flag "-t" target
                             "-P" "-F" "#{pane_id}"]
                      size       (concat ["-l" (str size)])
                      (and (nil? size) percentage)
                      (concat ["-p" (str percentage)]))
          full-args (cond-> (vec flag-args)
                      command (concat (if (sequential? command)
                                        command
                                        [command])))
          result (sh! binary socket full-args)
          ;; Diagnostic — when env var BY_TMUX_DEBUG is set, log every
          ;; split-pane! invocation + the resulting pane id and any
          ;; stderr.  Used to root-cause silent split failures.
          _ (when (System/getenv "BY_TMUX_DEBUG")
              (binding [*out* *err*]
                (println (str "[BY_TMUX_DEBUG] split-pane args="
                              (pr-str (vec full-args))
                              " exit=" (:exit result)
                              " stdout=" (pr-str (:stdout result))
                              " stderr=" (pr-str (:stderr result))))))]
      (ensure-ok! result "split-pane")
      (str/trim (:stdout result))))

  (resize-pane! [_ {:keys [target width height]}]
    (let [args (cond-> ["resize-pane" "-t" target]
                 width  (concat ["-x" (str width)])
                 height (concat ["-y" (str height)]))]
      (sh! binary socket args)))

  (select-pane! [_ target]
    (sh! binary socket ["select-pane" "-t" target]))

  (select-window! [_ target]
    (sh! binary socket ["select-window" "-t" target]))

  (send-keys! [_ {:keys [target keys literal?]}]
    (let [args (cond-> ["send-keys" "-t" target]
                 literal? (conj "-l"))]
      (sh! binary socket (concat args (mapv key-segment keys)))))

  (pipe-pane! [_ {:keys [target path start? open]
                  :or {start? true}}]
    (let [args (cond-> ["pipe-pane" "-t" target]
                 (and start? open) (conj "-O")
                 (not start?)      (conj "-O" "")) ;; toggle off
          cmd-arg (when (and start? path)
                    (str "cat >> " (.getAbsolutePath (io/file path))))
          args (cond-> args cmd-arg (concat [cmd-arg]))]
      (sh! binary socket args)))

  (capture-pane [_ {:keys [target start end ansi?]
                    :or {ansi? true}}]
    (let [args (cond-> ["capture-pane" "-p" "-t" target]
                 ansi?  (conj "-e")
                 start  (concat ["-S" (str start)])
                 end    (concat ["-E" (str end)]))
          {:keys [exit stdout]} (sh! binary socket args)]
      (when (zero? exit) stdout)))

  (display-popup! [_ {:keys [command width height title border env close-on-exit?]
                      :or {close-on-exit? true}}]
    (let [args (cond-> ["display-popup"]
                 close-on-exit? (conj "-E")
                 width   (concat ["-w" (str width)])
                 height  (concat ["-h" (str height)])
                 title   (concat ["-T" title])
                 border  (concat ["-b" (name border)])
                 env     (concat (mapcat (fn [[k v]] ["-e" (str (name k) "=" v)]) env)))
          args (concat args [command])]
      (:exit (sh! binary socket args))))

  (set-option! [_ {:keys [name value target scope]
                   :or {scope :global}}]
    (let [args (cond-> ["set-option"]
                 (= scope :global)  (conj "-g")
                 (= scope :server)  (conj "-s")
                 (= scope :window)  (conj "-w")
                 (= scope :pane)    (conj "-p")
                 target (concat ["-t" target]))]
      (sh! binary socket (concat args [name (str value)]))))

  (display-message [_ {:keys [format target]}]
    (let [args (cond-> ["display-message" "-p"]
                 target (concat ["-t" target])
                 format (concat [format]))
          {:keys [stdout]} (sh! binary socket args)]
      (str/trim (or stdout ""))))

  (signal! [_ name]
    (sh! binary socket ["wait-for" "-S" (str name)]))

  (run-shell [_ {:keys [args]}]
    (sh! binary socket args)))

(defn create
  "Construct a RealTmux backend.  Options:
     :binary — path to tmux (default \"tmux\")
     :socket — `-L` socket name for isolating tests/CI"
  ([] (create {}))
  ([{:keys [binary socket] :or {binary default-binary}}]
   (->RealTmux binary socket)))
