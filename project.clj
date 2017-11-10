(defproject figwheel.tools "0.1.0-SNAPSHOT"
  :description "FIXME: write this!"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}

  :min-lein-version "2.7.1"

  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.clojure/clojurescript "1.9.229"]
                 [org.clojure/tools.nrepl "0.2.12"]
                 [org.clojure/core.async "0.3.443"
                  :exclusions [org.clojure/tools.reader]]]

  :plugins [[lein-figwheel "0.5.15-SNAPSHOT"]
            [lein-cljsbuild "1.1.5" :exclusions [[org.clojure/clojure]]]]

  :source-paths ["src"]

  :clean-targets ^{:protect false} ["resources/public/js/compiled" "target"]

  :cljsbuild {:builds
              [{:id "dev"
                :source-paths ["src"]
                :figwheel {:open-urls ["http://localhost:3449/index.html"]}

                :compiler {:main example.core
                           :asset-path "js/compiled/out"
                           :output-to "resources/public/js/compiled/nashy.js"
                           :output-dir "resources/public/js/compiled/out"
                           :source-map-timestamp true
                           :preloads [devtools.preload]}}]}

  :repl-options {:nrepl-middleware [figwheel.tools.nrepl/wrap-cljs-repl]}

  :profiles {:dev {:dependencies [[figwheel-sidecar "0.5.15-SNAPSHOT"]
                                  #_[com.cemerick/piggieback "0.2.2"]]
                   ;; need to add dev source path here to get user.clj loaded
                   :source-paths ["src" "dev" "cljs-src"]}}

)
