;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.agent-tui.mode
  "Boot-time decision: which TUI Mode (A/B/C) does this `by` invocation use?

   Per docs/simplified-agent-tui-arch-design.md §2 the decision is from three
   inputs:
     1. `--with-tmux`        — explicit user opt-in to side-channel features
     2. `$TMUX`              — set when we're already inside a tmux session
     3. `tmux` on `$PATH`    — required for any tmux interaction

   Decision matrix (§2.7):

     | --with-tmux | tmux on PATH | $TMUX set | server alive | Mode |
     |-------------|--------------|-----------|--------------|------|
     | no          | no           | -         | -            | A    |
     | no          | yes          | no        | -            | A    |
     | no          | yes          | yes       | yes          | B    |
     | no          | yes          | yes       | no           | A    |
     | yes         | yes          | yes       | yes          | B    |
     | yes         | yes          | yes       | no           | C    |
     | yes         | yes          | no        | -            | C    |
     | yes         | no           | -         | -            | C    |

   `probe` is the public entry point. It returns a map; `cmd-run` exits 1 on
   Mode C after printing `:guidance`."
  (:require [clojure.java.shell :as shell]
            [clojure.string :as str]))

;; ----------------------------------------------------------------------------
;; Indirection seams (for tests)
;; ----------------------------------------------------------------------------

(defn- getenv [name] (System/getenv name))

(defn- which
  "Return absolute path of `bin` on $PATH, or nil. Implemented via a single
   `command -v` shellout — bounded, deterministic, no PATH parsing here."
  [bin]
  (let [{:keys [exit out]} (shell/sh "sh" "-c" (str "command -v " bin))]
    (when (zero? exit)
      (str/trim out))))

(defn- tmux-server-alive?*
  "Probe a tmux server through the socket implied by `$TMUX` (format:
   `<socket-path>,<pid>,<session-id>`). Returns true iff `tmux display` answers
   within `:timeout-ms`. Any failure (process spawn, non-zero exit, timeout) →
   false; we degrade rather than throw, to keep boot fast."
  [tmux-env tmux-bin]
  (let [socket (first (str/split (or tmux-env "") #","))]
    (if (str/blank? socket)
      false
      (try
        (let [pb        (doto (ProcessBuilder. ^"[Ljava.lang.String;"
                               (into-array String [tmux-bin "-S" socket "display" "-p" "#{client_pid}"]))
                          (.redirectErrorStream true))
              ^Process p (.start pb)
              ;; (long 200) is REQUIRED for the native-image build:
              ;; Process.waitFor has two overloads — `int waitFor()` and
              ;; `boolean waitFor(long, TimeUnit)`. Without a primitive
              ;; long hint the call dispatches via reflection at runtime,
              ;; and native-image strips that path → the call throws and
              ;; the surrounding `(catch Throwable _ false)` masks it,
              ;; turning every tmux-server probe into "dead". See
              ;; docs/design/native-image-design.md §11.
              done?    (.waitFor p (long 200) java.util.concurrent.TimeUnit/MILLISECONDS)]
          (when-not done? (.destroyForcibly p))
          (and done? (zero? (.exitValue p))))
        (catch Throwable _ false)))))

;; ----------------------------------------------------------------------------
;; Guidance text
;; ----------------------------------------------------------------------------

(def ^:private guidance-need-session
  "You passed --with-tmux, but you're not currently inside a tmux session.
For tmux side panes (activity, log) and popup dialogs, start a tmux
session and re-run `by` from inside it:

    tmux new -s brainyard
    by --with-tmux

Or drop --with-tmux to run the in-process TUI without tmux integration:

    by\n")

(def ^:private guidance-need-tmux
  "You passed --with-tmux, but `tmux` is not on $PATH.
Install tmux, then re-run from inside a tmux session:

    # macOS
    brew install tmux
    # Debian/Ubuntu
    sudo apt-get install tmux

Or drop --with-tmux to run the in-process TUI without tmux integration:

    by\n")

(def ^:private guidance-server-dead
  "You passed --with-tmux and $TMUX is set, but the tmux server isn't
responding (it may have been killed or the system was suspended).
Start a fresh tmux session:

    tmux new -s brainyard
    by --with-tmux\n")

;; ----------------------------------------------------------------------------
;; Probe
;; ----------------------------------------------------------------------------

(defn probe
  "Decide Mode A / B / C from `opts` (as parsed by cli-matic) plus environment.

   `opts` may contain `:with-tmux` (boolean). Anything else is ignored.

   Returns a map:
     :mode                 :A | :B | :C
     :explicit-with-tmux?  bool
     :tmux-on-path?        bool
     :inside-tmux?         bool        ;; $TMUX is non-blank
     :tmux-server-alive?   bool|nil    ;; nil when not probed (no $TMUX)
     :guidance             str|nil     ;; non-nil iff :mode = :C

   Mode C is *only* reachable when the user passed `--with-tmux` and we
   couldn't satisfy it. The default no-`$TMUX` path is Mode A."
  ([] (probe {}))
  ([opts]
   (let [with-tmux? (boolean (:with-tmux opts))
         tmux-env   (getenv "TMUX")
         inside?    (not (str/blank? tmux-env))
         tmux-bin   (which "tmux")
         on-path?   (some? tmux-bin)
         alive?     (when (and inside? on-path?)
                      (tmux-server-alive?* tmux-env tmux-bin))
         base       {:explicit-with-tmux? with-tmux?
                     :tmux-on-path?       on-path?
                     :inside-tmux?        inside?
                     :tmux-server-alive?  alive?}]
     (cond
       ;; Mode B: in tmux with a live server (and we have the binary).
       (and on-path? inside? alive?)
       (assoc base :mode :B :guidance nil)

       ;; Mode C: explicit --with-tmux but we can't honour it.
       with-tmux?
       (assoc base :mode :C
              :guidance (cond
                          (not on-path?)         guidance-need-tmux
                          (not inside?)          guidance-need-session
                          (false? alive?)        guidance-server-dead
                          :else                  guidance-need-session))

       ;; Default: Mode A.
       :else
       (assoc base :mode :A :guidance nil)))))
