;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui-app.native-main
  "GraalVM native-image entry point for the native `by` binary.

   Deliberately parallel to `ai.brainyard.agent-tui-app.main` (the JVM
   entry). The native image bakes the FULL agent roster by statically
   requiring `ai.brainyard.agent.interface` (which side-effecting-:require's
   every agent ns), then delegates straight to the JVM main's `-main`.

   This diverging native entry point lets the JVM path later drop the
   eager defagent requires without regressing the native binary's roster."
  (:gen-class)
  (:require
   [ai.brainyard.agent.interface]
   ;; Split-out eager agent roster. The 26 `defagent`-registering requires
   ;; that used to live in ai.brainyard.agent.interface were moved to
   ;; ai.brainyard.agent.agents-eager, off the JVM require path. Statically
   ;; require it HERE so the GraalVM native-image analyzer still bakes the
   ;; FULL 26-agent roster even though interface.clj no longer eager-loads
   ;; them. (interface.clj is still required above for the framework API
   ;; plus the two roster ns' it re-exports via export-symbols: acp-agent
   ;; and skills.)
   [ai.brainyard.agent.agents-eager]
   ;; Force-include the memory subsystem (ai.brainyard.memory.interface —
   ;; graph.clj + embed.clj, ~2.7s to compile). The JVM main.clj no longer
   ;; eager-requires it (resolved lazily on first `by memory` / recall/remember
   ;; so it stays off cold-start); statically require it HERE so the native
   ;; `by` image still bakes the full L1/L2/L3 + graph/vec subsystem.
   [ai.brainyard.memory.interface]
   ;; Force-include cognitect.aws + aws-client for the GraalVM native-image
   ;; static analyzer. clj-llm's bedrock.clj uses requiring-resolve to keep
   ;; AWS optional for non-Bedrock builds, but native-image then strips the
   ;; classes — the runtime resolve fails with "Could not locate". Importing
   ;; here (the project that ships `by`) guarantees inclusion without
   ;; forcing static deps on other clj-llm consumers.
   ;;
   ;; cognitect aws-api also dynaloads HTTP backend + per-protocol impl at
   ;; first use (cognitect.aws.dynaload/load-ns). Pre-require those too —
   ;; bedrock-runtime is rest-json. java HTTP client is selected on JDK 11+.
   [cognitect.aws.client.api]
   [cognitect.aws.http.default]
   [cognitect.aws.http.java]
   [cognitect.aws.protocols.rest-json]
   [ai.brainyard.aws-client.interface]
   [ai.brainyard.agent-tui-app.main :as main]))

(defn -main [& args]
  (apply main/-main args))
