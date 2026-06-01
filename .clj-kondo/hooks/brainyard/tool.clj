(ns hooks.brainyard.tool
  "clj-kondo analyze-call hooks for brainyard's home-grown def* macros, so the
   names they define resolve and their bodies still get linted (unlike a
   blanket :lint-as def-catch-all, which would silence the bodies too).

   `deftool-like` — for any `(macro <name> & rest)` form that defines <name>:
     deftool / defcommand / defskill / defagent  (ai.brainyard.agent.core.tool)
     defschemas                                  (clj-llm: (defschemas sym map))
     defsignature                                (clj-llm: (defsignature sym doc map))
   Rewrites to `(def <name> [<rest…>])` — <name> is registered, and every
   remaining child (description, tool-fn body, kwarg values like
   :bt-factory react-behavior-tree, schema maps, signature fields) stays under
   analysis.

   `export-symbols` — for (export-symbols <src-ns> sym1 sym2 …), which defs
   each listed symbol in the current ns. Rewrites to `(declare sym1 sym2 …)` so
   the re-exported names resolve at both the def site and their use sites
   WITHOUT tripping the :uninitialized-var lint that a bare `(def sym)` would."
  (:require [clj-kondo.hooks-api :as api]))

(defn deftool-like
  [{:keys [node]}]
  (let [[_macro name-node & arg-nodes] (:children node)]
    ;; Bail on an unexpected shape (e.g. macro misuse) — let clj-kondo lint the
    ;; raw form rather than throwing inside the hook.
    (if-not (and name-node (api/token-node? name-node) arg-nodes)
      {:node node}
      (let [new-node (api/list-node
                      [(api/token-node 'def)
                       name-node
                       (api/vector-node (vec arg-nodes))])]
        {:node (with-meta new-node (meta node))}))))

(defn export-symbols
  [{:keys [node]}]
  (let [[_macro _src-ns & sym-nodes] (:children node)]
    (if-not (seq sym-nodes)
      {:node node}
      (let [new-node (api/list-node
                      (list* (api/token-node 'declare) (vec sym-nodes)))]
        {:node (with-meta new-node (meta node))}))))
