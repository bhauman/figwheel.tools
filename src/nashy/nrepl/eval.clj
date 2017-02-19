(ns nashy.nrepl.eval
  (:require
   [nashy.utils :as utils]
   [nashy.repl :refer [thread-cljs-repl repl-running? repl-eval kill-repl]]
   [clojure.tools.nrepl.misc :as nrepl-misc]
   [cljs.repl.nashorn :as nash]
   [clojure.core.async :refer [chan put! go-loop <!! <! >! >!! close! take!] :as as]
   [clojure.tools.nrepl.transport :as transport]
   [clojure.tools.nrepl.middleware :as mid]))

#_ (remove-ns 'nashy.nrepl.eval)

;; middleware development here

(def ^:dynamic *initialize-wait-time* 5000)

(def ^:dynamic *msg* nil)

(defn out-message-type [state message] (:type message))

(defmulti process-eval-out-message #'out-message-type)

(defmethod process-eval-out-message :nashy.repl/eval-value [state {:keys [value] :as msg}]
  (-> (select-keys msg [::nrepl-message])
      (assoc ::send [(merge
                     (select-keys (::parsed-form msg)
                                  [:source :line :column :end-line :end-column])
                     (select-keys msg [:value :printed-value :ns]))])))

(defmethod process-eval-out-message ::done [state {:keys [value] :as msg}]
  (-> (select-keys msg [::nrepl-message])
      (assoc ::send [{:status "done"}])))

(defmethod process-eval-out-message :nashy.output-capture/output
  [state {:keys [::nrepl-message channel text]}]
  {::send [{channel text}]
   ::nrepl-message nrepl-message})

(defmethod process-eval-out-message ::read-exception [state {:keys [::nrepl-message exception]}]
  {::send [{:status "eval-error"
            :ex (-> exception class str)
            :root-ex (-> (#'clojure.main/root-cause exception) class str)}
           {:err (str (.getMessage exception) "\n")}]
   ::nrepl-message nrepl-message})

(defmethod process-eval-out-message :nashy.repl/eval-warning
  [state {:keys [::nrepl-message ::parsed-form message] :as msg}]
  (let [warning-msg (merge
                     (merge-with #(+ %1 (dec %2))
                                 (select-keys parsed-form [:line :column :end-line :end-column])
                                 (select-keys msg         [:line :column :end-line :end-column]))
                     (assoc (select-keys msg [:ns :file :message])
                            :status "eval-warning"))
        message (str message " at " (utils/format-line-column warning-msg))]
    {::send [{:err (str message "\n")} warning-msg]
     ::nrepl-message nrepl-message}))

(defmethod process-eval-out-message :nashy.repl/eval-error
  [state {:keys [::nrepl-message exception] :as msg}]
  (prn :exception msg)
  {::send [(cond-> {:status "eval-error"}
             (instance? Exception exception)
             (assoc :ex (-> exception class str)
                    :root-ex (-> (#'clojure.main/root-cause exception) class str)))
           {:err (str (.getMessage exception))}]
   ::nrepl-message nrepl-message})

(defmethod process-eval-out-message ::interrupted [state msg]
  (assoc msg :processed true))

(defn map-chan [in f]
  (let [out (chan 1 (map f))]
    (as/pipe in out)))

(defn initialize-cljs-code []
  (pr-str
   '(do (set! cljs.core/*ns* 'user.cljs))))

(defn interupt! [state msg]
  ;; kill repl and clean up channels
  )

#_(defn interrupt-handler [in state]
  (let [out (chan)]
    (go-loop []
      (when-let [{:keys [op] :as msg} (<! in)]
        (if (= op "interrupt")
          (do
            (interrupt! state msg)
            (close! out))
          (do
            (put! out msg)
            (recur)))))))

;; returns a channel
(defn eval-parsed-form [{:keys [last-eval-msg eval-out thread-repl] :as state} out nrepl-eval-msg parsed-form]
  ;; perhaps do this at the end
  (prn :handle-eval parsed-form)
  (repl-eval thread-repl (:source parsed-form))
  (go-loop []
    (when-let [eval-out-msg (<! eval-out)]
      (put! out (process-eval-out-message state (assoc eval-out-msg
                                                       ::parsed-form parsed-form
                                                       ::nrepl-message nrepl-eval-msg)))
      (prn :running )
      (when-not (#{:nashy.repl/eval-value :nashy.repl/eval-error} (:type eval-out-msg))
        (recur)))))

(defn handle-all-evals [{:keys [last-eval-msg] :as state} out nrepl-eval-msg]
  (let [parsed-forms (utils/read-forms (:code nrepl-eval-msg))]
    (go-loop [[parsed-form & xs] parsed-forms]
      (prn :parsed-form parsed-form)
      (if-not parsed-form
        (do
          (prn :finished)
          (swap! last-eval-msg #(do % nrepl-eval-msg))
          (put! out (process-eval-out-message state {:type ::done ::nrepl-message nrepl-eval-msg})))
        (if-not (:exception parsed-form)
          (do
            (prn :before-handle-eval parsed-form)
            (<! (eval-parsed-form state out nrepl-eval-msg parsed-form))
            (recur xs))
          (do
            (prn :except parsed-form)
            (put! out (process-eval-out-message state {:type ::read-exception
                                                       :exception (:exception parsed-form)
                                                       ::parsed-form parsed-form
                                                       ::nrepl-message nrepl-eval-msg}))
            (recur xs)))))))

(defn eval-handler [in {:keys [eval-out last-eval-msg] :as state}]
  (let [out (chan)]
    (go-loop []
      (when-let [[v ch] (as/alts! [in eval-out])]
        (condp = ch
          in
          (if (= (:op v) "eval")
            (<! (handle-all-evals state out v))
            (>! out v))
          ;; lingering output from the repl normally print output
          eval-out
          (put! out (process-eval-out-message state (assoc v ::nrepl-message @last-eval-msg))))
        (recur)))
    out))

(defn evaluate-cljs-new [forward-handler repl-env-thunk initial-eval-script]
  (when-not repl-env-thunk
    (throw (ex-info "No REPL ENV provided" {})))
  (let [eval-out (chan)
        in       (chan)
        state {:eval-out        eval-out
               :last-eval-msg   (atom {})
               :thread-repl     (thread-cljs-repl repl-env-thunk #(put! eval-out %))
               :forward-handler forward-handler}
                                        ;_in_loop  (as/reduce handle-in-msg state in)
        out   (eval-handler in state)
        _     (put! in {:op "eval" :code initial-eval-script})
        ;; _     (drain out)
        ;; this is just a take-all for now
        _out_loop (as/reduce (fn [state msg] (forward-handler msg) state) state out)]
    ;; cljs needs to compile and load its tools into the env
    (Thread/sleep *initialize-wait-time*)
    (fn [msg]
      (if (#{"eval" "interrupt"} (:op msg))
        (put! in msg)
        (forward-handler msg)))))

;; development code

(comment
  (def sample-data {:id "91db8f85-06a9-431b-87e9-2f8fed2775cc",
                    :session (atom {:id "964ef60f-2a44-468f-9807-a3585cd953a6"})
                    :transport :example})

  (def res (atom []))
  (def evaluator (-> (fn [out-msg] (swap! res conj out-msg))
                     (evaluate-cljs-new nash/repl-env)))

  (defn help [msg]
    (reset! res [])
    (evaluator msg)
    (Thread/sleep 200)
    (map ::send @res))

  (defn response [msg]
    (reset! res [])
    (evaluator msg)
    (Thread/sleep 200)
    (map send-on-transport-msg @res))

  (help (assoc sample-data :op "eval" :code "(+ 1 2)"))
  (help (assoc sample-data :op "eval" :code "(+ 1 4) (prn 7) 1"))
  (help (assoc sample-data :op "eval" :code "(+ 1 4) 

      a ) (prn 7) 1"))

  (help (assoc sample-data :op "eval" :code "(defn)"))

  (response (assoc sample-data :op "eval" :code "(+ 1 2)"))
  
  )

(defn send-on-transport-msgs [{:keys [::nrepl-message] :as out-msg}]
  (->> (::send out-msg)
       (mapv (partial nrepl-misc/response-for nrepl-message))))

(defn send-on-transport! [{:keys [::nrepl-message] :as out-msg}]
  (when (and (::send out-msg) (:transport nrepl-message))
    (prn :send-on-transport (::send out-msg))
    (->> (send-on-transport-msgs out-msg)
         (mapv #(do (prn :message-before-send %) %))
         (mapv #(transport/send (:transport nrepl-message) %)))))

(defn send-on-transport-handler [h]
  (fn [out-msg]
    (prn :trasport-handler (::send out-msg))
    (if (::send out-msg)
      (send-on-transport! out-msg)
      (h out-msg))))

;; a map from session ids to evaluators
;; infinitely more understandable and controllable
;; to manage your own session state

(def cljs-evaluators (atom {}))

(defn cljs-eval [h]
  (fn [{:keys [op session interrupt-id id transport] :as msg}]
    (if (= op "eval")
      (let [session-id (:id (meta session))]
        (when-not (contains? @cljs-evaluators session-id)
          (prn :creating-handler)
          (let [new-evaluator (-> h
                                  send-on-transport-handler
                                  (evaluate-cljs-new nash/repl-env (initialize-cljs-code)))]
            ;; on interupt we will need to clean up
            (swap! cljs-evaluators assoc session-id new-evaluator))
          (prn :finished-creating-handler))
        (binding [*msg* msg]
          ((get @cljs-evaluators session-id) msg)))
      (h msg))))

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















