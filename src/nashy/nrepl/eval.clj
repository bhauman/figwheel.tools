(ns nashy.nrepl.eval
  (:require
   [nashy.repl :refer [thread-cljs-repl repl-running? repl-eval kill-repl]]
   [cljs.repl.nashorn :as nash]
   [clojure.core.async :refer [chan put! go-loop <!! <! >!! close! take!] :as as]
   [clojure.tools.nrepl.transport :as t]
   [clojure.tools.nrepl.middleware :as mid]))

;; middleware development here

(def ^:dynamic *initialize-wait-time* 3000)

(defn out-message-type [state message]
  (:type message))

(defmulti process-out-message #'out-message-type)

(defmethod process-out-message :nashy.repl/eval-value [state {:keys [value] :as msg}]
  (-> (select-keys msg [::nrepl-message])
      (assoc ::send (if (= value ":nashy.nrepl.eval/completed")
                      {:status "done"}
                      (select-keys msg [:value :printed-value :ns])))))

(defmethod process-out-message :nashy.output-capture/output
  [state {:keys [::nrepl-message channel text]}]
  {::send {channel text}
   ::nrepl-message nrepl-message})

(defmethod process-out-message :nashy.repl/eval-warning
  [state {:keys [::nrepl-message channel text]}]
  ;; lots of choices as to what to do here
  ;; but to be safe lets send an :err out
  ;; and send a warning response

  ;; we can save all the warnings under the id of the message up to a
  ;; certain maxlen with a timestamp in a priority map 

  ;; we can then intercept *w to deliver the warnings
  ;; and or respond to a message to deliver them
  
  {::send {channel text}
   ::nrepl-message nrepl-message})



(defmethod process-out-message ::interrupted [state msg]
  (assoc msg :processed true))

(defn handle-in-msg [{:keys [last-eval-msg thread-repl forward-handler] :as state} msg]
  #_(prn 1 msg)
  (condp = (:op msg)
    "eval"
    (do
      #_(prn 2 msg)
      (swap! last-eval-msg #(do % msg))
      (repl-eval thread-repl (:code msg))
      (repl-eval thread-repl ":nashy.nrepl.eval/completed"))
    "interrupt"
    (do (forward-handler {:type ::interrupted
                          ::nrepl-message msg})
        (kill-repl thread-repl)))
  state)

(defn handle-eval-out-msg [{:keys [last-eval-msg forward-handler] :as state} eval-out-msg]
  #_(prn 4 eval-out-msg)
  (-> (process-out-message state
                           (assoc eval-out-msg
                                  ::nrepl-message @last-eval-msg))
      forward-handler)
  state)

;; right now this assumes single complete clojure forms
(defn evaluate-cljs [forward-handler repl-env-thunk]
  (when-not repl-env-thunk
    (throw (ex-info "No REPL ENV provided" {})))
  (let [eval-out (chan)
        in       (chan)
        state {:last-eval-msg   (atom {})
               :thread-repl     (thread-cljs-repl repl-env-thunk #(put! eval-out %))
               :forward-handler forward-handler}
        _in_loop  (as/reduce handle-in-msg state in)
        _out_loop (as/reduce handle-eval-out-msg state eval-out)]
    ;; cljs needs to compile and load its tools into the env
    (Thread/sleep *initialize-wait-time*)
    (fn [msg]
      (if (#{"eval" "interrupt"} (:op msg))
        (put! in msg)
        (forward-handler msg)))))



;; development code

(defn work-helper [msg]
  (let [res (atom [])
        evaluator (-> (fn [out-msg] (swap! res conj out-msg))
                      (evaluate-cljs nash/repl-env)
                      )]
    #_(Thread/sleep 10000)
    (evaluator msg)
    #_(evaluator {:op "interrupt"})
    (Thread/sleep 5000)
    (evaluator msg)
    (Thread/sleep 1000)
    @res))

(def sample-data {:id "91db8f85-06a9-431b-87e9-2f8fed2775cc",
                  :session (atom {:id "964ef60f-2a44-468f-9807-a3585cd953a6"})
                  :transport :example})

(comment
  (work-helper (assoc sample-data :op "eval" :code "(+ 1 2)"))

  (work-helper (assoc sample-data :op "eval" :code "(prn 5)"))

  (def res (atom []))
  (def evaluator (-> (fn [out-msg] (swap! res conj out-msg))
                     (evaluate-cljs nash/repl-env)))

  (defn help [msg]
    (reset! res [])
    (evaluator msg)
    (Thread/sleep 300)
    @res)

  (help (assoc sample-data :op "eval" :code "(+ 1 2)"))
  

  
  )



#_(defn create-repl-eval-stack []
  (let [in (chan)]
    

    )

  )






(defn cljs-eval [h]
  (fn [{:keys [op session interrupt-id id transport] :as msg}]
    (if (= op "eval")
      (do
        ;; creating a session should provide a repl env
        ;; need a way to determine what kind of envs to create
        ;; this could be middleware after session create
        ;; we will have the ability to switch repls
        ;; if we create an env we need to close it
        (when-not (::repl-env @session)
          (swap! session assoc ::repl-env (nash/repl-env)))
        ;; this is the right place to set up a repl
        (when-not (::cljs-thread-repl @session)
          (swap! session assoc ::cljs-thread-repl (thread-cljs-repl (::repl-env @session) ))))
      
        (h msg))
    
    (prn msg)
    (h msg)))




(mid/set-descriptor! #'cljs-eval
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
                       "root-ex" "The type of the root exception thrown, if any. If present, then `values` will be absent."}}}}
 )















