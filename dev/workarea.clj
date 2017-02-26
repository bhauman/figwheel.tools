(ns nashy.nrepl.workarea
  (:require
   [nashy.nrepl.eval]
   [clojure.tools.nrepl.server :as server]
   [clojure.tools.nrepl.middleware :as middleware]
   [clojure.tools.nrepl :as nrepl]))

(defn create-handler
  [& middlewares]
  (let [stack (middleware/linearize-middleware-stack middlewares)]
    ((apply comp (reverse stack)) server/unknown-op)))

(defn dev-handler []
  (create-handler
   #'clojure.tools.nrepl.middleware/wrap-describe
   ;; #'clojure.tools.nrepl.middleware.interruptible-eval/interruptible-eval

   #'nashy.nrepl.eval/cljs-eval

   ; #'clojure.tools.nrepl.middleware.load-file/wrap-load-file
   ;;#'clojure.tools.nrepl.middleware.session/add-stdin

   #'clojure.tools.nrepl.middleware.session/session))

;; development helpers

(defonce state-atom (atom {}))

(defn start-server [ky opts]
  (if (nil? (ky @state-atom))
    (swap! state-atom assoc ky (apply server/start-server (apply concat opts)))
    (throw (ex-info "Server running!" {}))))

(defn stop-server [ky]
  (when-not (nil? (ky @state-atom))
    (server/stop-server (ky @state-atom))
    (swap! state-atom dissoc ky)))

(defn msg [ky m]
  (when-not (ky @state-atom)
    (throw (ex-info (str "Server " (pr-str ky) " not available") {})))
  (with-open [conn (nrepl/connect :port (:port (ky @state-atom)))]
     (-> (nrepl/client conn 3000)
         (nrepl/message m)
         doall)))

(defmacro ev [ky m]
  `(msg ~ky {:op :eval :code ~(pr-str m)}))

(defn in-nrepl [m]
  (start-server :nrepl {})
  (let [res (msg :nrepl m)]
    (stop-server :nrepl)
    res))

(defn in-cljs [m]
  (start-server :cljs {:handler (#'dev-handler)})
  (let [res (msg :cljs m)]
    (stop-server :cljs)
    res))



(comment

  (:port (:cljs @state-atom))
  
  (start-server :cljs {:handler (#'dev-handler)})

  (def conn (nrepl/connect :port (:port (:cljs @state-atom))))
  (defn msg* [m]
    (-> (nrepl/client conn 1000)
        (nrepl/message m)
        doall))

  ;; create a new connection like this
  (def sess-id (-> (msg* {:op "clone" }) first :new-session))
  
  (msg* {:op "ls-sessions"})
  (msg* {:op "eval" :code "1" :session sess-id})
  
  (msg* {:op "eval" :code "(+ 1 3) 1 4 3 (prn 2) 1" :session sess-id})

  (msg* {:op "eval" :code "(str *ns*)" :session sess-id})

  (msg* {:op "eval" :code "asdf" :session sess-id})
  (msg* {:op "eval" :code "(ns cljs.user)" :session sess-id})

  (msg* {:op "eval" :code "(defn asdf [] 1)" :session sess-id})
  
  (stop-server :cljs)

  
  )


(comment
  (in-nrepl {:op :eval :code "1 \n(list 1)   "})
  (in-nrepl {:op :eval :code "(+ 1 3) \n(+ 23 1)"})
  (in-nrepl {:op :eval :code "(prn 5)"})
  (in-nrepl {:op :describe :verbose? 1})  

  (in-nrepl {:op :interrupt})

  
  
  (in-cljs {:op :eval :code "(+ 1 3)"})
  (in-cljs {:op :describe :verbose? 1})  
  
  (start-server :nrepl {})

  (ev :nrepl (+ 1 2))
  
  (stop-server :nrepl)


  )










