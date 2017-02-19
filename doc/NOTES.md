
Consider making pushing the some of the common asynchrony implemented
in the nREPL middleware interface down another layer. This will make
the library infinitely easier to use for folks who just want blocking
cljs eval.

Need to look at what it configuration is needed to define a cljs
environment. Some of this work has been done in figwheel but I think
we need to take those lessons and readdress this problem.

- what parts of a clojurescript evn can be changed during runtime?

## Make sure that a session is evolvable!!
Meaning that you can start with nothing load a file and grow from there.

* All of the functionality provided should operate inside and outside
of nREPL.

TODO

* clj interuptable-eval stores the thread and the current eval message
  in the session meta
* finish interupt
* look into the initialization of a cljs environment
  - simple and sophisitcated
* make sure to propogate repl options throwing on unsupported ones and translating
  ones to the new situation
  
* it looks like the following middleware are needed
  - cljs environment [:repl-env :compiler-env :compile-opts :compiler-srcs]
    * this would be alterable at runtime  within limits
	* we want to bind an environment to a session
	* we want to clone a session and then choose a running env or create a new one
  - cljs compile
    * middleware that is very similar to the repl middleware but it cljs compiles the current 
	  environment streams resulting messages back to the client
	* this also introduces the idea of middleware needing to emit events
	  - in this case a files changed event could trigger a compile and
        we want a reload-ns event to be sent to the client *after* compile has completed
		- the way to do this isn't obvious 
		- a transport filter seems to be a possibility if not the best way of doing this
  - cljs-client 
    * while repl eval is simple a way of talking to the client it
	  might behoove us to have a formal set of messages so we know
	  what is available on a cross platform basis
	* this would allow other middleware to send/recieve messages
      directly to/from the cljs-client and more importantly it
      provides this to the client (editor etc)
	  - this could allow a pattern of mirrored middleware a service
	    that is installed as nrepl middleware is also installed as
	    cljs-client middleware in the cljs runtime
	  - an example of this would be middleware that handles reloading CSS or html templates
	* this would also allow only client side middleware that can take commands from the 
  	  editor [for the browser to reload] or talking so services installed because 
	  the connection environment is inside the remote debugger
  - cljs-eval
  
* possible middleware
  - wathcing
  
# client protocol 

* provide eval middleware similar to nrepl protocol
* id-middleware 
  - should handle human recognizable identification instead of session
    middleware
  - this is to provide tooling a way of recognizing who is connected to what
    browser windows should all have a recognizable name
  - allows tooling to determine and select what env (among identical cljs-enviroments)
    should be doing the eval
* this will support the multiplexing of connected clients

# certain js eval runtimes envs (browsers) mirror the needs of editor tooling

* browsers are also nREPL clients via a websocket
* allows browsers to implement much more sophisticated tooling repls,
  editors, message logs, GUI web app dashboards that implement push button
  controls over the system (compile, config, testing, etc)

# Why nREPL?

*It is the widely deployed* and is the default for Clojure tooling
communication. It works well enough and at it's base is simple enough
to completely gut and start over. We should also understand that the
ClojureScript community is still very small in relative terms and is
not teaming with low level tooling developers. As evidenced by the
fact that the cljs REPL is still problematic this many years
later. Also the audience of potentially enthusiastic users is more and
more originating from a JavaScript background and are understandably
initially adverse to mucking around in Clojure land.

Currently the *only* real nREPL painpoints are the session and
interuptable-eval middleware as they are biased towards a Clojure eval
env and might be considered a little hacky. The session middleware can
easily be replaced. After all, it is a network protocol, and cljs
deserves its own middleware stack.

If we as a community can evolve a set of middleware that is fairly
feature complete across lang platforms (clj, cljs) and start moving
existing tooling towards it, we can provide a stable platform for
future toolmakers.

We can protect ourselves from nREPL protocol obsolesense by decoupling the
middleware service from nREPL so that this tooling comes in library form and
isn't dependent on nREPL env conventions.

Towards that goal I would say that each middleware component should be
responsible for its own enviroment including handling its own io messages.




