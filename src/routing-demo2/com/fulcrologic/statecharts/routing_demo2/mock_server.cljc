(ns com.fulcrologic.statecharts.routing-demo2.mock-server
  "Pathom 2 resolvers and mutations for the routing demo2 mock server."
  (:require
    [com.fulcrologic.statecharts.routing-demo2.data :as data]
    [com.wsscode.pathom.connect :as pc]
    [com.wsscode.pathom.core :as p]
    [taoensso.timbre :as log]))

(def ^:dynamic *server-delay-ms*
  "Simulated server response delay in ms. Bind to 0 in tests for synchronous behavior."
  0)

(defonce sessions
  (atom {"pre-seeded-token" {:user/id       1
                             :user/username "admin"
                             :user/name     "Alice Admin"}}))

(def ^:private valid-credentials
  "Map of username -> {:password ... :user/id ...} for login validation."
  {"admin" {:password "DemoPass123!" :user/id 1}
   "user1" {:password "UserPass456!" :user/id 2}})

;; ---------------------------------------------------------------------------
;; Resolvers
;; ---------------------------------------------------------------------------

(pc/defresolver all-projects-resolver [_env _]
  {::pc/output [{:project/all [:project/id]}]}
  {:project/all (data/all-projects)})

(pc/defresolver project-resolver [_env {:project/keys [id]}]
  {::pc/input  #{:project/id}
   ::pc/output [:project/name :project/description]}
  (data/lookup-project id))

(pc/defresolver all-users-resolver [_env _]
  {::pc/output [{:user/all [:user/id]}]}
  {:user/all (data/all-users)})

(pc/defresolver user-resolver [_env {:user/keys [id]}]
  {::pc/input  #{:user/id}
   ::pc/output [:user/username :user/name :user/role]}
  (data/lookup-user id))

(pc/defresolver settings-resolver [_env _]
  {::pc/output [:settings/theme :settings/notifications-enabled? :settings/language]}
  (data/get-settings))

;; ---------------------------------------------------------------------------
;; Mutations
;; ---------------------------------------------------------------------------

(pc/defmutation login [_env {:keys [username password]}]
  {::pc/params [:username :password]}
  (log/info "Login attempt for" username)
  (if-let [{expected-pw :password user-id :user/id} (get valid-credentials username)]
    (if (= password expected-pw)
      (let [user  (select-keys (data/lookup-user user-id)
                    [:user/id :user/username :user/name])
            token (str (random-uuid))]
        (swap! sessions assoc token user)
        {:token token :user user})
      (do
        (log/warn "Invalid password for" username)
        {:error "Invalid credentials"}))
    (do
      (log/warn "Unknown user" username)
      {:error "Invalid credentials"})))

(pc/defmutation check-session [_env {:keys [token]}]
  {::pc/params [:token]}
  (if-let [user (get @sessions token)]
    {:valid? true :user user}
    {:valid? false :user nil}))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(def all-resolvers
  "All resolvers and mutations for the mock server."
  [all-projects-resolver project-resolver
   all-users-resolver user-resolver
   settings-resolver
   login check-session])

(defn make-parser
  "Returns an async Pathom 2 parser function `(fn [eql] ...)`."
  []
  (let [parser (p/async-parser
                 {::p/mutate  pc/mutate-async
                  ::p/env     {::p/reader [p/map-reader pc/async-reader2 pc/open-ident-reader]}
                  ::p/plugins [(pc/connect-plugin {::pc/register all-resolvers})]})]
    (fn [eql] (parser {} eql))))
