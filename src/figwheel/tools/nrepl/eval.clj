(ns figwheel.tools.nrepl.eval
  (:require
   [figwheel.tools.repl.utils :as utils :refer [log]]
   [figwheel.tools.repl.io.cljs-forms :refer [read-forms]]
   [figwheel.tools.repl :refer [thread-cljs-repl repl-running? repl-eval kill-repl]]
   [clojure.tools.nrepl.misc :as nrepl-misc]
   [clojure.core.async :refer [chan put! go go-loop <!! <! >! >!! close! take! timeout] :as as]
   [clojure.tools.nrepl.transport :as transport]))

;; for testing purposes
(def *simulate-blocking-eval (atom false))

(defn out-message-type [state message] (:type message))

(defmulti process-eval-out-message #'out-message-type)

(defn -->! [out state msg]
  (put! out (process-eval-out-message state msg)))

(defmethod process-eval-out-message :figwheel.tools.repl/eval-value [state {:keys [value] :as msg}]
  (-> (select-keys msg [::nrepl-message])
      (assoc ::send [(merge
                     (select-keys (::parsed-form msg)
                                  [:source :line :column :end-line :end-column])
                     (select-keys msg [:value :printed-value :ns]))])))

(defmethod process-eval-out-message ::done [state {:keys [value] :as msg}]
  (-> (select-keys msg [::nrepl-message])
      (assoc ::send [{:status "done"}])))

(defmethod process-eval-out-message :figwheel.tools.repl.io.print-writer/output
  [state {:keys [::nrepl-message channel text]}]
  {::send [{channel text}]
   ::nrepl-message nrepl-message})

(defmethod process-eval-out-message ::read-exception [state {:keys [::nrepl-message exception]}]
  {::send [{:status "eval-error"
            :ex (-> exception class str)
            :root-ex (-> (#'clojure.main/root-cause exception) class str)}
           {:err (str (.getMessage exception) "\n")}]
   ::nrepl-message nrepl-message})

(defmethod process-eval-out-message :figwheel.tools.repl/eval-warning
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

(defmethod process-eval-out-message :figwheel.tools.repl/eval-error
  [state {:keys [::nrepl-message exception] :as msg}]
  (log :exception msg)
  {::send [(cond-> {:status "eval-error"}
             (instance? Exception exception)
             (assoc :ex (-> exception class str)
                    :root-ex (-> (#'clojure.main/root-cause exception) class str)))
           {:err (str (.getMessage exception) "\n")}]
   ::nrepl-message nrepl-message})

(defmethod process-eval-out-message ::interrupt-response
  [state {:keys [::nrepl-message ::interrupt-status]}]
  {::send [(-> (select-keys nrepl-message [:interrupt-id])
               (assoc :status ["done" interrupt-status]))]
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
  (log :handle-eval parsed-form)
  (repl-eval thread-repl (:source parsed-form))
  (go-loop []
    (let [[eval-out-msg ch] (as/alts! [(if @*simulate-blocking-eval
                                            (chan)
                                            eval-out) 
                                       interrupt-chan])]
      (log :running eval-out-msg)
      (if eval-out-msg
        (condp = ch
          
          eval-out
          (do
            (-->! out state
                  (assoc eval-out-msg
                         ::parsed-form parsed-form
                         ::nrepl-message nrepl-eval-msg))
            (when-not (#{:figwheel.tools.repl/eval-value :figwheel.tools.repl/eval-error} (:type eval-out-msg))
              (recur)))
          
          interrupt-chan
          (let [int-status (interrupt-status eval-out-msg nrepl-eval-msg)]
            (-->! out state
                  {:type ::interrupt-response
                   ::interrupt-status int-status
                   ::nrepl-message eval-out-msg})
            (if (= int-status "interrupted")
              ::interrupted
              (recur))))
        ;; on close we are killed
        ::killed))))


(defn drain-eval-out-chan [eval-out]
  (let [tmout (timeout 10)]
    (go-loop [res []]
        (let [[v ch] (as/alts! [eval-out tmout])]
          (if (or (nil? v) (= tmout ch))
            res 
            (recur (conj res v)))))))

(defn process-latent-eval-out-message! [{:keys [last-eval-msg] :as state} out msg]
  (log :process-latent msg)
  (-->! out state
        (->> (assoc @last-eval-msg :latent-eval-out true)
             (assoc msg ::nrepl-message))))

(defn process-latent-eval-out-messages! [state out msgs]
  ;; only forward output for interrupted evaluations
  (doseq [msg (filter #(-> % :type :figwheel.tools.repl.io.print-writer/output) msgs)]
    (process-latent-eval-out-message! state out msg)))

(defn handle-all-evals [{:keys [last-eval-msg eval-out] :as state} out nrepl-eval-msg]
  ;; we haven't evaluated anything yet so if there are values waiting in the
  ;; out channel we need to get rid of them
  (process-latent-eval-out-messages! state out (<!! (drain-eval-out-chan eval-out)))
  (let [parsed-forms (read-forms (:code nrepl-eval-msg))]
    (go-loop [[parsed-form & xs] parsed-forms]
      (log :parsed-form parsed-form)
      (if-not parsed-form
        (do
          (log :finished)
          (swap! last-eval-msg #(do % nrepl-eval-msg))
          (-->! out state {:type ::done ::nrepl-message nrepl-eval-msg}))
        (if-not (:exception parsed-form)
          (do
            (log :before-handle-eval parsed-form)
            (let [res (<! (eval-parsed-form state out nrepl-eval-msg parsed-form))]
              (condp = res
                ::killed
                (do
                  (log :killed)
                  (swap! last-eval-msg #(do % nrepl-eval-msg))
                  (-->! out state {:type ::done ::nrepl-message nrepl-eval-msg})
                  ::killed)
                ::interrupted (recur [])
                (recur xs))))
          (do
            (log :except parsed-form)
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

(defn cljs-quit-msg? [{:keys [op code]}]
  (and (= op "eval") (.endsWith (.trim code) ":cljs/quit")))

(defn eval-handler [in {:keys [eval-out last-eval-msg interrupt-chan] :as state}]
  (let [out (chan)]
    (go
      (loop []
        (let [[v ch] (as/alts! [in eval-out interrupt-chan])]
          (cond
            (nil? v) nil ;; quit
            (and (= ch in) (cljs-quit-msg? v))
            (let [[res ch] (as/alts! [(handle-all-evals state out v) (timeout 500)])] ;; forward the quit message
              ;; but handle the case where we are already in a
              ;; hung state
              (-->! out state {:type ::done ::nrepl-message v}))
            (and (= ch in) (= (:op v) "eval"))
            (let [res (<! (handle-all-evals state out v))]
              (when-not (= res ::killed)
                (recur)))
            :else
            (do
              (condp = ch
                in (>! out v)
                eval-out (process-latent-eval-out-message! state out v)
                interrupt-chan
                (-->! out state {:type ::interrupt-response
                                 ::interrupt-status "session-idle"
                                 ::nrepl-message v}))
              (recur)))))
      (<! (timeout 100))
      (kill! state)
      (close! out))

    out))

(defn done? [sq]
  (let [st (->> sq :figwheel.tools.nrepl.eval/send last :status)]
    (or (= st "done")
        (first (filter #(= % "done") st)))))

(defn async-take-upto [filter-pred? end-pred? & chs]
  (let [out (chan)]
    (go-loop []
      (let [[v c] (as/alts! chs)]
        (cond
          (nil? v) (close! out)
          (not (filter-pred? v)) (recur)
          (end-pred? v) (do (>! out v) (close! out))
          :else (do (>! out v) (recur)))))
    out))

;; it will be common for the result chan to be filtered by an id
(defn get-result
  ([result-chan]
   (get-result result-chan 3000))
  ([result-chan tmout]
   (as/into [] (async-take-upto #(do % true) done? result-chan (timeout tmout)))))

(defn get-filtered-result
  ([pred result-chan]
   (get-filtered-result pred result-chan 3000))
  ([pred result-chan tmout]
   (as/into [] (async-take-upto pred done? result-chan (timeout tmout)))))

(defn evaluate-cljs-new [forward-handler repl-env-thunk options]
  (when-not repl-env-thunk
    (throw (ex-info "No REPL ENV provided" {})))
  (let [eval-out       (chan)
        in             (chan)
        interrupt-chan (chan)
        state {:eval-out        eval-out
               :interrupt-chan  interrupt-chan
               :last-eval-msg   (atom {})
               :thread-repl     (thread-cljs-repl repl-env-thunk options #(put! eval-out %))
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

(defn send-on-transport-msgs [{:keys [::nrepl-message] :as out-msg}]
  (->> (::send out-msg)
       (mapv (partial nrepl-misc/response-for nrepl-message))))

(defn send-on-transport! [{:keys [::nrepl-message] :as out-msg}]
  (when (and (::send out-msg) (:transport nrepl-message))
    (log :send-on-transport (::send out-msg))
    (->> (send-on-transport-msgs out-msg)
         (mapv #(do (log :message-before-send %) %))
         (mapv #(transport/send (:transport nrepl-message) %)))))

(defn send-on-transport-handler [h]
  (fn [out-msg]
    (log :transport-handler (::send out-msg))
    (if (::send out-msg)
      (send-on-transport! out-msg)
      (h out-msg))))

(comment

  (do ;; setup
    (def sample-data {:id  "91db8f85-06a9-431b-87e9-2f8fed2775cc",
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






