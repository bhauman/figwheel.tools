(ns figwheel.tools.connect.websocket
  (:require
   [goog.object :as gobj])
  (:import [goog]))

;; todo google logging

(defn get-websocket-imp []
  (cond
    (and (exists? js/window)
         (exists? js/window.WebSocket))
    js/window.WebSocket
    (not (nil? goog/nodeGlobalRequire))
    (try (js/require "ws") (catch js/Error e nil))
    :else nil))

(defprotocol Channel
  (close [x])
  (add-receive-listener [x nm f])
  (remove-receive-listener [x nm])
  (add-error-listener [x nm f])
  (remove-error-listener [x nm])  
  (send-data [x d]))

(defn reconnecting-websocket-open [url on-open]
  (if-let [WebSocket (get-websocket-imp)]
    ((fn websocket-open [retried-count]
       (let [socket (WebSocket. url)]
         (set! (.-onopen socket)
               (fn [x] (on-open socket)))
         (set! (.-onclose socket)
               (fn [x]
                 (let [retried-count (or retried-count 0)]
                   (when (> retry-count retried-count)
                     (js/setTimeout
                      (fn [] (websocket-open url (inc retried-count)))
                      ;; linear back off with a max
                      (min 10000 (+ 2000 (* 500 retried-count))))))))
        socket))
     0)
    ;; throw error as now webSocket is available
    (throw (js/Error. "Websocket implementation not available"))))

(defn websocket-channel [url]
  (let [receive-listeners (atom {})
        error-listeners   (atom {})
        socket-atom       (atom nil)]
    (reconnecting-websocket-open
     url
     (fn [socket]
       (reset! socket-atom socket)
       (set! (.-onmessage socket)
             (fn [data]
               (mapv #(% data) (vals @receive-listeners))))
       (set! (.-onerror socket)
             (fn [x]
               (mapv #(% data) (vals @error-listeners))))))
    (reify Channel
      (close [th]
        (when @socket-atom
          (set! (.onclose @socket-atom) (fn [_]))
          (.close @socket-atom)))
      (add-receive-listener [_ knm f]
        (swap! receive-listeners assoc knm f))
      (add-error-listener [_ knm f]
        (swap! error-listeners assoc knm f))
      (remove-receive-listener [_ knm]
        (swap! receive-listeners dissoc knm))
      (remove-error-listener [_ knm]
        (swap! receive-listeners dissoc knm))      
      (send-data [_ data]
        (when @socket-atom
          (.send @socket-atom data))))))



#_(get-websocket-imp)


#_(defn open [{:keys [retry-count retried-count websocket-url build-id] :as opts}]
  (if-let [WebSocket (get-websocket-imp)]
    (do
      (utils/log :debug "Figwheel: trying to open cljs reload socket")
      (let [url (str websocket-url (if build-id (str "/" build-id) ""))
            socket (WebSocket. url)]
        (set! (.-onmessage socket) (fn [msg-str]
                                     (when-let [msg
                                                (read-string (.-data msg-str))]
                                       (#'handle-incoming-message msg))))
        (set! (.-onopen socket)  (fn [x]
                                   (reset! socket-atom socket)
                                   (when (utils/html-env?)
                                     (.addEventListener js/window "beforeunload" close!))
                                   (utils/log :debug "Figwheel: socket connection established")))
        (set! (.-onclose socket) (fn [x]
                                   (let [retried-count (or retried-count 0)]
                                     (utils/debug-prn "Figwheel: socket closed or failed to open")
                                     (when (> retry-count retried-count)
                                       (js/setTimeout
                                        (fn []
                                          (open
                                           (assoc opts :retried-count (inc retried-count))))
                                        ;; linear back off
                                        (min 10000 (+ 2000 (* 500 retried-count))))))))
        (set! (.-onerror socket) (fn [x] (utils/debug-prn "Figwheel: socket error ")))
        socket))
    (utils/log :debug
               (if (utils/node-env?)
                 "Figwheel: Can't start Figwheel!! Please make sure ws is installed\n do -> 'npm install ws'"
                 "Figwheel: Can't start Figwheel!! This browser doesn't support WebSockets"))))
