# figwheel.tools

Figwheel tools will eventually hold much of Figwheel's functionality in
separate composable libraries, (i.e. compile, error/warning parsing etc.)

For now, it only holds the support for `figwheel.tools.nrepl` which is a drop in
replacement for [piggieback](https://github.com/cemerick/piggieback).

`piggieback` has one known flaw, it instantiates a new ClojureScript
REPL on every eval request. Creating a CLJS REPL is a particularly
heavy operation. It involves, at the very least, the reloading of the
analysis cache and this causes a noticeable lag on each evaluation. In
my experience the lag is close to 1 second.

`figwheel.tools.nrepl` creates a single CLJS REPL on a thread and is much
more responsive and lighter as a result. It also handles more of the nREPL
protocol's features.

`figwheel.tools.nrepl` provides an instantaneous response to forms
submitted for evaluation.

For example it can handle multiple forms
```
;; Multiple forms are evaluated:
=> 1 2 (+ 1 2) 4
1
2
3
4
=>
```

You can also `interrupt` a hung evaluation:
```
;; Interrupting a hung evaluation
=> (loop [] (recur))
;; now reload the current page that hosts the REPL
^C
;; control is returned to the REPL which is actually running in 
;; a new runtime
=>
```

`figwheel.tools.nrepl` handles the ClojureScript I/O as explicit
messages and can leverage these explicit messages to create a better
ClojureScript nREPL experience.

## Usage

Modify your project.clj to include the following :dependencies and :repl-options:

```
:profiles {:dev {:dependencies [[figwheel.tools "0.1.0-SNAPSHOT"]]
                 :repl-options {:nrepl-middleware [figwheel.tools.nrepl/wrap-cljs-repl]}}}
```

In your nREPL environment you can now start a CLJS REPL


```
$ lein repl
....
user=> (figwheel.tools.nrepl/cljs-repl (cljs.repl.nashorn/repl-env))
To quit, type: :cljs/quit
nil
cljs.user=> (defn <3 [a b] (str a " <3 " b "!"))
#<
function cljs$user$_LT_3(a, b) {
    return [cljs.core.str(a), cljs.core.str(" <3 "), cljs.core.str(b), cljs.core.str("!")].join("");
}
>
cljs.user=> (<3 "nREPL" "ClojureScript")
"nREPL <3 ClojureScript!"
```

## License

Copyright © 2017 Bruce Hauman

Distributed under the Eclipse Public License either version 1.0 or (at your option) any later version.
