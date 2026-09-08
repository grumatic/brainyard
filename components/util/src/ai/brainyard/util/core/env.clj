;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.util.core.env
  "The one place `by` answers \"what is the value of environment variable K?\".

   `docs/design/environment-scoping-design.md` §3.1 (Phase 0).

   Two facts make this a function rather than a lookup, and both are load-bearing.

   **The JVM environment is immutable**, so `.env` cannot be loaded into it.
   `dotenv.clj` calls `System/setProperty` instead, and the property table is
   therefore a second, equally real source of environment values. A reader that
   consults only `System/getenv` cannot see anything a user put in `.env` on the
   path where the shell wrapper is not involved — `bb tui`, `by-bin` run
   directly, `BY_JAR=1` without the wrapper.

   **Blank counts as unset.** An exported-but-empty `ANTHROPIC_API_KEY=` is a
   common shell accident, and letting it resolve masks the credential that
   should have been used next in the chain — the reasoning
   `clj-llm`'s copy of this already carried. Every caller that wanted a value
   was already blank-checking, at the helper or at the call site; the two that
   were not (`env-truthy?`/`env-int` in the app, `valid-dir-canonical` for
   `BY_WORKING_DIR`) reject a blank downstream anyway, so making it the default
   changes no behaviour and removes a step everyone had to remember.

   The one caller that must NOT collapse blank is MCP's `${VAR}` interpolation,
   which has to tell \"unset\" (leave the literal `${VAR}` in place and warn)
   from \"set to empty\" (substitute nothing). It passes
   `{:blank-as-unset? false}`, and being the exception is why that is an option
   rather than a second function nobody would know to reach for.

   **A blank environment variable therefore FALLS THROUGH to the property
   rather than shadowing it**, and this is the one deliberate behaviour change
   in the consolidation. Every private copy read `(or (getenv k) (getProperty
   k))` and blank-checked the RESULT, so `export GH_TOKEN=` in a shell profile
   silently defeated the `GH_TOKEN` in `.env` — forever, with nothing anywhere
   saying why. That is the same accident blank-as-unset exists to forgive, one
   link earlier in the chain; forgiving it at one link and not the other was an
   artifact of where the check happened to sit, not a decision.

   **Precedence is env over property, and that is not arbitrary**: both `.env`
   loaders already refuse to override a real environment variable
   (`by-wrapper.sh` tests `${!key+x}`, `dotenv.clj` tests `System/getenv`), so
   reading the property first would invert a contract two loaders implement and
   the docs state.

   This lives in `util` because it has no dependencies of its own and every
   layer needs it — the app, the base, and four components each grew a private
   copy of these five lines before it existed."
  (:require [clojure.string :as str]))

(defn- not-blank [v] (when-not (str/blank? v) v))

(defn- var-name
  "`k` as a variable name string, or nil. A keyword/symbol is used by NAME, so
   `:PATH` and `\"PATH\"` name the same variable and `(str :PATH)` never becomes
   a variable literally called `\":PATH\"` — the bug `mcp/client.clj`'s
   `env-var-name` was written to prevent."
  [k]
  (not-empty (cond
               (nil? k)                       nil
               (or (keyword? k) (symbol? k))  (name k)
               :else                          (str k))))

(defn resolve-var
  "Value of environment variable `k`, or nil.

   Checks the process environment first, then the JVM system-property table
   (where `.env` values live — see the namespace docstring). `k` may be a
   string, keyword or symbol (see `var-name`).

   Options:
     `:blank-as-unset?` (default true) — a value that is empty or all
       whitespace resolves to nil. Pass false only when the caller must
       distinguish a variable set to the empty string from one that is unset."
  ([k] (resolve-var k nil))
  ([k {:keys [blank-as-unset?] :or {blank-as-unset? true}}]
   (when-some [n (var-name k)]
     (if blank-as-unset?
       (or (not-blank (System/getenv n)) (not-blank (System/getProperty n)))
       (or (System/getenv n) (System/getProperty n))))))

(defn resolve-first
  "The first of `ks` that resolves, as `[k value]`, or nil.

   Returns the PAIR rather than the value because every caller needs to say
   WHICH variable supplied the credential: the provider tables carry
   alternates (`ANTHROPIC_API_KEY` then `ANTHROPIC_AUTH_TOKEN`), and status
   output that names the wrong one sends the user to edit a variable that is
   not the one in play."
  [ks]
  (some (fn [k] (when-let [v (resolve-var k)] [k v])) ks))

(defn resolve-any?
  "True when any of `ks` resolves to a non-blank value."
  [ks]
  (some? (resolve-first ks)))
