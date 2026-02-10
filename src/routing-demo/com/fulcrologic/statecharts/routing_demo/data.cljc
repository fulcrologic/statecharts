(ns com.fulcrologic.statecharts.routing-demo.data
  "Pre-seeded mock data for the event planner routing demo.

   Simulates a backend database with events, days, and menus.
   Loading functions simulate async I/O with configurable delay.")

(def events-db
  "Mock database of events, keyed by event ID."
  {1 {:event/id   1
      :event/name "Annual Tech Conference"
      :event/days [1 2 3]}
   2 {:event/id   2
      :event/name "Community Meetup"
      :event/days [1]}})

(def days-db
  "Mock database of days, keyed by [event-id day-num]."
  {[1 1] {:day/num      1
          :day/name     "Day 1 - Keynotes"
          :day/event-id 1}
   [1 2] {:day/num      2
          :day/name     "Day 2 - Workshops"
          :day/event-id 1}
   [1 3] {:day/num      3
          :day/name     "Day 3 - Closing"
          :day/event-id 1}
   [2 1] {:day/num      1
          :day/name     "Day 1 - Meetup"
          :day/event-id 2}})

(def menus-db
  "Mock database of menus, keyed by [event-id day-num]."
  {[1 1] {:menu/items ["Coffee & Pastries" "Lunch Buffet" "Afternoon Tea"]}
   [1 2] {:menu/items ["Continental Breakfast" "Workshop Snacks" "Dinner Banquet"]}
   [1 3] {:menu/items ["Brunch" "Farewell Reception"]}
   [2 1] {:menu/items ["Pizza" "Craft Beer"]}})

(defn lookup-event
  "Returns the event map for `event-id`, or nil if not found."
  [event-id]
  (get events-db event-id))

(defn lookup-day
  "Returns the day map for the given `event-id` and `day-num`, or nil if not found."
  [event-id day-num]
  (get days-db [event-id day-num]))

(defn lookup-menu
  "Returns the menu map for the given `event-id` and `day-num`, or nil if not found."
  [event-id day-num]
  (get menus-db [event-id day-num]))
