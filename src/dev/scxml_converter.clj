(ns scxml-converter
  (:require
    [camel-snake-kebab.core :as csk]
    [clojure.pprint :refer [pprint]]
    [clojure.set :as set]
    [clojure.string :as str]
    [hickory.core :as hc]))

(def attr-renames {:class        :className
                   :for          :htmlFor
                   :tabindex     :tabIndex
                   :viewbox      :viewBox
                   :spellcheck   :spellcheck
                   :autocorrect  :autoCorrect
                   :autocomplete :autoComplete})

(def element-renames {'scxml   'machine
                      'onenter 'on-enter
                      'onexit  'on-exit})

(def coercions {:id     keyword
                :event  keyword
                :name   keyword
                :target (fn [t]
                          (let [targets (str/split t #" ")]
                            (if (= 1 (count targets))
                              (keyword (first targets))
                              (mapv keyword targets))))})

(defn element->call
  ([elem]
   (element->call elem {}))
  ([elem {:keys [ns-alias] :as options}]
   (cond
     (and (string? elem)
       (let [elem (str/trim elem)]
         (or
           (= "" elem)
           (and
             (str/starts-with? elem "<!--")
             (str/ends-with? elem "-->"))
           (re-matches #"^[ \n]*$" elem)))) nil
     (string? elem) (str/trim elem)
     (vector? elem) (let [tag               (symbol (name (first elem)))
                          tag               (get element-renames tag tag)
                          raw-props         (second elem)
                          attrs             (reduce-kv
                                              (fn [acc k v]
                                                (let [k (get element-renames k k)
                                                      v (if (contains? coercions k)
                                                          ((get coercions k) v)
                                                          v)]
                                                  (assoc acc k v)))
                                              {}
                                              (set/rename-keys raw-props attr-renames))
                          children          (keep (fn [c] (element->call c options)) (drop 2 elem))
                          expanded-children (reduce
                                              (fn [acc c]
                                                (if (vector? c)
                                                  (into [] (concat acc c))
                                                  (conj acc c)))
                                              []
                                              children)]
                      (concat (list) (keep identity
                                       [(if (seq ns-alias)
                                          (symbol ns-alias tag)
                                          (symbol tag))
                                        attrs]) expanded-children))
     :otherwise "")))

(defn translate-scxml
  "Convert an SCXML fragment (containing just one top-level XML element) into a corresponding statecharts
   CLJC code fragment.

  Options is a map that can contain:

  - `ns-alias`: The primary element namespace alias to use.  If not set, the calls will not be namespaced.
  "
  ([^String scxml-fragment options]
   (let [hiccup-list (map hc/as-hiccup (hc/parse-fragment scxml-fragment))]
     (let [result (keep (fn [e] (element->call e options)) hiccup-list)]
       (if (< 1 (count result))
         (vec result)
         (first result)))))
  ([^String html-fragment]
   (translate-scxml html-fragment {:ns-alias "ele"})))


(comment
  (translate-scxml "
<scxml version=\"1.0\" datamodel=\"ecmascript\" initial=\"master\"> <state id=\"master\">
    <initial id=\"init1\">
      <transition target=\"_home\"/>
    </initial>
    <transition event=\"new_dealer\" target=\"NewDealer\"/>
    <transition event=\"mumble\" target=\"_home\"/> <!-- bail out to caller -->
    <transition event=\"silence\" target=\"_home\"/> <!-- bail out to caller -->
    <state id=\"_home\">
      <onenter>
        <script>
        _data = {};
        </script>
      </onenter>
      <invoke src=\"datamodel.v3#InitDataModel\" type=\"vxml3\">
        <finalize>
          <script>
          var n;
          for (n in event) {
              _data[n] = event[n];
          }
          </script>
        </finalize>
      </invoke>
      <transition event=\"success\" target=\"Welcome\"/>
    </state>

    <state id=\"Welcome\">
      <invoke src=\"dialog.vxml#Welcome\" type=\"vxml3\">
        <param name=\"skinpath\" expr=\"skinpath\"/>
      </invoke>
      <transition event=\"success\" target=\"Intro2\"/>
    </state>

    <state id=\"Intro2\">
      <invoke src=\"dialog.vxml#Intro2\" type=\"vxml3\">
        <param name=\"skinpath\" expr=\"skinpath\"/>
      </invoke>
      <transition event=\"success\" target=\"EvalDeal\"/>
    </state>

    <state id=\"EvalDeal\">
      <onenter>
        <script>enterEvalDeal();</script>
      </onenter>
      <invoke src=\"dialog.vxml#EvalDeal\" type=\"vxml3\">
        <param name=\"skinpath\" expr=\"skinpath\"/>
        <param name=\"playercard1\" expr=\"playercard1\"/>
        <param name=\"playercard2\" expr=\"playercard2\"/>
        <param name=\"playertotal\" expr=\"blackjack.GetTotalOf('caller').toString()\"/>
        <param name=\"dealercardshowing\" expr=\"dealercardshowing\"/>
      </invoke>
      <transition event=\"success\" target=\"AskHit\"/>
    </state>

    <state id=\"AskHit\">
      <invoke src=\"dialog.vxml#AskHit\" type=\"vxml3\">
        <param name=\"skinpath\" expr=\"skinpath\"/>
        <finalize>
          <script>finalizeAskHit();</script>
        </finalize>
      </invoke>
      <transition event=\"hit\" target=\"PlayNewCard\"/>
      <transition event=\"stand\" target=\"PlayDone\"/>
    </state>

    <state id=\"PlayNewCard\">
      <invoke src=\"dialog.vxml#PlayNewCard\" type=\"vxml3\">
        <param name=\"skinpath\" expr=\"skinpath\"/>
        <param name=\"playernewcard\" expr=\"playernewcard\"/>
        <param name=\"playertotal\" expr=\"blackjack.GetTotalOf('caller').toString()\"/>
      </invoke>
      <transition event=\"success\" cond=\"blackjack.GetTotalOf('caller') &gt;= 21\" target=\"PlayDone\"/>
      <transition event=\"success\" target=\"AskHit\"/> <!-- less than 21 -->
    </state>

    <state id=\"PlayDone\">
      <onenter>
        <script>enterPlayDone();</script>
      </onenter>
      <invoke src=\"dialog.vxml#PlayDone\" type=\"vxml3\">
        <param name=\"skinpath\" expr=\"skinpath\"/>
        <param name=\"gameresult\" expr=\"blackjack.GetGameResult()\"/>
        <param name=\"dealertotal\" expr=\"blackjack.GetTotalOf('dealer').toString()\"/>
      </invoke>
      <transition event=\"playagain\" target=\"Intro2\"/>
      <transition event=\"quit\" target=\"_home\"/>
    </state>

    <state id=\"NewDealer\">
      <onenter>
       <script>enterNewDealer();</script>
      </onenter>
      <invoke src=\"dialog.vxml#Dummy\" type=\"vxml3\"/>
      <transition event=\"success\" target=\"Welcome\"/>
    </state>
  </state>
</scxml>
"
    {:ns-alias nil}))
