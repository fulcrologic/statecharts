(ns com.fulcrologic.statecharts.routing-demo2.data
  "Pre-seeded mock data for the routing demo2 application.

   Contains projects, users, and settings with simple lookup functions.")

(def projects-db
  "Mock database of projects, keyed by project ID."
  {1 {:project/id          1
      :project/name        "Website Redesign"
      :project/description "Complete overhaul of the company website with modern design and improved UX."}
   2 {:project/id          2
      :project/name        "Mobile App v2"
      :project/description "Second major version of the mobile application with offline support."}
   3 {:project/id          3
      :project/name        "API Gateway"
      :project/description "Centralized API gateway for microservices routing and rate limiting."}})

(def users-db
  "Mock database of users, keyed by user ID."
  {1 {:user/id       1
      :user/username "admin"
      :user/name     "Alice Admin"
      :user/role     :admin}
   2 {:user/id       2
      :user/username "user1"
      :user/name     "Bob Builder"
      :user/role     :member}
   3 {:user/id       3
      :user/username "user2"
      :user/name     "Carol Coder"
      :user/role     :member}
   4 {:user/id       4
      :user/username "user3"
      :user/name     "Dave Designer"
      :user/role     :member}})

(def settings-db
  "Application settings."
  {:settings/theme                 "light"
   :settings/notifications-enabled? true
   :settings/language              "en"})

(defn lookup-project
  "Returns the project map for `project-id`, or nil if not found."
  [project-id]
  (get projects-db project-id))

(defn lookup-user
  "Returns the user map for `user-id`, or nil if not found."
  [user-id]
  (get users-db user-id))

(defn all-projects
  "Returns a vector of all project maps."
  []
  (vec (vals projects-db)))

(defn all-users
  "Returns a vector of all user maps."
  []
  (vec (vals users-db)))

(defn get-settings
  "Returns the application settings map."
  []
  settings-db)
