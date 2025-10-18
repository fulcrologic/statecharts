# Clojure Library Source and Documentation

Many of the namespaces you'll see used in the project will not be local files. You will want to access the documentation
and source of these items, and Clojure has a great way for doing this.

## Understanding Namespaces

A namespace is an element of clojure that is used to organize code. The `require` command loads namespaces, and can then
also modify the *current* namespace's content to include aliases (to other namespaces) and refer (copy) the vars from
other namespaces into the current one (the current ns is always known by reading clojure.core/*ns*, which returns the
actual Java Namespace object that represents namespaces. Use the getName method to get the string name of it).

The following namespace declaration (which actually is just a macro):

```
(namespace foo.bar
  (:require 
    [fully.qualified.namespace :as fqn :refer [f g]]))
```

Loads the `fully.qualified.namespace`, Adds an alias to the `foo.bar` namespace called `fqn` to stand for the loaded
one, and then *interns* the vars `f` and `g` from `fqn` into the `foo.bar` namespace. The result is that when evaluating
code the runtime looks at `*ns*` (the current active namespace), and tries to figure out what symbols mean. Simple
symbols are looked up in the interned symbols. symbols that are prefixed with aliases are looked up that way (e.g. fqn/f
is the same as f). Other fully-qualified symbol are taken literally.

## Extracting Documentation and Source

There is a namespace called clojure.repl that can be used to extract the documentation of vars (functions and such), and
also for open-source things it can show you the source code.

Since you have access to an nREPL, you can leverage this to get a focused view of things you're interested in without
putting entire files into your context window. You should always prefer getting the source of a function (defn or def)
if you can find it before looking for a file, unless you just need to know what is in the entire namespace.

First name sure the tool and namespace are loaded in nREPL:

* `(require 'clojure.repl)`
* `(require 'the-namespace-of-the-function-you-want)`

Then:

* To see the docstring, use `(clojure.repl/doc fully-qualified-name)`
* To see the source, use `(clojure.repl/doc fully-qualified-name)`

IMPORTANT: Both `doc` and `source` are macros that take a symbol that MUST NOT be quoted in any way. You should always
use the fully-qualified name (e.g. dataico.lib.strings/normalize instead of just normalize or an aliased
strs/normalize). 
