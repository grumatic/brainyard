;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.feature-commands
  "The feature$* family — an LLM-facing view over the feature registry.

   Mirrors the schedule$* / fsm$* command-family idiom: a small vector of
   defcommands plus a `feature-commands` roster that `all-common-commands`
   concatenates.

   Why these exist: `agent-runtime$config` can search 137 keys by substring,
   but a substring match cannot answer \"is graph memory actually on, and if
   not, why?\" — the answer depends on the gate, everything that :implies it,
   everything it :requires, and which precedence layer supplied the value.
   Before feature$explain that took reading six files.

   These commands ORCHESTRATE existing machinery only: reads go through
   core.feature, and feature$set routes to `config/set-config!` on the gate
   key, so persistence, the config-agent allowlist and dossier behaviour are
   unchanged. No new storage, no new precedence layer."
  (:require [ai.brainyard.agent.core.tool :refer [defcommand]]
            [ai.brainyard.agent.core.protocol :as proto]
            [ai.brainyard.agent.core.config :as config]
            [ai.brainyard.agent.core.feature :as feature]
            [clojure.string :as str]))

(defn- fid->str [fid] (str (symbol fid)))

(defn- source-label
  "Human phrasing for a `config/config-source` layer."
  [source]
  (case source
    :env      "an environment variable"
    :agent    "a per-agent override"
    :session  "the session config"
    :global   ".brainyard/config.edn (or the schema default)"
    :default  "the schema default"
    (str source)))

(defn- unmet->strs [unmet]
  (mapv (fn [r]
          (if (set? r)
            {:any-of (mapv fid->str (sort r))}
            (fid->str r)))
        unmet))

(defn- why
  "One-sentence answer to \"why is this on/off?\". This is the whole point of
   feature$explain — a caller that gets only :on? still has to go digging."
  [fid {:keys [on? source implied-by unmet]} gate gate-src]
  (let [f (feature/feature-doc fid)]
    (cond
      (:proposed f)
      (str "Not gateable yet — " (name (:gate f))
           " is planned but not in config-schema, so this feature is always on"
           " and only its knobs are live.")

      ;; Unmet requirements come first: they explain the state regardless of
      ;; what the gate says, and an ungated grouping can have them too
      ;; (analytics/scoring requires analytics/trajectory).
      (seq unmet)
      (str "OFF because its requirements are unmet: "
           (str/join ", " (map (fn [r] (if (set? r)
                                         (str "any of " (str/join " / " (map fid->str (sort r))))
                                         (fid->str r)))
                               unmet))
           (when gate
             (str ". The gate itself reads " (pr-str (config/get-config nil gate)))))

      (= source :implied-by)
      (str "ON because " (str/join ", " (map fid->str (sort implied-by)))
           " implies it"
           (when gate (str ", even though " (name gate) " is not set on its own"))
           ".")

      ;; Before the on?/off branches — an ungated grouping is always on, and
      ;; both of those would try to name a gate that does not exist.
      (nil? gate)
      "ON — an ungated grouping, so it has no on/off switch, only knobs."

      on?
      (str "ON because " (name gate) " resolves truthy, from " (source-label gate-src) ".")

      :else
      (str "OFF because " (name gate) " resolves falsey, from " (source-label gate-src) "."))))

;; ============================================================================
;; feature$list
;; ============================================================================

(defcommand feature$list
  "List feature families with how many of their features are on; pass :family to expand one into its features."
  (fn [& {:keys [family]}]
    (let [agent proto/*current-agent*]
      (if (str/blank? (str family))
        {:families
         (vec (for [fam feature/families
                    :let [fids (feature/family->features fam)
                          states (map #(feature/feature-state agent %) fids)
                          on (count (filter :on? states))]]
                (cond-> {:family (name fam)
                         :on on
                         :of (count fids)}
                  (some (comp seq :degraded) states)
                  (assoc :degraded true))))
         :profile (let [p (config/get-config agent :feature-profile)]
                    (cond-> {:name (str (some-> p name))
                             :known (contains? config/feature-profiles p)}
                      (not (contains? config/feature-profiles p))
                      (assoc :warning
                             (format "Unknown profile — ignored, schema defaults apply. Valid: %s"
                                     (str/join ", " (map name (sort (keys config/feature-profiles))))))))
         :hint "feature$list :family \"memory\" expands one family; feature$explain <feature> says why a feature is on or off."}
        (if-let [view (feature/family-view agent family)]
          view
          {:error (format "Unknown family '%s'. Valid: %s"
                          family (str/join ", " (map name feature/families)))}))))
  :input-schema  [:map
                  [:family {:optional true}
                   [:string {:desc "Family to expand (memory, self-improve, automation, context, exec, agents, reasoning, tools, analytics, ui). Omit for the summary."}]]]
  :output-schema [:map
                  [:families {:optional true} [:string {:desc "Summary rows: {:family :on :of} plus :degraded when any member is degraded"}]]
                  [:family   {:optional true} [:string {:desc "Family name (when :family was given)"}]]
                  [:features {:optional true} [:string {:desc "Per-feature detail for the expanded family"}]]
                  [:profile  {:optional true} [:string {:desc "Active :feature-profile — {:name :known} plus :warning when the name is unrecognised"}]]
                  [:hint     {:optional true} [:string {:desc "How to drill in"}]]
                  [:error    {:optional true} [:string {:desc "Unknown family"}]]])

;; ============================================================================
;; feature$explain
;; ============================================================================

(defcommand feature$explain
  "Explain why one feature is on or off: its gate, which config layer supplied it, what implied it, unmet requirements, and its knobs."
  (fn [& {:keys [feature]}]
    (let [agent proto/*current-agent*]
      (if-let [fid (feature/resolve-feature feature)]
        (let [f     (feature/feature-doc fid)
              st    (feature/feature-state agent fid)
              gate  (:gate f)
              gsrc  (when (and gate (not (:proposed f))) (config/config-source agent gate))]
          (cond-> {:feature   (fid->str fid)
                   :title     (:title f)
                   :family    (name (:family f))
                   :on?       (:on? st)
                   :why       (why fid st gate gsrc)
                   :lifecycle (:lifecycle f)
                   :doc       (some-> (:doc f) (str/replace #"\s+" " "))
                   :knobs     (vec (for [k (:keys f)]
                                     {:key     (name k)
                                      :value   (config/get-config agent k)
                                      :default (get-in config/config-schema [k :default])
                                      :source  (config/config-source agent k)}))}
            gate
            (assoc :gate (name gate))

            gsrc
            (assoc :gate-value (config/get-config agent gate)
                   :gate-source gsrc)

            (:proposed f)
            (assoc :proposed true
                   :note "gate key not in config-schema yet — knobs are live, the gate is not settable")

            (:presentation f)
            (assoc :presentation true
                   :note "rendering only; never a capability gate")

            (seq (:implied-by st))
            (assoc :implied-by (mapv fid->str (sort (:implied-by st))))

            (seq (:unmet st))
            (assoc :unmet (unmet->strs (:unmet st)))

            (seq (:degraded st))
            (assoc :degraded (into {} (for [[k v] (:degraded st)] [(fid->str k) v])))

            (and gate (config/requires-restart-key? gate))
            (assoc :requires-restart true
                   :restart-note "read once at startup — RESTART by for a change to take effect")))
        {:error (format "Unknown feature '%s'. Use feature$list to see families, then <family>/<name> (e.g. memory/graph)."
                        feature)})))
  :input-schema  [:map
                  [:feature [:string {:desc "Feature id, e.g. memory/graph or memory.graph"}]]]
  :output-schema [:map
                  [:feature   {:optional true} [:string]]
                  [:on?       {:optional true} [:boolean {:desc "Resolved state, after :implies and :requires"}]]
                  [:why       {:optional true} [:string {:desc "One-sentence explanation of the resolved state"}]]
                  [:gate      {:optional true} [:string {:desc "Config key acting as the on/off switch"}]]
                  [:gate-value {:optional true} [:any]]
                  [:gate-source {:optional true} [:any {:desc "Winning precedence layer: :env :agent :session :global :default"}]]
                  [:implied-by {:optional true} [:string {:desc "Features that switch this on transitively"}]]
                  [:unmet     {:optional true} [:string {:desc "Requirements not satisfied (feature resolves off)"}]]
                  [:degraded  {:optional true} [:string {:desc "Partial requirements: still on, minus a capability"}]]
                  [:knobs     {:optional true} [:string {:desc "Member keys with value, default and winning layer"}]]
                  [:requires-restart {:optional true} [:boolean]]
                  [:error     {:optional true} [:string]]])

;; ============================================================================
;; feature$set
;; ============================================================================

(defcommand feature$set
  "Turn a feature (or a whole family) on or off by writing its gate key through the normal config path."
  (fn [& {:keys [feature state]}]
    (let [agent proto/*current-agent*
          on?   (contains? #{"on" "true" "yes" "enable" "enabled" true} state)
          off?  (contains? #{"off" "false" "no" "disable" "disabled" false} state)]
      (cond
        (not (or on? off?))
        {:error "state must be on or off"}

        ;; A bare family name sets the family master switch — non-destructive,
        ;; so the member gates keep whatever they were set to individually.
        (and (not (feature/resolve-feature feature))
             (contains? feature/family-gates (keyword (str/lower-case (str feature)))))
        (let [fam (keyword (str/lower-case (str feature)))
              r   (feature/set-family! agent fam on?)]
          (if (:error r)
            r
            (assoc r :result
                   (format "%s set to %s — %d of %d features in %s are now on."
                           (:gate r) (:set r)
                           (count (filter :on? (:features r)))
                           (count (:features r))
                           (:family r)))))

        (nil? (feature/resolve-feature feature))
        {:error (format "Unknown feature or family '%s'. Use feature$list to see families, then <family>/<name> (e.g. memory/graph)."
                        feature)}

        :else
        ;; Guards and the write live in core.feature/set-feature! so this
        ;; command and the TUI's /feature cannot drift on what is settable.
        (let [fid (feature/resolve-feature feature)
              r   (feature/set-feature! agent fid on?)]
          (if (:error r)
            r
            (cond-> (assoc (dissoc r :unmet :degraded)
                           :result (format "%s set to %s and persisted to .brainyard/config.edn."
                                           (:gate r) (:set r)))
              ;; The written value and the RESOLVED state can differ — that is
              ;; the whole point of declared dependencies, so say so rather
              ;; than letting the caller assume the write took.
              (and on? (not (:on? r)))
              (assoc :warning
                     (format "Still off: %s" (or (feature/off-reason agent fid) "unmet requirements"))
                     :unmet (unmet->strs (:unmet r)))

              (seq (:degraded r))
              (assoc :degraded (into {} (for [[k v] (:degraded r)] [(fid->str k) v])))

              (config/requires-restart-key? (keyword (:gate r)))
              (assoc :requires-restart true
                     :restart-note "read once at startup — RESTART by for this to take effect")))))))
  :input-schema  [:map
                  [:feature [:string {:desc "Feature id (memory/graph) or a bare family name (memory) to set the family master switch"}]]
                  [:state   [:string {:desc "on or off"}]]]
  :output-schema [:map
                  [:feature {:optional true} [:string]]
                  [:gate    {:optional true} [:string]]
                  [:set     {:optional true} [:boolean {:desc "The value written to the gate"}]]
                  [:on?     {:optional true} [:boolean {:desc "The RESOLVED state after the write — may differ from :set when requirements are unmet"}]]
                  [:result  {:optional true} [:string]]
                  [:warning {:optional true} [:string {:desc "Set on, but still resolving off"}]]
                  [:unmet   {:optional true} [:string]]
                  [:degraded {:optional true} [:string]]
                  [:requires-restart {:optional true} [:boolean]]
                  [:family  {:optional true} [:string {:desc "Family name, when a family master switch was set"}]]
                  [:features {:optional true} [:string {:desc "Per-member resolved state, when a family switch was set"}]]
                  [:error   {:optional true} [:string]]])

(def feature-commands
  "The feature$* roster, concatenated into `all-common-commands`."
  [#'feature$list #'feature$explain #'feature$set])
