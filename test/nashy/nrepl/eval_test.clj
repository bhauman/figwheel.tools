(ns nashy.nrepl.eval-test
  (:require
   [nashy.nrepl.eval :as ne]
   [cljs.repl.nashorn :as nash]
   [clojure.test :refer :all]))

(def ^:dynamic *result* nil #_(atom []))

(def ^:dynamic *evaluator* nil)

(def ^:dynamic *dev* true)

(defn make-evaluator []
  (let [result (atom [])
        evaluator
        (-> (fn [out-msg] (swap! result conj out-msg))
            (ne/evaluate-cljs-new nash/repl-env "1"))]
    ;; need to wait for this complete initializing
    (Thread/sleep 3000)
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

(def msg-base
  {:id "91db8f85-06a9-431b-87e9-2f8fed2775cc",
   ;:session (atom {:id "964ef60f-2a44-468f-9807-a3585cd953a6"})
   :transport :example})

(defn msg [& data] (apply assoc msg-base data))

(defn evaluate [msg & [timeout]]
  (reset! *result* [])
  (*evaluator* msg)
  (Thread/sleep (or timeout 100))
  @*result*)

(defn assert-resp= [msg-map expected-res]
  (let [resp (evaluate msg-map)]
    (is (every?
         #(= msg-map %)
         (map :nashy.nrepl.eval/nrepl-message resp)))
    (is (= (map :nashy.nrepl.eval/send resp)
           expected-res))))


(deftest eval-test
  (testing "simple eval"
    (assert-resp= (msg :op "eval" :code "(+ 1 2)")
                  '[[{:source "(+ 1 2)",
                      :line 1,
                      :column 1,
                      :end-line 1,
                      :end-column 8,
                      :value "3",
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
                   {:err "Unmatched delimiter )"}]
                  [{:status "done"}]))

  (assert-resp= (msg :op "eval" :code "1 ) 2")
                '([{:source "1", :value "1", :printed-value 1, :ns cljs.user}]
                  [{:status "eval-error"
                    :ex "class clojure.lang.ExceptionInfo",
                    :root-ex "class clojure.lang.ExceptionInfo"}
                   {:err "Unmatched delimiter )"}]
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
                '([{:err "Use of undeclared Var cljs.user/a at line 2, column 2"}
                   {:line 2,
                    :column 2,
                    :end-line 2,
                    :end-column 3,
                    :ns cljs.user,
                    :file "<cljs repl>",
                    :message "Use of undeclared Var cljs.user/a",
                    :extra {:prefix cljs.user, :suffix a, :macro-present? false},
                    :status "eval-warning"}]
                  [{:status "eval-error"
                    :ex "class clojure.lang.ExceptionInfo",
                    :root-ex "class clojure.lang.ExceptionInfo"}
                   {:err "TypeError: Cannot read property \"a\" from undefined"}]
                  [{:status "done"}])))

(deftest eval-error
  (assert-resp= (msg :op "eval" :code " (let)")
                '([{:status "eval-error"
                    :ex "class clojure.lang.ExceptionInfo",
                    :root-ex "class clojure.lang.ArityException"}
                   {:err
                    "Wrong number of args (0) passed to: core/let at line 1 <cljs repl>"}]
                  [{:status "done"}]))
  )

(deftest javascript-error
  (assert-resp= (msg :op "eval" :code "js/defnotthere")
                '([{:status "eval-error"
                    :ex "class clojure.lang.ExceptionInfo",
                    :root-ex "class clojure.lang.ExceptionInfo"}
                   {:err "ReferenceError: \"defnotthere\" is not defined"}]
                  [{:status "done"}]))
  )
