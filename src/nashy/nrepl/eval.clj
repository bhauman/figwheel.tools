(ns nashy.nrepl.eval
  (:require
   [nashy.utils :as utils]
   [nashy.repl :refer [thread-cljs-repl repl-running? repl-eval kill-repl]]
   [clojure.tools.nrepl.misc :as nrepl-misc]
   [cljs.repl.nashorn :as nash]
   [clojure.core.async :refer [chan put! go go-loop <!! <! >! >!! close! take! timeout] :as as]
   [clojure.tools.nrepl.transport :as transport]
   [clojure.tools.nrepl.middleware :as mid]))

#_ (remove-ns 'nashy.nrepl.eval)

;; middleware development here

(def ^:dynamic *initialize-wait-time* 5000)

(def ^:dynamic *msg* nil)

;; for testing purposes
(def *simulate-blocking-eval (atom false))

(defn out-message-type [state message] (:type message))

(defmulti process-eval-out-message #'out-message-type)

(defn -->! [out state msg]
  (put! out (process-eval-out-message state msg)))

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
           {:err (str (.getMessage exception) "\n")}]
   ::nrepl-message nrepl-message})

(defmethod process-eval-out-message ::interrupt-response
  [state {:keys [::nrepl-message ::interrupt-status]}]
  {::send [(-> (select-keys nrepl-message [:interrupt-id])
               (assoc :status interrupt-status))]
   ::nrepl-message nrepl-message})

(defn interrupt-status [eval-out-msg running-nrepl-msg]
  (assert (= (:op eval-out-msg) "interrupt"))
  (assert running-nrepl-msg)
  (if (and
       (:interrupt-id eval-out-msg)
       (not= (:id running-nrepl-msg)
             (:interrupt-id eval-out-msg)))
    "interrupt-id-mismatch"
    "interrupted"))

;; this is here mostly for development purposes
;; !interesting feature! two or three interrupts in a row
;; can signal a kill
(defn interrupt-handler [in {:keys [interrupt-chan]}]
  (let [out (chan)]
    (go-loop []
      (if-let [msg (<! in)]
        (do
          (if (= (:op msg) "interrupt")
            (put! interrupt-chan msg)
            (put! out msg))
          (recur))
        (close! out)))
    out))

;; returns a channel
(defn eval-parsed-form [{:keys [last-eval-msg eval-out thread-repl interrupt-chan] :as state}
                        out nrepl-eval-msg parsed-form]
  ;; perhaps do this at the end
  (prn :handle-eval parsed-form)
  (repl-eval thread-repl (:source parsed-form))
  (go-loop []
    (if-let [[eval-out-msg ch] (as/alts! [(if @*simulate-blocking-eval
                                            (chan)
                                            eval-out) 
                                          interrupt-chan])]
      (do
        (prn :running )
        (condp = ch
          
          eval-out
          (do
            (-->! out state
                  (assoc eval-out-msg
                         ::parsed-form parsed-form
                         ::nrepl-message nrepl-eval-msg))
            (when-not (#{:nashy.repl/eval-value :nashy.repl/eval-error} (:type eval-out-msg))
              (recur)))
          
          interrupt-chan
          (let [int-status (interrupt-status eval-out-msg nrepl-eval-msg)]
            (-->! out state
                  {:type ::interrupt-response
                   ::interrupt-status int-status
                   ::nrepl-message eval-out-msg})
            (-->! out state
                  {:type ::done
                   ::nrepl-message eval-out-msg})
            (if (= int-status "interrupted")
              ::interrupted
              (recur)))))
      ;; on close we are killed
      ::killed)))

(defn handle-all-evals [{:keys [last-eval-msg] :as state} out nrepl-eval-msg]
  (let [parsed-forms (utils/read-forms (:code nrepl-eval-msg))]
    (go-loop [[parsed-form & xs] parsed-forms]
      (prn :parsed-form parsed-form)
      (if-not parsed-form
        (do
          (prn :finished)
          (swap! last-eval-msg #(do % nrepl-eval-msg))
          (-->! out state {:type ::done ::nrepl-message nrepl-eval-msg}))
        (if-not (:exception parsed-form)
          (do
            (prn :before-handle-eval parsed-form)
            (let [res (<! (eval-parsed-form state out nrepl-eval-msg parsed-form))]
              (condp = res
                ::killed      (recur [])
                ::interrupted (recur [])
                (recur xs))))
          (do
            (prn :except parsed-form)
            (-->! out state {:type ::read-exception
                             :exception (:exception parsed-form)
                             ::parsed-form parsed-form
                             ::nrepl-message nrepl-eval-msg})
            ;; we could/should bail here and ignore the rest of the forms
            ;; as this seems like expected behavior and gives the user output that is easier
            ;; to understand and
            ;; why feed any forms after a bad form? it's just asking for trouble
            #_(recur [])
            (recur xs)))))))

(defn kill! [{:keys [eval-out interrupt-chan thread-repl] :as state}]
  (kill-repl thread-repl)
  (Thread/sleep 100)
  (close! eval-out)
  (close! interrupt-chan))

(defn eval-handler [in {:keys [eval-out last-eval-msg interrupt-chan] :as state}]
  (let [out (chan)]
    (go-loop []
      (if-let [[v ch] (as/alts! [in eval-out interrupt-chan])]
        (do
          (condp = ch
            in
            (if (= (:op v) "eval")
              (<! (handle-all-evals state out v))
              (>! out v))
            ;; lingering output from the repl normally print output we will associate it
            ;; with the last message sent
            ;; we add a sentinal flag below to help us reason about the behavior of the
            ;; system during dev and test
            eval-out
            (-->! out state
                  (->> (assoc @last-eval-msg :bypass-sentinal true)
                       (assoc v ::nrepl-message)))
            ;; when interupt comes here we are waiting for input and thus idly accepting
            ;; new eval requests
            interrupt-chan
            (do
              (-->! out state {:type ::interrupt-response
                               ::interrupt-status "session-idle"
                               ::nrepl-message v})
              (-->! out state {:type ::done
                               ::nrepl-message v})))
          (recur))
        ;; on close kill-signal!
        (do (kill! state) (close! out))))
    out))

(defn done? [sq]
  (->> sq :nashy.nrepl.eval/send last :status (= "done")))

(defn async-take-upto [pred? & chs]
  (let [out (chan)]
    (go-loop []
      (let [[v c] (as/alts! chs)]
        (cond
          (nil? v) (close! out)
          (pred? v) (do (>! out v) (close! out))
          :else (recur))))
    out))

(defn get-result
  ([result-chan]
   (get-result result-chan 3000))
  ([result-chan tmout]
   (as/into [] (async-take-upto done? result-chan (timeout tmout)))))

#_(get-result (let [ch (chan)]
                (future (do
                          (Thread/sleep 100)
                          (as/onto-chan ch [{} {}
                                            {:nashy.nrepl.eval/send [{:status "done"}]}
                                            {} {}])))
                
                ch)
              500)

;; TODO
;; tests for "interrupt" which should return done
;; tests for kill

;; add options
;; add options validation to thread-repl
;; rename this fn
;; perhaps take an in and return an out


(defn evaluate-cljs-new [forward-handler repl-env-thunk]
  (when-not repl-env-thunk
    (throw (ex-info "No REPL ENV provided" {})))
  (let [eval-out       (chan)
        in             (chan)
        interrupt-chan (chan)
        state {:eval-out        eval-out
               :interrupt-chan  interrupt-chan
               :last-eval-msg   (atom {})
               :thread-repl     (thread-cljs-repl repl-env-thunk #(put! eval-out %))
               :forward-handler forward-handler}
        out   (-> in
                  (interrupt-handler state)
                  (eval-handler state))
        ;; initialize with a single message and block on it
        ;; this could be initialization code but ... we don't want anything that
        ;; can fail ...
        _  (put! in {:op "eval" :code "1"})
        ;; we could have a timeout here
        ;; its default init code
        _  (<!! (get-result out 20000))
        ;; this is just a take-all for now
        _out_loop (as/reduce (fn [state msg] (forward-handler msg) state) state out)]
    {:input-chan in
     :interrupt-chan interrupt-chan}))

;; development code

(comment
  

  (do ;; setup
    (def sample-data {:id "91db8f85-06a9-431b-87e9-2f8fed2775cc",
                      :session (atom {:id "964ef60f-2a44-468f-9807-a3585cd953a6"})
                      :transport :example})
    (def sample-data2 {:id "91db8f85-06a9-data2-87e9-2f8fed2775cc",
                      :session (atom {:id "964ef60f-2a44-468f-9807-a3585cd953a6"})
                      :transport :example})
    (def sample-mark {:id "91db8f85-06a9-mark-87e9-2f8fed2775cc",
                      :session (atom {:id "964ef60f-2a44-468f-9807-a3585cd953a6"})
                      :transport :example})
    (declare evaluator)

    (defn kill []
      (when (:input-chan @#'evaluator)
        (close! (:input-chan @#'evaluator))))
    
    (when evaluator (kill))
    
    (def res (atom []))
    (def evaluator (time
                    (-> (fn [out-msg] (swap! res conj out-msg))
                       (evaluate-cljs-new nash/repl-env))))

    
    (defn help* [msg]
      (reset! @#'res [])
      (put! (:input-chan @#'evaluator) msg)
      (Thread/sleep 200)
      @res)
    
    (defn help [msg]
      (map ::send (help* msg)))
    
    (defn response [msg]
      (map send-on-transport-msgs (help* msg))))


  
  (help (assoc sample-data2 :op "eval" :code "(+ 1 2)"))

  
  (reset! *simulate-blocking-eval true)
  (help* (assoc sample-mark :op "interrupt" :interrupt-id "asdf"))
  
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

;; XXX possible temporary until we figure out the environment story
(def ^:dynamic *cljs-evaluator* nil)

(defn cljs-eval [h]
  (fn [{:keys [op session interrupt-id id transport] :as msg}]
    (condp = op
      "eval"
      (do
        (when-not (contains? @session #'*cljs-evaluator*)
          (prn :creating-evaluator)
          (let [new-evaluator (-> h
                                  send-on-transport-handler
                                  (evaluate-cljs-new nash/repl-env))]
            ;; on interupt we will need to clean up
            (swap! session assoc #'*cljs-evaluator* new-evaluator))
          (prn :finished-creating-evaluator))
        (binding [*msg* msg] ;; <-- this is superstition
          (if-let [evaluator (get @session #'*cljs-evaluator*)]
            (put! (:input-chan evaluator) msg)
            (h msg))))
      "interrupt" ;; <-- this message should be able to take a "kill" param
      (binding [*msg* msg] ;; <-- this is superstition
        (when-let [evaluator (get @session #'*cljs-evaluator*)]
          (prn :interrupt)
          (put! (:interrupt-chan evaluator) msg)))
      ;; we also need a notion of kill, our environments need to be
      ;; killed and restarted. Creation and killing can be handled in a
      ;; completely different middleware - cljs-environment middleware
      ;; for example
      ;; "kill-eval"
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
                       "root-ex" "The type of the root exception thrown, if any. If present, then `values` will be absent."}
             "interrupt"
             {:doc "Attempts to interrupt some code evaluation."
              :requires {"session" "The ID of the session used to start the evaluation to be interrupted."}
              :optional {"interrupt-id" "The opaque message ID sent with the original \"eval\" request."}
              :returns {"status" "'interrupted' if an evaluation was identified and interruption will be attempted
'session-idle' if the session is not currently evaluating any code
'interrupt-id-mismatch' if the session is currently evaluating code sent using a different ID than specified by the \"interrupt-id\" value "}}}}}
 )















