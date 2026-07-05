;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
(ns ai.brainyard.playground-ui.graph
  "Context-graph memory visualisation — a self-contained, dependency-free
   node-link canvas (Neo4j-style) rendered as pure Replicant hiccup SVG.

   Split of concerns matches the rest of the SPA: this ns holds the PURE bits —
   a deterministic force-directed `layout`, the initial `view` box, a
   screen→SVG coordinate helper, and the `panel` render fn. All side effects
   (fetching, drag/zoom/pan state transitions) live in `dispatch`, keyed under
   `[:graph <workspace-id>]`.

   Nodes carry `{:id :name :node-type :summary :aliases}`; edges carry
   `{:src_id :dst_id :relation :fact :confidence}` — the shape emitted by
   `by memory graph --json` (see components/memory)."
  (:require [clojure.string :as str]))

;; --- node-type colour palette ----------------------------------------------

(def ^:private type-colors
  "Fixed, legible colours for the common context-graph node types; anything
   else falls back to a hashed palette entry so every type is visually stable."
  {"person"   "#f778ba"   ; pink
   "project"  "#58a6ff"   ; blue
   "tech"     "#3fb950"   ; green
   "tool"     "#3fb950"
   "concept"  "#d29922"   ; amber
   "topic"    "#d29922"
   "org"      "#a371f7"   ; purple
   "place"    "#f0883e"   ; orange
   "event"    "#ff7b72"   ; red
   "entity"   "#8b949e"}) ; grey (generic fallback type)

(def ^:private palette
  ["#58a6ff" "#3fb950" "#f778ba" "#d29922" "#a371f7"
   "#f0883e" "#ff7b72" "#56d4dd" "#e3b341" "#7ee787"])

(defn node-color [node-type]
  (let [t (some-> node-type name str/lower-case)]
    (or (get type-colors t)
        (get palette (mod (hash (or t "?")) (count palette))))))

;; --- deterministic force-directed layout -----------------------------------
;; Fruchterman-Reingold with cooling. Deterministic init (nodes seeded on a
;; circle by index) so reopening a workspace's graph yields a stable picture —
;; no Math/random. Runs synchronously; fine for agent-memory-scale graphs
;; (tens to low hundreds of nodes), which is what `by memory graph` returns.

(def ^:private W 1000.0)   ; layout canvas (user units)
(def ^:private H 700.0)

(defn layout
  "Positions for `nodes` given `edges`. Returns {node-id [x y]} in a 0..W×0..H
   box. `edges` reference nodes by :src_id/:dst_id. O(iters·n²) — capped node
   set keeps that cheap."
  [nodes edges]
  (let [n     (count nodes)]
    (if (zero? n)
      {}
      (let [ids   (mapv :id nodes)
            idx   (zipmap ids (range))         ; node-id -> slot
            xs    (js/Float64Array. n)
            ys    (js/Float64Array. n)
            k     (* 0.9 (js/Math.sqrt (/ (* W H) n)))  ; ideal edge length
            ;; seed on a circle (deterministic), radius fills most of the box
            _     (dotimes [i n]
                    (let [a (/ (* 2 js/Math.PI i) n)]
                      (aset xs i (+ (/ W 2) (* (* 0.42 W) (js/Math.cos a))))
                      (aset ys i (+ (/ H 2) (* (* 0.42 H) (js/Math.sin a))))))
            ;; edge list as slot pairs (skip dangling refs)
            es    (into []
                        (keep (fn [{:keys [src_id dst_id]}]
                                (let [a (idx src_id) b (idx dst_id)]
                                  (when (and a b (not= a b)) [a b]))))
                        edges)
            iters 260]
        (loop [it 0
               temp (* 0.10 W)]
          (when (< it iters)
            (let [dx (js/Float64Array. n)
                  dy (js/Float64Array. n)]
              ;; repulsion (all pairs)
              (dotimes [i n]
                (dotimes [j n]
                  (when (not= i j)
                    (let [ddx (- (aget xs i) (aget xs j))
                          ddy (- (aget ys i) (aget ys j))
                          d   (js/Math.max 0.01 (js/Math.sqrt (+ (* ddx ddx) (* ddy ddy))))
                          f   (/ (* k k) d)]
                      (aset dx i (+ (aget dx i) (* (/ ddx d) f)))
                      (aset dy i (+ (aget dy i) (* (/ ddy d) f)))))))
              ;; attraction (along edges)
              (doseq [[a b] es]
                (let [ddx (- (aget xs a) (aget xs b))
                      ddy (- (aget ys a) (aget ys b))
                      d   (js/Math.max 0.01 (js/Math.sqrt (+ (* ddx ddx) (* ddy ddy))))
                      f   (/ (* d d) k)]
                  (aset dx a (- (aget dx a) (* (/ ddx d) f)))
                  (aset dy a (- (aget dy a) (* (/ ddy d) f)))
                  (aset dx b (+ (aget dx b) (* (/ ddx d) f)))
                  (aset dy b (+ (aget dy b) (* (/ ddy d) f)))))
              ;; integrate, capped by temperature, clamped to the box
              (dotimes [i n]
                (let [len (js/Math.max 0.01 (js/Math.sqrt (+ (* (aget dx i) (aget dx i))
                                                             (* (aget dy i) (aget dy i)))))
                      cap (js/Math.min len temp)]
                  (aset xs i (-> (+ (aget xs i) (* (/ (aget dx i) len) cap))
                                 (js/Math.max 20) (js/Math.min (- W 20))))
                  (aset ys i (-> (+ (aget ys i) (* (/ (aget dy i) len) cap))
                                 (js/Math.max 20) (js/Math.min (- H 20))))))
              (recur (inc it) (* temp 0.985)))))
        (into {} (map (fn [id] [id [(aget xs (idx id)) (aget ys (idx id))]])) ids)))))

(defn view
  "Initial viewBox {:x :y :w :h} framing all positions with padding."
  [pos]
  (if (empty? pos)
    {:x 0 :y 0 :w W :h H}
    (let [xs (map (comp first val) pos)
          ys (map (comp second val) pos)
          x0 (apply min xs) x1 (apply max xs)
          y0 (apply min ys) y1 (apply max ys)
          pad 80]
      {:x (- x0 pad) :y (- y0 pad)
       :w (max 200 (+ (- x1 x0) (* 2 pad)))
       :h (max 160 (+ (- y1 y0) (* 2 pad)))})))

(defn event->svg
  "Screen (clientX/Y from `ev`) → SVG user coords under viewBox `v`, using the
   SVG element's on-screen rect. Returns [x y]."
  [ev v]
  (let [svg  (.-ownerSVGElement (.-target ev))
        svg  (or svg (.-target ev))
        rect (.getBoundingClientRect svg)
        rx   (/ (- (.-clientX ev) (.-left rect)) (max 1 (.-width rect)))
        ry   (/ (- (.-clientY ev) (.-top rect)) (max 1 (.-height rect)))]
    [(+ (:x v) (* rx (:w v)))
     (+ (:y v) (* ry (:h v)))]))

;; --- render ----------------------------------------------------------------

(defn- degree-map [nodes edges]
  (let [present (into #{} (map :id) nodes)]
    (reduce (fn [m {:keys [src_id dst_id]}]
              (cond-> m
                (present src_id) (update src_id (fnil inc 0))
                (present dst_id) (update dst_id (fnil inc 0))))
            {} edges)))

(defn- node-radius [deg] (min 34 (+ 15 (* 2.5 (or deg 0)))))

(defn- legend [nodes]
  (let [types (->> nodes (map :node-type) (map #(some-> % name)) (remove nil?) distinct sort)]
    (when (seq types)
      [:div.graph-legend
       (for [t types]
         [:span.legend-item {:replicant/key t}
          [:span.swatch {:style {:background (node-color t)}}]
          t])])))

(defn- detail-panel [id nodes sel]
  (when-let [nd (some #(when (= sel (:id %)) %) nodes)]
    [:div.graph-detail
     [:header
      [:span.dot {:style {:background (node-color (:node-type nd))}}]
      [:strong (:name nd)]
      [:span.spacer]
      [:button.x {:on {:click [[:graph/select id nil]]}} "✕"]]
     [:p.type (some-> (:node-type nd) name)]
     (when-let [s (not-empty (:summary nd))] [:p.summary s])
     (when (seq (:aliases nd))
       [:p.aliases "aka " (str/join ", " (:aliases nd))])]))

(defn- svg-graph [id {:keys [nodes edges pos view selected]}]
  (let [degs (degree-map nodes edges)
        vb   (str (:x view) " " (:y view) " " (:w view) " " (:h view))
        ;; label/stroke sizing tracks zoom so text stays legible at any scale
        fs   (max 8 (* 0.014 (:w view)))
        sw   (max 0.6 (* 0.0016 (:w view)))]
    [:svg.graph-svg
     {:viewBox vb
      :preserveAspectRatio "xMidYMid meet"
      :on {:wheel       [[:event/prevent-default] [:graph/zoom id]]
           :pointerdown [[:graph/bg-down id]]
           :pointermove [[:graph/pointer-move id]]
           :pointerup   [[:graph/pointer-up id]]
           :pointerleave [[:graph/pointer-up id]]}}
     ;; arrowhead marker (defined in user units; scales with the view)
     [:defs
      [:marker {:id "arrow" :viewBox "0 0 10 10" :refX 9 :refY 5
                :markerWidth 6 :markerHeight 6 :orient "auto-start-reverse"}
       [:path {:d "M 0 0 L 10 5 L 0 10 z" :fill "#4b5563"}]]]
     ;; edges
     [:g.edges
      (for [{:keys [src_id dst_id relation] :as e} edges
            :let [p1 (get pos src_id) p2 (get pos dst_id)]
            :when (and p1 p2)]
        (let [[x1 y1] p1 [x2 y2] p2
              mx (/ (+ x1 x2) 2) my (/ (+ y1 y2) 2)]
          [:g {:replicant/key (str "e-" (:id e))}
           [:line {:x1 x1 :y1 y1 :x2 x2 :y2 y2
                   :stroke "#3d444d" :stroke-width sw :marker-end "url(#arrow)"}]
           (when relation
             [:text.edge-label {:x mx :y my :font-size (* 0.85 fs)
                                :fill "#8b949e" :text-anchor "middle"}
              (some-> relation name)])]))]
     ;; nodes
     [:g.nodes
      (for [nd nodes
            :let [nid (:id nd) p (get pos nid)]
            :when p]
        (let [[x y] p
              r (node-radius (get degs nid))
              on? (= selected nid)]
          [:g.node {:replicant/key (str "n-" nid)
                    :transform (str "translate(" x "," y ")")
                    :class (when on? "sel")
                    :on {:pointerdown [[:event/stop-propagation]
                                       [:graph/node-down id nid]]}}
           [:circle {:r r :fill (node-color (:node-type nd))
                     :stroke (if on? "#fff" "#0d1117")
                     :stroke-width (if on? (* 2 sw) sw)}]
           [:text.node-label {:y (+ r (* 1.15 fs)) :font-size fs
                              :fill "#c9d1d9" :text-anchor "middle"}
            (let [nm (or (:name nd) "?")]
              (if (> (count nm) 22) (str (subs nm 0 21) "…") nm))]]))]]))

(defn panel
  "Full graph-memory panel for workspace `id` given its `[:graph id]` state."
  [id {:keys [status enabled? counts] :as g}]
  [:div.graph-body
   (case status
     :loading [:p.hint "Reading the workspace's context-graph…"]
     :error   [:p.hint.err (str "Couldn't read graph memory"
                                (when-let [e (:error g)] (str ": " e)))]
     :not-enabled
     [:div.graph-empty
      [:h3 "Graph memory is off for this workspace"]
      [:p.hint "The context-graph overlay is disabled by default. Turn it on by "
       "adding these to " [:b "Settings → Workspace environment"] ", then create "
       "or resume a workspace:"]
      [:pre.env-hint "BY_ENABLE_GRAPH_MEMORY=true\nBY_GRAPH_EXTRACT_MODEL=bedrock/amazon.nova-lite-v1:0\nBY_GRAPH_EMBED_MODEL=static"]
      [:p.hint "Once enabled, chat in the terminal — entities and relationships "
       "are extracted into the graph as your sessions consolidate."]]
     :empty
     [:div.graph-empty
      [:h3 "No graph yet"]
      [:p.hint "Graph memory is enabled, but nothing has been extracted into it "
       "yet. Use the terminal for a bit, then reopen this panel — nodes and edges "
       "appear as sessions consolidate."]]
     ;; :ready
     [:div.graph-canvas
      [:div.graph-toolbar
       [:span.counts (str (:nodes counts) " nodes · " (:edges counts) " edges")]
       [:span.spacer]
       [:button {:on {:click [[:graph/refit id]]}} "Fit"]]
      (svg-graph id g)
      (legend (:nodes g))
      (detail-panel id (:nodes g) (:selected g))])])
