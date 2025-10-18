# Components and Rendering

## HTML5 Element Factories

### DOM Factory Functions
All HTML5 elements have factory functions in `com.fulcrologic.fulcro.dom`:

```clojure
(ns app.ui
  (:require [com.fulcrologic.fulcro.dom :as dom]))

(dom/div :.some-class
  (dom/ul {:style {:color "red"}}
    (dom/li "Item")))
```

### CLJC Compatibility
For files that run on both client and server:
```clojure
(ns app.ui
  (:require #?(:clj [com.fulcrologic.fulcro.dom-server :as dom]
               :cljs [com.fulcrologic.fulcro.dom :as dom])))
```

### DOM Syntax Options

**Basic forms:**
```clojure
(dom/div "Hello")                    ; no props
(dom/div nil "Hello")                ; explicit nil props (slight performance benefit)
(dom/div #js {:data-x 1} ...)        ; JavaScript objects allowed
```

**CSS shorthand:**
```clojure
(dom/div :.cls.cls2#id ...)          ; classes and ID
(dom/div :.cls {:data-x 2} "Ho")     ; shorthand + props
```

**Dynamic classes:**
```clojure
(dom/div :.a {:className "other" 
              :classes [(when hidden "hidden") 
                       (if tall :.tall :.short)]} ...)
```

### Performance Notes

**Macro vs Function:**
```clojure
(mapv dom/div ["a" "b"])     ; function - no source annotation
(dom/div nil "a")            ; macro - has source annotation
(dom/div {} "a")             ; macro - has source annotation
```

**Optimal performance:**
```clojure
(dom/div :.a {} (f))         ; no ambiguity - fastest compile-time conversion
(dom/div :.a (f))            ; ambiguous - runtime checks required
```

## The `defsc` Macro

### Purpose
Main macro for creating stateful components with sanity checking.

### Basic Structure
```clojure
(defsc ComponentName [this props computed extra]
  {:query         [...]           ; optional
   :ident         [...]           ; optional  
   :initial-state {...}}          ; optional
  (dom/div (:some-prop props)))
```

### Argument List
- **`this`**: Component instance (required)
- **`props`**: Component data (required)
- **`computed`**: Parent-computed data like callbacks (optional)
- **`extra`**: Extension point for libraries (optional)

### Argument Destructuring
```clojure
(defsc Person [this 
               {:keys [db/id person/name] :as props}
               {:keys [onClick] :or {onClick identity}}]
  {:query [:db/id :person/name]}
  (dom/div {:onClick onClick} name))
```

## Component Options

### Ident Generation

**Keyword form (recommended):**
```clojure
{:ident :person/id}  ; table name = ID key name
```

**Template form:**
```clojure
{:ident [:person/id :person/id]}  ; [table-name id-key]
```

**Lambda form (flexible):**
```clojure
{:ident (fn [] [:person/id (:person/id props)])}
```

### Query Definition

**Template form (recommended):**
```clojure
{:query [:person/id :person/name {:person/address (comp/get-query Address)}]}
```

**Lambda form (for complex queries):**
```clojure
{:query (fn [] [:person/id :person/name])}  ; Required for unions, wildcards
```

### Initial State

**Template form:**
```clojure
{:initial-state {:person/id :param/id
                 :person/name :param/name
                 :person/address {:address/street "123 Main St"}}}
```

**Lambda form:**
```clojure
{:initial-state (fn [{:keys [id name]}] 
                  {:person/id id :person/name name})}
```

**Auto-composition in template:**
```clojure
;; To-one relation
{:query [{:person/address (comp/get-query Address)}]
 :initial-state {:person/address {:address/street "123 Main St"}}}

;; To-many relation  
{:query [{:person/phones (comp/get-query Phone)}]
 :initial-state {:person/phones [{:phone/number "555-1234"}
                                 {:phone/number "555-5678"}]}}
```

## Pre-Merge Hook

### Purpose
Manipulate data entering the app at component level.

### Usage
```clojure
(defsc Person [this props]
  {:pre-merge (fn [{:keys [data-tree current-normalized state-map query]}]
                ;; Return modified data
                (assoc data-tree :ui/expanded false))}
  ...)
```

### Use Cases
- Add UI-only properties to server data
- Set default values for missing fields
- Transform data format
- Initialize child router state

## React Integration

### Element Keys
Always provide `:key` for collections:
```clojure
(map (fn [person] 
       (ui-person (assoc person :react-key (:person/id person))))
     people)

;; Or use factory keyfn
(def ui-person (comp/factory Person {:keyfn :person/id}))
(map ui-person people)
```

### Attribute Naming
Follow React conventions:
- `:className` instead of `:class`
- `:onClick` instead of `:onclick`
- `:htmlFor` instead of `:for`

## Factory Functions

### Creating Factories
```clojure
(def ui-person (comp/factory Person))

;; With automatic keys
(def ui-person (comp/factory Person {:keyfn :person/id}))

;; For computed props
(def ui-person (comp/computed-factory Person))
```

### Using Factories
```clojure
;; Basic usage
(ui-person {:person/name "Joe" :person/age 30})

;; With computed props
(ui-person (comp/computed person-data {:onClick delete-fn}))

;; Computed factory (two arguments)
(ui-person person-data {:onClick delete-fn})
```

## HTML Conversion

### Converting HTML to Fulcro
Common patterns for translating HTML:

**HTML:**
```html
<div class="container" id="main">
  <p>Hello <strong>World</strong></p>
</div>
```

**Fulcro:**
```clojure
(dom/div :.container#main
  (dom/p "Hello " (dom/strong "World")))
```

### Common Translations
- `class="x y"` → `:className "x y"` or `:.x.y`
- `<input type="text">` → `(dom/input {:type "text"})`
- Event handlers use camelCase: `onClick`, `onChange`, etc.

## Component Lifecycle

### React Lifecycle Methods
All React lifecycle methods available as component options:
```clojure
(defsc MyComponent [this props]
  {:componentDidMount    (fn [this] ...)
   :componentWillUnmount (fn [this] ...)
   :componentDidUpdate   (fn [this prev-props prev-state] ...)}
  ...)
```

### Common Patterns
```clojure
(defsc PersonList [this props]
  {:componentDidMount 
   (fn [this]
     (df/load! this :people Person))}  ; Load data on mount
  ...)
```

## Function Components

### Simple UI Functions
For presentation-only components:
```clojure
(defn my-header [title]
  (dom/div :.header
    (dom/h1 title)))
```

### Limitations
- No component lifecycle
- No query/ident/initial-state
- No render optimization
- No Fulcro Inspect visibility

### When to Use
- Pure presentation
- No data requirements
- No state management
- Simple reusable UI elements