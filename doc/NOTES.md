
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
		- Continuations are a good model to lool at 
		  i.e. attach a composable transport fn to the nrepl-message
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

*It is the widely deployed* and is the default for Clojure tooling/repl
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



# TODOS 
## in the nrepl.eval

Heavily relying on a blocking eval would be nice to know a certain
result belongs to a given message considering the different behavior
of different envs.

Or ensure that only one eval js runtime env exists per repl environent
if broadcast mode is selected 

Consider what it would mean to have a fully continuation based
implementation of nrepl.eval ...

This would rely on pushing the matching up of in and out eval in a lower lib.

Continuations can't travel across the eval divide without an id going
out and coming back. We could do this by evaling inside a map and
changing the wrap-fn to assign the correct values to *1 *2 *3

Why continuations?

It may be possible to come up with some simple composable operators/combinators
that help us express the complexity of the asynchronous nature of the system.

Hang ups with continuations 
  timeouts?  these are possible. 
  interrupt on hanging eval betond a time out it would be nice to send a signal
   to interrupt - this is where continuations become a little messy 
   an outside signal interfeering on an already composed message stack




OK starting to see a simpler way of providing an eval service api

But it relies on an interface like this

    eval(env code continue-handler)
    interrupt(env continue-handler)
    kill(env continue-handler)

the continue-handler will be called multiple times with results such
as value, warning, exception, out/err output.

(Read is not currently a problem for out javascript eval?)

this call will queue evals internally if necessary
and ensures that the result is the actual result of the call

    eval(env code continue-handler)

The reason queuing is nessesary is to ensure the capture of output **during**
the actual execution of the code. 

This is only necessary because we are using the cljs repl as a base
for this and it is a matter of timing to match output to a command so
sending it off collecting output and waiting for it to return is a
natural course of action.

Tagging output with an id would allow us to push any queuing to
to the javascript runtime env which doesn't actually need it bc it is
single threaded. 

We could give the output an id and ferry it across the output streams
that are used by the repl.

But this hackery is probably better avoided and just use blocking
atomic exec on the cljs-repl side and capture the output. And when we
have our own repl code that talks to a repl-env ...

we can also create a blocking eval that 
returns {:value # :out # :err # :warns # :except #}

    eval(env code) 


