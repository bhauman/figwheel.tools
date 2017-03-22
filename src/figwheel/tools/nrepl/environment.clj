(ns figwheel.tools.nrepl.environment
  (:require
   [cljs.repl.nashorn :as nash]
   [cljs.env :as env]
   [clojure.tools.nrepl.transport :as transport]
   [clojure.tools.nrepl.middleware :as mid]
   [clojure.tools.nrepl.misc :as nrepl-misc]))

(def ^:dynamic *config* nil)

(def ^:dynamic *cljs-build-config* nil)

(def ^:dynamic *cljs-repl-env* nil)

(def ^:dynamic *cljs-compiler-env* nil)

;; creates a repl-env constructor
(defmulti repl-env :repl-env)

(defmethod repl-env :nashorn [build-config]
  #(nash/repl-env* (select-keys (:compiler build-config) [:output-to :output-dir])))

(def sample-config
  '{figwheel.tools.nrepl.environment
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
  (when-let [build-config (get-in @session [#'*config* 'figwheel.tools.nrepl.environment :builds build-id])]
    (assoc build-id :id build-id)))

(defn default-build-id [config]
  ;; TODO first opt none build  
  (:default-build-id config))

(defn set-build-config! [session build-config]
  (let [build-config (assoc build-config ::uuid (str (java.util.UUID/randomUUID)))]
    (swap! @session assoc
           #'*cljs-build-config* build-config
           #'*cljs-repl-env*     (repl-env build-config)
           #'*cljs-compiler-env* (env/default-compiler-env (:compiler build-config)))))

(defn switch-to-build-id! [session build-id]
  (let [build-id
        (default-build-id (get-in @session [#'*config* 'figwheel.tools.nrepl.environment]))
        build-config (get-build-config session build-id)]
    (set-build-config! session build-config)))

(defn setup-session! [config session]
  (when-not (@session #'*config*)
    (let [cfg (or config *config* sample-config)]
      (swap! @session assoc #'*config* cfg)))
  (when-not (@session #'*cljs-build-config*)
    (let [build-id
          (default-build-id (get-in @session [#'*config* 'figwheel.tools.nrepl.environment]))]
      (switch-to-build-id! build-id))))

(defn send [orig-msg msg]
  (transport/send (:transport orig-msg)
                  (nrepl-misc/response-for orig-msg msg)))

(defn ls-build-configs [session nrepl-msg]
  (send nrepl-msg {:status ["done"]
                   :build-configs
                   (get-in @session [#'*config* 'figwheel.tools.nrepl.environment :builds])}))

(defn current-build-config [session nrepl-msg]
  (send nrepl-msg
        {:status ["done"]
         :build-config #'*cljs-build-config*}))

(defn send-status [nrepl-msg status]
  (send nrepl-msg {:status ["done" status]}))

(defn switch-to-build-id [session nrepl-msg]
  (let [curr-build-id (:id (@session #'*cljs-build-config*))]
    (cond
      (not (:build-id nrepl-msg))
      (send-status nrepl-msg "missing-build-id-param")
      (not (get-build-config session (:build-id nrepl-msg)))
      (send-status nrepl-msg "no-such-build-id")
      :else
      (let [build-id      (:build-id nrepl-msg)]
        (switch-to-build-id! build-id)
        (if (= build-id (:id (@session #'*cljs-build-config*)))
          (send nrepl-msg {:build-id build-id
                           :status ["done"]})
          (send-status nrepl-msg "failed"))))))

(defn set-build-config [session nrepl-msg]
  ;; TODO validate config
  (cond
    (not (:config nrepl-msg))
    (send-status nrepl-msg "missing-config-param")
    ;; validate here
    :else
    (do
      (set-build-config! session (:config nrepl-msg))
      (send nrepl-msg {:status ["done" "success"]}))))

(defn cljs-env
  ([h] (cljs-env h nil))
  ([h config]
   (fn [{:keys [op session interrupt-id id transport] :as nrepl-msg}]
     (setup-session! config session)
     (condp = op
       "ls-build-configs"
       (ls-build-configs session nrepl-msg)
       "current-build-config"
       (current-build-config session nrepl-msg)
       "switch-to-build-id"
       (switch-to-build-id session nrepl-msg)
       "set-build-config"
       (set-build-config session nrepl-msg)
       :else
       (h nrepl-msg)))))


;; TODO finish docs
(mid/set-descriptor! #'cljs-env
 {:requires #{"clone" "close"}
  :expects #{"eval"}
  :handles {"ls-build-configs"
            {:doc "Lists the current CLJS build configs available to choose from."
             :requires {"session" "The ID of the session."}
             :optional {"id" "An opaque message ID that will be included in responses"}
             :returns {"build-configs" "A list of build configs"}
             "current-build-config"
             {:doc "Returns the build config for the current session cljs environment"
              
              :requires {"session" "The ID of the session."}
              :optional {"id" "An opaque message ID that will be included in responses"}
              :returns {"build-config" "A build config"}}
             "switch-to-build-id"
             {:doc "Given a build id sets the current config to be that id"
              :requires {"session" "The ID of the session."}
              :optional {"id" "An opaque message ID that will be included in responses"}}}}}
 )
