(ns com.fulcrologic.statecharts.integration.fulcro.rad-integration
  (:require
    [com.fulcrologic.rad.form :as form]
    [com.fulcrologic.rad.form-options :as fo]
    [com.fulcrologic.rad.report :as report]
    [com.fulcrologic.rad.report-options :as ro]
    [com.fulcrologic.rad.options-util :refer [?!]]
    [com.fulcrologic.fulcro-i18n.i18n :refer [tr]]
    [com.fulcrologic.fulcro.algorithms.form-state :as fs]
    [com.fulcrologic.fulcro.raw.application :as rapp]
    [com.fulcrologic.fulcro.raw.components :as rc]
    [com.fulcrologic.fulcro.algorithms.normalized-state :as fns]
    [com.fulcrologic.statecharts.integration.fulcro.ui-routes :as uir]
    [com.fulcrologic.statecharts.integration.fulcro.ui-routes-options :as uo]))

(defn busy-form-handler
  "Use as the uo/busy? option in RAD forms to prevent routing when forms are dirty.

  ```
  (defsc-form MyForm [this props]
    {uo/busy? (busy-form-handler MyForm)
     ...
  ```
  "
  [FormClass]
  (fn [{:fulcro/keys [app]} {:route/keys [idents]}]
    (let [form-ident (get idents (rc/class->registry-key FormClass))
          form-props (when form-ident (fns/ui->props (rapp/current-state app) FormClass form-ident))]
      (and form-props (fs/dirty? form-props)))))

(defn form-controls
  "Use this to replace the implementation of form controls with controls that use statechart UI Routing instead of dynamic routing.

   options map has:

   * :cancel-route - The registry key of the component to route to when leaving the form
   * :cancel-route - The registry key of the component to route to when leaving the form


   ```
   (defsc-form MyForm [this props]
     {fo/controls (form-controls ...)
      ...
   ```
  "
  [{:keys [cancel-route]}]
  (merge form/standard-controls
    {::form/done {:type   :button
                  :local? true
                  :label  (fn [this]
                            (let [props           (rc/props this)
                                  read-only-form? (?! (rc/component-options this ::read-only?) this)
                                  dirty?          (if read-only-form? false (or (:ui/new? props) (fs/dirty? props)))]
                              (if dirty? (tr "Cancel") (tr "Done"))))
                  :class  (fn [this]
                            (let [props  (rc/props this)
                                  dirty? (or (:ui/new? props) (fs/dirty? props))]
                              (if dirty? "ui tiny primary button negative" "ui tiny primary button positive")))
                  :action (fn [this] (when cancel-route (uir/route-to! this cancel-route)))}
     ::form/save {:type      :button
                  :local?    true
                  :disabled? (fn [this]
                               (let [props           (rc/props this)
                                     read-only-form? (?! (rc/component-options this ::read-only?) this)
                                     remote-busy?    (seq (:com.fulcrologic.fulcro.application/active-remotes props))
                                     dirty?          (if read-only-form? false (or (:ui/new? props) (fs/dirty? props)))]
                                 (or (not dirty?) remote-busy?)))
                  :visible?  (fn [this] (not (form/view-mode? this)))
                  :label     (fn [_] (tr "Save"))
                  :class     (fn [this]
                               (let [props        (rc/props this)
                                     remote-busy? (seq (:com.fulcrologic.fulcro.application/active-remotes props))]
                                 (when remote-busy? "ui tiny primary button loading")))
                  :action    (fn [this] (form/save! {::form/master-form this}))}}))
