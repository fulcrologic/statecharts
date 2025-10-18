If you have a REPL available, running tests in any namespace N should be done as:

```
(do
    (require 'N)
    (in-ns 'N)
    (require '[kaocha.repl :as k]) 
    (k/run 'N))
```

