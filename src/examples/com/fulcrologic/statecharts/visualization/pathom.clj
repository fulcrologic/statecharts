(ns com.fulcrologic.statecharts.visualization.pathom
  "Pathom 2 parser setup for the visualization API."
  (:require
    [com.fulcrologic.statecharts.visualization.server.resolvers :as resolvers]
    [com.wsscode.pathom.connect :as pc]
    [com.wsscode.pathom.core :as p]
    [taoensso.timbre :as log]))

(defn make-parser
  "Creates a Pathom 2 parser with all registered resolvers.

  The parser can process EQL queries and resolve data using the configured resolvers."
  []
  (p/parser
    {::p/env     {::p/reader                 [p/map-reader
                                              pc/reader2
                                              pc/open-ident-reader
                                              p/env-placeholder-reader]
                  ::p/placeholder-prefixes   #{">"}
                  ::pc/mutation-join-globals [:tempids]}
     ::p/mutate  pc/mutate
     ::p/plugins [(pc/connect-plugin {::pc/register resolvers/resolvers})
                  p/error-handler-plugin
                  p/request-cache-plugin
                  p/trace-plugin]}))

(defn process-query
  "Processes an EQL query using the Pathom parser.

  Parameters:
  - parser: The Pathom parser instance
  - env: Map containing environment data (protocols, config, etc.)
  - eql: The EQL query to process

  Returns the query result map."
  [parser env eql]
  (try
    (parser env eql)
    (catch Exception e
      (log/error e "Error processing query:" eql)
      {::p/errors [{:message (.getMessage e)
                    :type    :query-error}]})))
