# TODOS

Move reader-helper to a proxy look at nrepl.tools for example

## environment

make environment based on a ["build-config-set" config] ["build-config-update" path value]  model

write tests when satisfied

## eval

update eval middleware so that the repl-thread is stored based on the build-config in the environment

* important to not let two evaluators talk to the same repl environment

* eventually get rid of this limitation getting rid of this limitation
  will require that we hae the ability to label messages going to and
  from the repl env (have eval, error and output messages labeled with ids)





## overall current goal

Get to a working model of interaction including a browser client 

To validate that this is a good path going forward.


