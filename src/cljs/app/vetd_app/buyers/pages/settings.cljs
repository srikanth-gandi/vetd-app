(ns vetd-app.buyers.pages.settings
  (:require [vetd-app.buyers.components :as bc]
            [vetd-app.common.components :as cc]
            [vetd-app.ui :as ui]
            [vetd-app.util :as util]
            [vetd-app.docs :as docs]
            [reagent.core :as r]
            [re-frame.core :as rf]
            [re-com.core :as rc]
            [clojure.string :as s]))

;;;; Events
(rf/reg-event-fx
 :b/nav-settings
 (constantly
  {:nav {:path "/b/settings"}
   :analytics/track {:event "Navigate"
                     :props {:category "Navigation"
                             :label "Buyers Settings"}}}))

(rf/reg-event-fx
 :b/route-settings
 (fn [{:keys [db]}]
   {:db (assoc db :page :b/settings)
    :analytics/page {:name "Buyers Settings"}}))

;;;; Components
(defn c-field
  [k v ]
  [:> ui/GridRow
   [:> ui/GridColumn {:width 16}
    [:> ui/Segment {:class "display-field"
                    :vertical true}
     [:h3.display-field-key k]
     [:div.display-field-value v]]]])

(defn c-edit-user-name
  [user-name]
  (let [uname (r/atom user-name)]
    (fn []
      [:> ui/Input
       {:default-value @uname
        :auto-focus true
        :fluid true
        :style {:padding-top 7}
        :placeholder "Enter your full name..."
        :on-change #(reset! uname (-> % .-target .-value))
        :action (r/as-element
                 [:> ui/Button {:on-click #()
                                :color "blue"}
                  "Save"])}])))

(defn c-page []
  (let [user-name& (rf/subscribe [:user-name])
        user-email& (rf/subscribe [:user-email])
        org-name& (rf/subscribe [:org-name])
        edit-mode& (r/atom #{})]
    (fn []
      [:> ui/Grid
       [:> ui/GridRow
        [:> ui/GridColumn {:computer 5 :mobile 0}]
        [:> ui/GridColumn {:computer 6 :mobile 16}
         [bc/c-profile-segment {:title "Account Settings"}
          [:> ui/GridRow
           [:> ui/GridColumn {:width 16}
            [:> ui/Segment {:class "display-field"
                            :vertical true}
             (if (@edit-mode& "uname")
               [:> ui/Label {:on-click #(swap! edit-mode& disj "uname")
                             :as "a"
                             :style {:float "right"}}
                "Cancel"]
               [:> ui/Label {:on-click #(swap! edit-mode& conj "uname")
                             :as "a"
                             :style {:float "right"}}
                [:> ui/Icon {:name "edit outline"}]
                "Edit Name"])
             [:h3.display-field-key "Name"]
             [:div.display-field-value
              (if (@edit-mode& "uname")
                [c-edit-user-name @user-name&]
                @user-name&)]]]]
          [c-field "Email" @user-email&]
          [c-field "Organization" @org-name&]
          [:> ui/GridRow
           [:> ui/GridColumn {:width 16}
            [:> ui/Segment {:class "display-field"
                            :vertical true}
             [:> ui/Label {:as "a"
                           :style {:float "right"}}
              [:> ui/Icon {:name "edit outline"}]
              "Change Password"]
             [:h3.display-field-key "Password"]
             [:div.display-field-value [:em "hidden"]]]]]
          
          ]]
        [:> ui/GridColumn {:computer 5 :mobile 0}]]])))
