(ns figwheel.tools.nrepl.eval-test
  (:require
   [figwheel.tools.nrepl.eval :as ne]
   [cljs.repl.nashorn :as nash]
   [clojure.core.async :refer [put! chan <!!] :as as]
   [clojure.test :refer :all]))

(def ^:dynamic *result* nil)

(def ^:dynamic *evaluator* nil)

(def ^:dynamic *dev* true)

(defn make-evaluator []
  (let [result (chan)
        evaluator
        (-> (fn [out-msg] (put! result out-msg))
            (ne/evaluate-cljs-new nash/repl-env {}))]
    {:result result
     :evaluator evaluator}))

(defn evaluate-env [f]
  (if *dev*
    (do
      (when-not *evaluator*
        (let [{:keys [result evaluator]} (make-evaluator)]
          (alter-var-root #'*result* (fn [x] result))
          (alter-var-root #'*evaluator* (fn [x] evaluator))))
      (f))
    (let [{:keys [result evaluator]} (make-evaluator)]
      (binding [*result* result
                *evaluator* evaluator]
        (f)))))

(use-fixtures :once evaluate-env)

(defn msg-base []
  {:id (str (java.util.UUID/randomUUID))
   ;:session (atom {:id "964ef60f-2a44-468f-9807-a3585cd953a6"})
   :transport :example})

(defn msg [& data] (apply assoc (msg-base) data))

(defn evaluate [msg & [timeout]]
  (as/>!! (:input-chan *evaluator*) msg)
  (let [out (<!! (ne/get-filtered-result
                  #(= (:id msg) (:id (:figwheel.tools.nrepl.eval/nrepl-message %)))
                  *result*
                  10000))]
    out))

(defn assert-resp= [msg-map expected-res]
  (let [resp (evaluate msg-map)]
    (is (every?
         #(= msg-map %)
         (map :figwheel.tools.nrepl.eval/nrepl-message resp)))
    (is (= (map :figwheel.tools.nrepl.eval/send resp)
           expected-res))))

(deftest simple-test
    (testing "simple eval"
      (assert-resp= (msg :op "eval" :code "1")
                  '[[{:source "1", :value "1", :printed-value 1, :ns cljs.user}]
                    [{:status "done"}]])))


(deftest eval-test
  (testing "simple eval"
    (assert-resp= (msg :op "eval" :code "(+ 1 99)")
                  '[[{:source "(+ 1 99)",
                      :line 1,
                      :column 1,
                      :end-line 1,
                      :end-column 9,
                      :value "100",
                      :printed-value 1,
                      :ns cljs.user}]
                    [{:status "done"}]])
    (assert-resp= (msg :op "eval" :code "1")
                  '[[{:source "1", :value "1", :printed-value 1, :ns cljs.user}]
                    [{:status "done"}]]))

  (testing "multiple eval"
    (assert-resp= (msg :op "eval" :code "(+ 1 4) 1 (+ 23 1)")
                  '[[{:source "(+ 1 4)",
                      :line 1,
                      :column 1,
                      :end-line 1,
                      :end-column 8,
                      :value "5",
                      :printed-value 1,
                      :ns cljs.user}]
                    [{:source "1", :value "1", :printed-value 1, :ns cljs.user}]
                    [{:source "(+ 23 1)",
                      :line 1,
                      :column 11,
                      :end-line 1,
                      :end-column 19,
                      :value "24",
                      :printed-value 1,
                      :ns cljs.user}]
                    [{:status "done"}]]))

  (testing "reader conditionals"
    (assert-resp= (msg :op "eval" :code "(+ 1 4) #?(:cljs 1 :clj 2) (+ 23 1)")
                  '[[{:source "(+ 1 4)",
                      :line 1,
                      :column 1,
                      :end-line 1,
                      :end-column 8,
                      :value "5",
                      :printed-value 1,
                      :ns cljs.user}]
                    [{:source "#?(:cljs 1 :clj 2)",
                      :value "1",
                      :printed-value 1,
                      :ns cljs.user}]
                    [{:source "(+ 23 1)",
                      :line 1,
                      :column 28,
                      :end-line 1,
                      :end-column 36,
                      :value "24",
                      :printed-value 1,
                      :ns cljs.user}]
                    [{:status "done"}]])

    (assert-resp= (msg :op "eval" :code "#?(:cljs 1 :clj 2) (+ 23 1)")
                  '[[{:source "#?(:cljs 1 :clj 2)",
                      :value "1",
                      :printed-value 1,
                      :ns cljs.user}]
                    [{:source "(+ 23 1)",
                      :line 1,
                      :column 20,
                      :end-line 1,
                      :end-column 28,
                      :value "24",
                      :printed-value 1,
                      :ns cljs.user}]
                    [{:status "done"}]])
    (assert-resp= (msg :op "eval" :code "#?(:cljs 1 :clj 2)")
                  '([{:source "#?(:cljs 1 :clj 2)",
                      :value "1",
                      :printed-value 1,
                      :ns cljs.user}]
                    [{:status "done"}]))
    )

  (testing "tags"
    (assert-resp= (msg :op "eval" :code "(+ 1 4) #js {} (+ 23 1)")
                  '([{:source "(+ 1 4)",
                      :line 1,
                      :column 1,
                      :end-line 1,
                      :end-column 8,
                      :value "5",
                      :printed-value 1,
                      :ns cljs.user}]
                    [{:source "#js {}",
                      :value "#js {}",
                      :printed-value 1,
                      :ns cljs.user}]
                    [{:source "(+ 23 1)",
                      :line 1,
                      :column 16,
                      :end-line 1,
                      :end-column 24,
                      :value "24",
                      :printed-value 1,
                      :ns cljs.user}]
                    [{:status "done"}]))
    (assert-resp= (msg :op "eval" :code "#js {} (+ 23 1)")
                  '([{:source "#js {}",
                      :value "#js {}",
                      :printed-value 1,
                      :ns cljs.user}]
                    [{:source "(+ 23 1)",
                      :line 1,
                      :column 8,
                      :end-line 1,
                      :end-column 16,
                      :value "24",
                      :printed-value 1,
                      :ns cljs.user}]
                    [{:status "done"}]))
    (assert-resp= (msg :op "eval" :code "(+ 1 4) #js {}")
                  '([{:source "(+ 1 4)",
                      :line 1,
                      :column 1,
                      :end-line 1,
                      :end-column 8,
                      :value "5",
                      :printed-value 1,
                      :ns cljs.user}]
                    [{:source "#js {}",
                      :value "#js {}",
                      :printed-value 1,
                      :ns cljs.user}]
                    [{:status "done"}]))))

(deftest parsed-form-reader-error
  (assert-resp= (msg :op "eval" :code ")")
                '([{:status "eval-error"
                    :ex "class clojure.lang.ExceptionInfo",
                    :root-ex "class clojure.lang.ExceptionInfo"}
                   {:err "Unmatched delimiter )\n"}]
                  [{:status "done"}]))

  (assert-resp= (msg :op "eval" :code "1 ) 2")
                '([{:source "1", :value "1", :printed-value 1, :ns cljs.user}]
                  [{:status "eval-error"
                    :ex "class clojure.lang.ExceptionInfo",
                    :root-ex "class clojure.lang.ExceptionInfo"}
                   {:err "Unmatched delimiter )\n"}]
                  [{:source "2", :value "2", :printed-value 1, :ns cljs.user}]
                  [{:status "done"}]))
  
  )

(deftest printing-output-messages
  (assert-resp= (msg :op "eval" :code "(prn 7)")
                '([{:out "7\n"}]
                  [{:source "(prn 7)",
                    :line 1,
                    :column 1,
                    :end-line 1,
                    :end-column 8,
                    :value "nil",
                    :printed-value 1,
                    :ns cljs.user}]
                  [{:status "done"}]))
  (assert-resp= (msg :op "eval" :code "(+ 1 4) (prn 7) 1")
                '([{:source "(+ 1 4)",
                    :line 1,
                    :column 1,
                    :end-line 1,
                    :end-column 8,
                    :value "5",
                    :printed-value 1,
                    :ns cljs.user}]
                  [{:out "7\n"}]
                  [{:source "(prn 7)",
                    :line 1,
                    :column 9,
                    :end-line 1,
                    :end-column 16,
                    :value "nil",
                    :printed-value 1,
                    :ns cljs.user}]
                  [{:source "1", :value "1", :printed-value 1, :ns cljs.user}]
                  [{:status "done"}]))
  
  (assert-resp= (msg :op "eval" :code "(prn 7) (println \"hello\") (pr {})")
                '([{:out "7\n"}]
                  [{:source "(prn 7)",
                    :line 1,
                    :column 1,
                    :end-line 1,
                    :end-column 8,
                    :value "nil",
                    :printed-value 1,
                    :ns cljs.user}]
                  [{:out "hello\n"}]
                  [{:source "(println \"hello\")",
                    :line 1,
                    :column 9,
                    :end-line 1,
                    :end-column 26,
                    :value "nil",
                    :printed-value 1,
                    :ns cljs.user}]
                  [{:out "{}\n"}]
                  [{:source "(pr {})",
                    :line 1,
                    :column 27,
                    :end-line 1,
                    :end-column 34,
                    :value "nil",
                    :printed-value 1,
                    :ns cljs.user}]
                  [{:status "done"}]))
  )


(deftest warnings
  (assert-resp= (msg :op "eval" :code "  
 a")
                '([{:err "Use of undeclared Var cljs.user/a at line 2, column 2\n"}
                   {:line 2,
                    :column 2,
                    :end-line 2,
                    :end-column 3,
                    :ns cljs.user,
                    :file "<cljs repl>",
                    :message "Use of undeclared Var cljs.user/a",
                    :status "eval-warning"}]
                  #_[{:source "a",
                    :line 2,
                    :column 2,
                    :end-line 2,
                    :end-column 3,
                    :value "nil",
                    :printed-value 1,
                    :ns cljs.user}]
                  ;; now that we are in a ns bc of repl :init no error is thrown
                  [{:status "eval-error"
                      :ex "class clojure.lang.ExceptionInfo",
                      :root-ex "class clojure.lang.ExceptionInfo"}
                     {:err "TypeError: Cannot read property \"a\" from undefined\n"}]
                  [{:status "done"}])))

(deftest eval-error
  (assert-resp= (msg :op "eval" :code " (let)")
                '([{:status "eval-error"
                    :ex "class clojure.lang.ExceptionInfo",
                    :root-ex "class clojure.lang.ArityException"}
                   {:err
                    "Wrong number of args (0) passed to: core/let at line 1 <cljs repl>\n"}]
                  [{:status "done"}]))
  )

(deftest javascript-error
  (assert-resp= (msg :op "eval" :code "js/defnotthere")
                '([{:status "eval-error"
                    :ex "class clojure.lang.ExceptionInfo",
                    :root-ex "class clojure.lang.ExceptionInfo"}
                   {:err "ReferenceError: \"defnotthere\" is not defined\n"}]
                  [{:status "done"}]))
  )

(deftest interrupt-when-idle 
  (assert-resp= (msg :op "interrupt")
                '([{:status ["done" "session-idle"]}])))

(deftest interrupt-without-id
  (try
    (reset! ne/*simulate-blocking-eval true)
    ;; this blocks
    (as/>!! (:input-chan *evaluator*) (msg :op "eval" :code "(+ 1 665)"))
    (assert-resp= (msg :op "interrupt")
                    '([{:status ["done" "interrupted"]}]))
    
    (finally
      (reset! ne/*simulate-blocking-eval false))))

(deftest interrupt-with-id
  (try
    (reset! ne/*simulate-blocking-eval true)
    ;; this blocks
    (let [m (msg :op "eval" :code "(+ 1 77)")]
      (as/>!! (:input-chan *evaluator*) m)
      (assert-resp= (msg :op "interrupt" :interrupt-id (:id m))
                    `([{:status ["done" "interrupted"]
                        :interrupt-id ~(:id m)}])))
    (finally
      (reset! ne/*simulate-blocking-eval false))))

(deftest interrupt-with-bad-interrupt-id
  (try
    (reset! ne/*simulate-blocking-eval true)
    ;; this blocks
    (let [m (msg :op "eval" :code "(+ 1 88)")]
      (as/>!! (:input-chan *evaluator*) m)
      (assert-resp= (msg :op "interrupt" :interrupt-id "doesnotmatch")
                    `([{:status ["done" "interrupt-id-mismatch"]
                        :interrupt-id "doesnotmatch"}]))
      ;; still blocked on empty test channel so interrupt for real
      (assert-resp= (msg :op "interrupt")
                    `([{:status ["done" "interrupted"]}])))
    (finally
      (reset! ne/*simulate-blocking-eval false))))
