(ns figwheel.tools.nrepl
  (:require
   [cljs.analyzer :as ana]
   [clojure.core.async :refer
    [chan put! go go-loop <!! <! >! >!! close! take! timeout] :as as]
   [clojure.tools.nrepl.middleware :as mid]
   [clojure.tools.nrepl.misc :as nrepl-misc]
   [clojure.tools.nrepl.transport :as transport]
   [figwheel.tools.nrepl.eval :refer [log] :as ne]))

(def ^:dynamic *msg* nil)
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
          (swap! session (partial merge {;#'*msg* *msg*
                                         #'*original-ns* *original-ns*
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

#_(mid/set-descriptor! #'cljs-eval
 {:requires #{"clone" "close"}
  :expects #{}
  :handles {"eval"
            {:doc "Evaluates code."
             :requires {"code" "The code to be evaluated."
                        "session" "The ID of the session within which to evaluate the code."}
             :optional {"id" "An opaque message ID that will be included in responses related to the evaluation, and which may be used to restrict the scope of a later \"interrupt\" operation."
                        "eval" "A fully-qualified symbol naming a var whose function value will be used to evaluate [code], instead of `clojure.core/eval` (the default)."
                        "file" "The path to the file containing [code]. `clojure.core/*file*` will be bound to this."
                        "line" "The line number in [file] at which [code] starts."
                        "column" "The column number in [file] at which [code] starts."}
             :returns {"ns" "*ns*, after successful evaluation of `code`."
                       "values" "The result of evaluating `code`, often `read`able. This printing is provided by the `pr-values` middleware, and could theoretically be customized. Superseded by `ex` and `root-ex` if an exception occurs during evaluation."
                       "ex" "The type of exception thrown, if any. If present, then `values` will be absent."
                       "root-ex" "The type of the root exception thrown, if any. If present, then `values` will be absent."}
             "interrupt"
             {:doc "Attempts to interrupt some code evaluation."
              :requires {"session" "The ID of the session used to start the evaluation to be interrupted."}
              :optional {"interrupt-id" "The opaque message ID sent with the original \"eval\" request."}
              :returns {"status" "'interrupted' if an evaluation was identified and interruption will be attempted
'session-idle' if the session is not currently evaluating any code
'interrupt-id-mismatch' if the session is currently evaluating code sent using a different ID than specified by the \"interrupt-id\" value "}}}}}
 )
