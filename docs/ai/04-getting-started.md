# Getting Started

## Prerequisites

### Install Fulcro Inspect
**Essential Chrome extension** for development:
- View internal application state
- Transaction history
- State snapshots and time travel
- Query autocomplete with Pathom
- Source-level debugging

### Chrome Development Settings
Enable in Chrome DevTools settings:
- **Console**: "Enable Custom Formatters"
- **Network**: "Disable Cache (while devtools is open)"

### Required Tools
- **Java SE Development Kit**: Version 8 recommended (OpenJDK or official)
- **Clojure CLI Tools**: For dependency management
- **Node.js and npm**: For ClojureScript compilation
- **Editor**: IntelliJ CE + Cursive (recommended) or Emacs/Spacemacs

## Project Setup

### Directory Structure
```bash
mkdir app && cd app
mkdir -p src/main src/dev resources/public
npm init
npm install shadow-cljs react react-dom --save
```

### Dependencies (`deps.edn`)
```clojure
{:paths   ["src/main" "resources"]
 :deps    {org.clojure/clojure    {:mvn/version "1.10.3"}
           com.fulcrologic/fulcro {:mvn/version "3.5.9"}}

 :aliases {:dev {:extra-paths ["src/dev"]
                 :extra-deps  {org.clojure/clojurescript   {:mvn/version "1.10.914"}
                               thheller/shadow-cljs        {:mvn/version "2.16.9"}
                               binaryage/devtools          {:mvn/version  "1.0.4"}}}}}
```

### Shadow-cljs Configuration (`shadow-cljs.edn`)
```clojure
{:deps     {:aliases [:dev]}
 :dev-http {8000 "classpath:public"}
 :builds   {:main {:target     :browser
                   :output-dir "resources/public/js/main"
                   :asset-path "/js/main"
                   :modules    {:main {:init-fn app.client/init
                                       :entries [app.client]}}
                   :devtools   {:after-load app.client/refresh
                                :preloads   [com.fulcrologic.fulcro.inspect.preload]}}}}
```

### HTML File (`resources/public/index.html`)
```html
<html>
 <meta charset="utf-8">
  <body>
    <div id="app"></div>
    <script src="/js/main/main.js"></script>
  </body>
</html>
```

## Basic Application Structure

### Application Setup (`src/main/app/application.cljs`)
```clojure
(ns app.application
  (:require [com.fulcrologic.fulcro.application :as app]))

(defonce app (app/fulcro-app))
```

### UI Components (`src/main/app/ui.cljs`)
```clojure
(ns app.ui
  (:require
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.dom :as dom]))

(defsc Person [this {:person/keys [name age]}]
  (dom/div
    (dom/p "Name: " name)
    (dom/p "Age: " age)))

(def ui-person (comp/factory Person))

(defsc Root [this props]
  (dom/div
    (ui-person {:person/name "Joe" :person/age 22})))
```

### Client Entry Point (`src/main/app/client.cljs`)
```clojure
(ns app.client
  (:require
    [app.application :refer [app]]
    [app.ui :as ui]
    [com.fulcrologic.fulcro.application :as app]))

(defn ^:export init []
  (app/mount! app ui/Root "app"))

(defn ^:export refresh []
  (app/mount! app ui/Root "app"))
```

## Building and Running

### Start Shadow-cljs Server
```bash
npx shadow-cljs server
```

### Build Process
1. Navigate to http://localhost:9630 (Shadow-cljs UI)
2. Select "main" build under "Builds" menu
3. Click "start watch"
4. Access app at http://localhost:8000

### REPL Connection
```clojure
;; Connect to nREPL and select build
user=> (shadow/repl :main)
;; Test connection
cljs.user=> (js/alert "Hi")
```

## Component Development

### Component Anatomy
```clojure
(defsc ComponentName [this props]
  {:query [...] :ident [...] :initial-state {...}} ; optional
  (dom/div {:className "a" :style {:color "red"}}
    (dom/p "Hello")))
```

### DOM Element Factories
```clojure
;; Various syntax forms
(dom/div {:id "id" :className "x y z"} ...)
(dom/div :.x#id {:className "y z"} ...)
(dom/div :.x.y.z#id ...)
(dom/div :.x#id {:classes ["y" "z"]} ...)
```

### Factory Functions
```clojure
(def ui-person (comp/factory Person {:keyfn :person/id}))

;; Usage
(ui-person {:person/name "Joe" :person/age 22})
```

## Data Flow Basics

### Initial State
```clojure
(defsc Person [this {:person/keys [name age]}]
  {:initial-state (fn [{:keys [name age]}] {:person/name name :person/age age})}
  (dom/div ...))

(defsc Root [this {:keys [friends enemies]}]
  {:initial-state (fn [_] {:friends (comp/get-initial-state Person {:name "Joe" :age 22})})
  (dom/div (ui-person friends)))
```

### Queries
```clojure
(defsc Person [this {:person/keys [name age]}]
  {:query [:person/name :person/age]
   :initial-state ...}
  (dom/div ...))

(defsc Root [this {:keys [friends]}]
  {:query [{:friends (comp/get-query Person)}]}
  (dom/div (ui-person friends)))
```

### Passing Callbacks
**Incorrect:**
```clojure
(ui-person (assoc props :onDelete delete-fn)) ; Lost on refresh
```

**Correct:**
```clojure
(defsc Person [this {:person/keys [name]} {:keys [onDelete]}] ; computed props
  (dom/div (dom/button {:onClick #(onDelete name)} "Delete")))

;; Parent passes computed props
(ui-person (comp/computed person-data {:onDelete delete-fn}))
```

## Hot Code Reload
- Shadow-cljs provides automatic hot reload
- Edit component code and save
- UI updates without losing state
- Use refresh function for manual updates