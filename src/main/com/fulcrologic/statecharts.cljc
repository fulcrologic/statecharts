(ns com.fulcrologic.statecharts
  "Placeholder ns so we can use it for aliasing in clojure 1.10 and below.

   Thread Safety: This library does not provide built-in thread safety for concurrent
   event processing. Each session's events must be processed serially. Event loops must
   serialize per-session processing. Use `run-serialized-event-loop!` from
   `com.fulcrologic.statecharts.event-queue.async-event-loop` for async processors.")
