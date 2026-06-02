;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui-tmux.core.host-callbacks
  "Adapters that translate the agent runtime's `:permission-fn` /
   `:user-feedback-fn` callback contract into questionnaire popups dispatched
   through the `host` transport adapter.

   **Retired substrate (May 2026).** Kept as test-only/internal after the
   `by-host`↔`by-ui` daemon split was retired. See
   `docs/tui/architecture.md` §9 and `docs/specs/tui.md` CR-TUI-20.

   Per docs/tmux-based-agent-tui.md §5.3 — the agent runtime invokes these
   callbacks blocking; the by-host adapter translates them into `:popup`
   frames sent to the attached `by-ui`, then awaits the `:popup-result`."
  (:require [ai.brainyard.agent-tui-tmux.core.host :as host]
            [ai.brainyard.agent-tui-tmux.core.questionnaire :as q])
  (:import [java.io File]))

(defn- parent-dir [path]
  (when path (.getParent (File. ^String (str path)))))

(defn make-permission-fn
  "Build a permission-fn callback bound to `host-handle`.

   Contract per existing TUI:
     argument: {:tool ... :path :paths}
     return:   {:allowed true} | {:denied true :reason ...}

   Maintains a `!allowed-dirs` set across the session (so `:always` is
   sticky for the lifetime of the agent).  Cancellation / timeout deny."
  [{:keys [host !allowed-dirs timeout-ms]
    :or {!allowed-dirs (atom #{})
         timeout-ms 60000}}]
  (fn [{:keys [tool path paths] :as req}]
    (let [all-paths   (or (when paths (seq paths)) (when path [path]))
          parent-dirs (keep parent-dir all-paths)
          display     (if (and all-paths (> (count all-paths) 1))
                        (clojure.string/join ", " all-paths)
                        (first all-paths))]
      (cond
        (and (seq parent-dirs)
             (every? #(contains? @!allowed-dirs %) parent-dirs))
        {:allowed true}

        :else
        (let [questionnaire (q/permission-questionnaire
                             {:tool (or tool "tool") :path display})
              reply (host/await-popup-reply! host questionnaire {:timeout-ms timeout-ms})]
          (case (q/permission-decision reply)
            :yes    {:allowed true}
            :always (do (doseq [d parent-dirs] (swap! !allowed-dirs conj d))
                        {:allowed true})
            :no     {:denied true :reason "User denied file access"}
            :never  {:denied true :reason "User denied file access (and asked not to ask again)"}
            {:denied true :reason (case (:status reply)
                                    :timeout   "Permission prompt timed out"
                                    :cancelled "Permission prompt cancelled"
                                    "Permission denied")}))))))

(defn make-user-feedback-fn
  "Build a user-feedback-fn callback bound to `host-handle`.

   Contract per existing TUI:
     argument: {:question \"...\" :options [\"a\" \"b\" ...]}
     return:   selected option index (Long), or nil for cancel/timeout.

   `:tabs` extension supported: if `req` has `:tabs`, build a multi-tab
   questionnaire and return the full `:answers` map."
  [{:keys [host timeout-ms] :or {timeout-ms 60000}}]
  (fn [{:keys [question options tabs] :as req}]
    (let [questionnaire (cond
                          tabs
                          (q/make {:title (or (:title req) "Feedback") :tabs tabs})

                          :else
                          (q/feedback-questionnaire {:question question :options options}))
          reply (host/await-popup-reply! host questionnaire {:timeout-ms timeout-ms})]
      (cond
        (not= :submitted (:status reply)) nil

        tabs
        (:answers reply)

        :else
        (-> reply :answers :feedback :value)))))
