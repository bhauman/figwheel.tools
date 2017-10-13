(ns figwheel.tools.nrepl
  (:require
   [cljs.analyzer :as ana]
   [clojure.core.async :refer
    [chan put! go go-loop <!! <! >! >!! close! take! timeout] :as as]
   [clojure.tools.nrepl.middleware :as mid]
   [clojure.tools.nrepl.misc :as nrepl-misc]
   [clojure.tools.nrepl.transport :as transport]
   [figwheel.tools.repl.utils :refer [log]]
   [figwheel.tools.nrepl.eval :as ne]))

(def ^:dynamic *cljs-evaluator* nil)
(def ^:dynamic *original-ns* nil)

(defn cljs-repl* [repl-env options]
  (try
    (log :creating-evaluator)
    (let [new-evaluator (-> identity
                            ne/send-on-transport-handler
                            (ne/evaluate-cljs-new repl-env options))]
      ;; on interupt we will need to clean up
      (set! *cljs-evaluator* new-evaluator)
      (set! *original-ns* *ns*)
      (set! *ns* (find-ns ana/*cljs-ns*)))
    (log :finished-creating-evaluator)
    (println "To quit, type:" :cljs/quit)
    (catch Exception e
      (set! *cljs-evaluator* nil)
      (throw e))))

(defn cljs-repl [repl-env & {:as options}]
  (cljs-repl* repl-env options))

;; middleware
(defn wrap-cljs-repl [h]
  (fn [{:keys [op session interrupt-id id transport] :as msg}]
    (if-let [evaluator (get @session #'*cljs-evaluator*)]
      (do 
        (when (ne/cljs-quit-msg? msg)
          (let [orig-ns (@session #'*original-ns*)]
            (swap! session assoc
                   #'*cljs-evaluator* nil
                   #'*ns* orig-ns
                   #'*original-ns* nil)
            (transport/send transport
                            (nrepl-misc/response-for msg
                                                     :value "nil"
                                                     :printed-value 1
                                                     :ns (str orig-ns)))))
        (condp = op
          "load-file"
          (let [msg (assoc msg
                           :op "eval"
                           :code (format "(load-file %s)"
                                         (pr-str (get msg :file-path))))]
            (put! (:input-chan evaluator) msg))
          "eval"
          (put! (:input-chan evaluator) msg)
          "interrupt"
          (put! (:interrupt-chan evaluator) msg)
          (h msg)))
      (do
        (when-not (contains? @session #'*cljs-evaluator*)
          (swap! session (partial merge {#'*original-ns* *original-ns*
                                         #'*cljs-evaluator* *cljs-evaluator*})))
        (h msg)))))

(mid/set-descriptor! #'wrap-cljs-repl
  {:requires #{"clone"}
   ; piggieback unconditionally hijacks eval and load-file and intterupt
   :expects #{"eval" "load-file" "interrupt"}
   :handles {}})

;; this is an example of nrepl eval middleware endpoint that serves
;; the same purpose as interuptible-eval but where CLJS is the endpoint
#_(defn cljs-eval [h]
  (fn [{:keys [op session interrupt-id id transport] :as msg}]
    (condp = op
      "eval"
      (do
        (when-not (contains? @session #'*cljs-evaluator*)
          (log :creating-evaluator)
          (let [new-evaluator (-> h
                                  ne/send-on-transport-handler
                                  (ne/evaluate-cljs-new nash/repl-env {}))]
            ;; on interupt we will need to clean up
            (swap! session assoc #'*cljs-evaluator* new-evaluator))
          (log :finished-creating-evaluator))
        (binding [*msg* msg] ;; <-- this is superstition
          (if-let [evaluator (get @session #'*cljs-evaluator*)]
            (put! (:input-chan evaluator) msg)
            (h msg))))
      "interrupt" ;; <-- this message should be able to take a "kill" param
      (binding [*msg* msg] ;; <-- this is superstition
        (when-let [evaluator (get @session #'*cljs-evaluator*)]
          (log :interrupt)
          (put! (:interrupt-chan evaluator) msg)))
      ;; we also need a notion of kill, our environments need to be
      ;; killed and restarted. Creation and killing can be handled in a
      ;; completely different middleware - cljs-environment middleware
      ;; for example
      ;; "kill-eval"
      (h msg))))

