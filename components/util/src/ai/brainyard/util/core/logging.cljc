;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.util.core.logging
  "Logging helpers and configuration for Timbre."
  (:require
   #?@(:clj [[clojure.pprint :refer [pprint]]])
   [clojure.string :as str]
   [taoensso.encore :as enc]
   [taoensso.timbre.appenders.core :as appenders]
   [taoensso.timbre :as log]))

#?(:clj
   (defmacro p
     "Convert a data structure to a visually-delimited pretty-printed string block."
     [v]
     `(str
       ~(str "\n" v "\n================================================================================\n")
       (with-out-str (pprint ~v))
       "================================================================================")))

(defn pretty
  "Marks a data item for pretty formatting when logging it (requires installing logging middleware)."
  [v]
  (with-meta v {:pretty true}))

(defn pretty-middleware
  "Returns timbre logging middleware that will reformat items marked with `pretty` as pretty-printed strings using `data->string`."
  [data->string]
  (fn [data]
    (update data :vargs (fn [args]
                          (mapv
                           (fn [v]
                             (if (and (coll? v) (-> v meta :pretty))
                               (data->string v)
                               v))
                           args)))))

#?(:clj
   (defn custom-output-fn
     "Derived from Timbre's default output function. Used server-side."
     ([data] (custom-output-fn nil data))
     ([opts data]
      (let [{:keys [no-stacktrace?]} opts
            {:keys [level ?err msg_ ?ns-str ?file timestamp_ ?line]} data]
        (format "%1.1S %s %20s:-%3s - %s%s"
                (name level)
                (force timestamp_)
                (str/replace-first (or ?ns-str ?file "?") "com.fulcrologic." "_")
                (or ?line "?")
                (force msg_)
                (enc/if-let [_   (not no-stacktrace?)
                             err ?err]
                  (str "\n" (log/stacktrace err opts))
                  ""))))))

;; Default taoensso.timbre logging config
(defonce default-log-config
  {:min-level :debug
   :ns-filter {:allow "*"
               :deny #{"com.mchange.v2.*"
                       "com.zaxxer.hikari.pool.*"
                       "io.netty.buffer.PoolThreadCache"
                       "org.apache.http.impl.conn.PoolingHttpClientConnectionManager"
                       "org.quartz.*"
                       "shadow.cljs.devtools.server.worker.impl"
                       "io.undertow.websockets.core.request"
                       "datomic.*"}}})

#?(:clj
   (defn configure-logging!
     "Configure clojure logging for this project. `config` is the log config map."
     ([] (configure-logging! default-log-config))
     ([config]
      (let [merged-config (assoc config
                                 :middleware [(pretty-middleware #(with-out-str (pprint %)))]
                                 :appenders {:println (appenders/println-appender {:stream :*out*})
                                             :spit (appenders/spit-appender
                                                    {:fname (get config :logfile "app.log")})}
                                 :output-fn  custom-output-fn)]
        (log/merge-config! merged-config)
        (log/debug "Configured Timbre with " (p merged-config))
        merged-config))))
