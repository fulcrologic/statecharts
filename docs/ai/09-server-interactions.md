# Server Interactions

## Overview
Fulcro uses EQL (EDN Query Language) over Transit protocol for all server communication. Single API endpoint processes both queries and mutations.

## Server Setup

### Basic Ring Server
```clojure
(ns app.server
  (:require
    [app.parser :refer [api-parser]]
    [org.httpkit.server :as http]
    [com.fulcrologic.fulcro.server.api-middleware :as server]
    [ring.middleware.content-type :refer [wrap-content-type]]
    [ring.middleware.resource :refer [wrap-resource]]))

(def middleware
  (-> not-found-handler
    (server/wrap-api {:uri "/api" :parser api-parser})
    (server/wrap-transit-params)
    (server/wrap-transit-response)
    (wrap-resource "public")
    wrap-content-type))

(defn start []
  (http/run-server middleware {:port 3000}))
```

### Pathom Parser Setup
```clojure
(ns app.parser
  (:require
    [com.wsscode.pathom.core :as p]
    [com.wsscode.pathom.connect :as pc]))

(def pathom-parser
  (p/parser {::p/env     {::p/reader [p/map-reader pc/reader2 pc/ident-reader]}
             ::p/mutate  pc/mutate
             ::p/plugins [(pc/connect-plugin {::pc/register resolvers})
                          p/error-handler-plugin
                          (p/post-process-parser-plugin p/elide-not-found)]}))

(defn api-parser [query]
  (pathom-parser {} query))
```

## Pathom Resolvers

### Basic Resolver Structure
```clojure
(pc/defresolver person-resolver [env {:person/keys [id]}]
  {::pc/input  #{:person/id}      ; Required inputs
   ::pc/output [:person/name :person/age]}  ; Available outputs
  (get-person-from-database id))
```

### Input/Output Declaration
- **`::pc/input`**: Set of required input keys
- **`::pc/output`**: EQL describing available outputs
- **Resolution**: Pathom chains resolvers based on input/output relationships

### Example Resolvers
```clojure
;; Entity resolver
(pc/defresolver person-resolver [env {:person/keys [id]}]
  {::pc/input  #{:person/id}
   ::pc/output [:person/name :person/age]}
  (get people-table id))

;; Collection resolver
(pc/defresolver list-resolver [env {:list/keys [id]}]
  {::pc/input  #{:list/id}
   ::pc/output [:list/label {:list/people [:person/id]}]}
  (when-let [list (get list-table id)]
    (assoc list :list/people (mapv (fn [id] {:person/id id}) (:list/people list)))))

;; Root resolver (no inputs)
(pc/defresolver friends-resolver [env input]
  {::pc/output [{:friends [:list/id]}]}
  {:friends {:list/id :friends}})
```

### Ident Queries
EQL supports ident-based queries for specific entities:
```clojure
[{[:person/id 1] [:person/name]}]
=> {[:person/id 1] {:person/name "Sally"}}
```

## Client Remote Configuration

### HTTP Remote Setup
```clojure
(ns app.application
  (:require
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.networking.http-remote :as http]))

(defonce app (app/fulcro-app
               {:remotes {:remote (http/fulcro-http-remote {})}}))
```

### Multiple Remotes
```clojure
(defonce app (app/fulcro-app
               {:remotes {:api    (http/fulcro-http-remote {:url "/api"})
                          :auth   (http/fulcro-http-remote {:url "/auth"})
                          :files  (http/fulcro-http-remote {:url "/files"})}}))
```

## Server Mutations

### Pathom Mutation Definition
```clojure
(ns app.mutations
  (:require [com.wsscode.pathom.connect :as pc]))

(pc/defmutation delete-person [env {list-id :list/id person-id :person/id}]
  {::pc/sym `delete-person}  ; Optional symbol override
  (log/info "Deleting person" person-id "from list" list-id)
  (swap! list-table update list-id 
    update :list/people (fn [old-list] 
                          (filterv #(not= person-id %) old-list))))
```

### Mutation Return Values
```clojure
(pc/defmutation save-person [env {:person/keys [id name]}]
  {::pc/sym `save-person}
  (let [saved-person (save-to-database {:person/id id :person/name name})]
    {:person/id id :person/name name :person/updated-at (js/Date.)}))
```

### Joining Mutation Results
Server mutations can return data that gets auto-merged:
```clojure
;; Client mutation with returning
(defmutation save-person [params]
  (action [env] ...)
  (remote [env] (m/returning Person)))

;; Server handles the query automatically
```

## Error Handling

### Server Error Responses
```clojure
(pc/defmutation risky-operation [env params]
  {::pc/sym `risky-operation}
  (try
    (dangerous-operation params)
    (catch Exception e
      {::p/error (str "Operation failed: " (.getMessage e))})))
```

### Client Error Handling
```clojure
(defmutation save-data [params]
  (action [env] ...)
  (remote [env] true)
  (error-action [{:keys [error]}]
    (log/error "Save failed:" error)))
```

## Query Parameters

### Client-Side Parameters
```clojure
(df/load! this :people Person 
  {:params {:limit 10 :offset 20 :filter "active"}})
```

### Server Parameter Handling
```clojure
(pc/defresolver people-resolver [env params]
  {::pc/output [{:people [:person/id]}]}
  (let [{:keys [limit offset filter]} params]
    {:people (query-people :limit limit :offset offset :filter filter)}))
```

## Advanced Server Features

### Batch Optimization
Pathom automatically batches resolver calls:
```clojure
;; Client makes these calls
[{[:person/id 1] [:person/name]}
 {[:person/id 2] [:person/name]}
 {[:person/id 3] [:person/name]}]

;; Pathom can batch into single resolver call with multiple inputs
```

### Resolver Dependencies
```clojure
(pc/defresolver user-posts [env {:user/keys [id]}]
  {::pc/input  #{:user/id}
   ::pc/output [{:user/posts [:post/id]}]}
  {:user/posts (get-posts-for-user id)})

(pc/defresolver post-details [env {:post/keys [id]}]
  {::pc/input  #{:post/id}
   ::pc/output [:post/title :post/content]}
  (get-post-details id))

;; Pathom chains: user/id → user/posts → post details
```

### Global Resolvers
```clojure
;; Available at query root (like GraphQL root queries)
(pc/defresolver current-user [env input]
  {::pc/output [{:current-user [:user/id]}]}
  (when-let [user-id (get-session-user-id env)]
    {:current-user {:user/id user-id}}))
```

## Development Tools

### Parser Testing
```clojure
;; Test resolvers directly
(app.parser/api-parser [{[:person/id 1] [:person/name]}])
=> {[:person/id 1] {:person/name "Sally"}}
```

### Query Tracing
Enable in Pathom parser for debugging:
```clojure
{::p/plugins [...
              p/trace-plugin  ; Add this for query tracing
              ...]}
```

### Server Refresh Pattern
```clojure
(ns user
  (:require [clojure.tools.namespace.repl :refer [refresh]]))

(defn restart []
  (stop-server)
  (refresh :after 'user/start))
```

## Security Considerations

### Query Authorization
```clojure
(pc/defresolver sensitive-data [env input]
  {::pc/output [:sensitive/field]}
  (when (authorized? env)
    {:sensitive/field "secret"}))
```

### Mutation Authorization
```clojure
(pc/defmutation admin-operation [env params]
  {::pc/sym `admin-operation}
  (when-not (admin? env)
    (throw (ex-info "Unauthorized" {:type :unauthorized})))
  (perform-admin-operation params))
```

### Parameter Validation
```clojure
(pc/defmutation create-user [env {:user/keys [name email] :as params}]
  {::pc/sym `create-user}
  (when-not (and (valid-name? name) (valid-email? email))
    (throw (ex-info "Invalid parameters" {:type :validation-error})))
  (create-user-in-db params))
```