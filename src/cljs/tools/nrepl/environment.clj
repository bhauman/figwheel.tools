(ns cljs.tools.nrepl.environment
  (:require
   [cljs.repl.nashorn :as nash]
   [cljs.env :as env]
   [clojure.tools.nrepl.transport :as transport]
   [clojure.tools.nrepl.misc :as nrepl-misc]))

(def ^:dynamic *config* nil)

(def ^:dynamic *cljs-build-id* nil)

(def ^:dynamic *cljs-repl-env* nil)

(def ^:dynamic *cljs-compiler-env* nil)

(def ^:dynamic *cljs-compiler-options* nil)

(def ^:dynamic *cljs-source-paths* nil)

;; creates a repl-env constructor
(defmulti repl-env :repl-env)

(defmethod repl-env :nashorn [build-config]
  #(nash/repl-env* (select-keys (:compiler build-config) [:output-to :output-dir])))

(def sample-config
  '{nashy.nrepl.cljs-environment
    {:default-build-id :dev
     :builds {:dev {:source-paths ["src"]
                    :repl-env :nashorn
                    :compiler {:main nashy.core
                               :asset-path "js/compiled/out"
                               :output-to "resources/public/js/compiled/nashy.js"
                               :output-dir "resources/public/js/compiled/out"
                               :source-map-timestamp true
                              ;; To console.log CLJS data-structures make sure you enable devtools in Chrome
                              ;; https://github.com/binaryage/cljs-devtools
                              ;; :preloads [devtools.preload]
                              }}}}})

(defn get-build-config [session build-id]
  (get-in @session [#'*config* 'nashy.nrepl.cljs-environment :builds build-id]))

(defn default-build-id [config]
  ;; TODO first opt none build  
  (:default-build-id config))

(defn switch-to-build-id! [session build-id]
  (let [build-id
        (default-build-id (get-in @session [#'*config* 'nashy.nrepl.cljs-environment]))
        build-config (get-build-config session build-id)
        compiler-env (env/default-compiler-env (:compiler build-config))]
    (swap! @session assoc
           #'*cljs-build-id*         build-id
           #'*cljs-repl-env*         (repl-env build-config)
           #'*cljs-compiler-env*     compiler-env
           #'*cljs-compiler-options* (:compiler build-config)
           #'*cljs-source-paths*     (:source-paths build-config))))

(defn setup-session! [config session]
  (when-not (@session #'*config*)
    (let [cfg (or config *config* sample-config)]
      (swap! @session assoc #'*config* cfg)))
  (when-not (@session #'*cljs-build-id*)
    (let [build-id
          (default-build-id (get-in @session [#'*config* 'nashy.nrepl.cljs-environment]))]
      (switch-to-build-id! build-id))))

(defn send [orig-msg msg]
  (transport/send (:transport orig-msg)
                  (nrepl-misc/response-for orig-msg msg)))

(defn ls-build-ids [session nrepl-msg]
  (send nrepl-msg {:status ["done"]
                   :build-env-ids
                   (->> (get-in @session [#'*config* 'nashy.nrepl.cljs-environment :builds])
                        keys
                        (mapv name))}))

(defn current-build-id [session nrepl-msg]
  (send nrepl-msg
        {:status ["done"]
         :build-id (@session #'*cljs-build-id*)}))

(defn send-status [nrepl-msg status]
  (send nrepl-msg {:status ["done" status]}))

(defn switch-to-build-id [session nrepl-msg]
  (let [curr-build-id (@session #'*cljs-build-id*)]
    (cond
      (not (:build-id nrepl-msg))
      (send-status nrepl-msg "missing-build-id-param")
      (not (get-build-config session (:build-id nrepl-msg)))
      (send-status nrepl-msg "no-such-build-id")
      :else
      (let [build-id      (:build-id nrepl-msg)]
        (switch-to-build-id! build-id)
        (if (= build-id (@session #'*cljs-build-id*))
          (send nrepl-msg {:build-id build-id
                           :status ["done"]})
          (send-status nrepl-msg "failed"))))))

(defn cljs-env
  ([h] (wrap-env h nil))
  ([h config]
   (fn [{:keys [op session interrupt-id id transport] :as nrepl-msg}]
     (setup-session! config session)
     (condp = op
       "ls-build-ids"
       (ls-build-ids session nrepl-msg)
       "current-build-id"
       (current-build-id session nrepl-msg)
       "switch-to-build-id"
       (switch-to-build-id session nrepl-msg)
       :else
       (h nrepl-msg)))))
