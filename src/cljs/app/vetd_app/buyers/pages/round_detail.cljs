(ns vetd-app.buyers.pages.round-detail
  (:require [vetd-app.buyers.components :as bc]
            [vetd-app.common.components :as cc]
            [vetd-app.ui :as ui]
            [vetd-app.util :as util]
            [vetd-app.docs :as docs]
            [reagent.core :as r]
            [reagent.format :as format]
            [re-frame.core :as rf]
            [clojure.string :as s]))

(defonce cell-click-disabled? (r/atom false))
;; the id of the product currently being reordered
(defonce reordering-product (r/atom nil))
;; the current x position of a column being reordered
(defonce curr-reordering-pos-x (r/atom nil))

;; Events
(rf/reg-event-fx
 :b/nav-round-detail
 (fn [_ [_ round-idstr]]
   {:nav {:path (str "/b/rounds/" round-idstr)}}))

(rf/reg-event-fx
 :b/route-round-detail
 (fn [{:keys [db]} [_ round-idstr]]
   {:db (assoc db
               :page :b/round-detail
               :page-params {:round-idstr round-idstr})
    :analytics/page {:name "Buyers Round Detail"
                     :props {:round-idstr round-idstr}}}))

(rf/reg-event-fx
 :b/round.initiation-form-saved
 (fn [_ [_ _ {{:keys [round-id]} :return}]]
   {:toast {:type "success"
            :title "Initiation Form Submitted"
            :message "Status updated to \"In Progress\""}
    :analytics/track {:event "Initiation Form Saved"
                      :props {:category "Round"
                              :label round-id}}}))

(rf/reg-event-fx
 :b/round.add-requirement
 (fn [{:keys [db]} [_ round-id requirement-text]]
   {:ws-send {:payload {:cmd :b/round.add-requirement
                        :round-id round-id
                        :requirement-text requirement-text
                        :buyer-id (util/db->current-org-id db)}}
    :analytics/track {:event "Add Requirement"
                      :props {:category "Round"
                              :label round-id}}}))

(rf/reg-event-fx
 :b/round.add-product
 ;; "products" is a mixed coll of product id's (for adding products that exist),
 ;; and product names (for adding products that don't exist in our DB yet)
 (fn [{:keys [db]} [_ round-id products]]
   {:ws-send {:payload {:cmd :b/round.add-product
                        :round-id round-id
                        :buyer-id (util/db->current-org-id db)
                        :product-ids (filter number? products)
                        :product-names (filter string? products)}}
    :analytics/track {:event "Add Product"
                      :props {:category "Round"
                              :label round-id}}}))

(rf/reg-event-fx
 :b/round.declare-winner
 (fn [{:keys [db]} [_ round-id product-id reason]]
   {:ws-send {:payload {:cmd :b/round.declare-result
                        :round-id round-id
                        :product-id product-id
                        :result 1
                        :reason reason
                        :buyer-id (util/db->current-org-id db)}}
    :confetti nil
    :analytics/track {:event "Declare Winner"
                      :props {:category "Round"
                              :label round-id}}}))

(rf/reg-event-fx
 :b/round.disqualify
 (fn [{:keys [db]} [_ round-id product-id reason]]
   {;; :db (update-in db [:loading? :products] conj product-id)
    :ws-send {:payload {:cmd :b/round.declare-result
                        :return {:handler :b/round.declare-result-return
                                 :round-id round-id
                                 :product-id product-id}
                        :round-id round-id
                        :product-id product-id
                        :result 0
                        :reason reason
                        :buyer-id (util/db->current-org-id db)}}}))

(rf/reg-event-fx
 :b/round.declare-result-return
 (fn [{:keys [db]} [_ _ {{:keys [round-id product-id]} :return}]]
   {;; :db (update-in db [:loading? :products] disj product-id)
    :toast {:type "success"
            :title "Product Disqualified from VetdRound"}
    :analytics/track {:event "Disqualify Product"
                      :props {:category "Round"
                              :label round-id}}}))

(rf/reg-event-fx
 :b/round.undo-disqualify
 (fn [{:keys [db]} [_ round-id product-id]]
   {:ws-send {:payload {:cmd :b/round.declare-result
                        :round-id round-id
                        :product-id product-id
                        :result nil
                        :buyer-id (util/db->current-org-id db)}}
    :analytics/track {:event "Undo Disqualify Product"
                      :props {:category "Round"
                              :label round-id}}}))

(rf/reg-event-fx
 :b/round.ask-a-question
 (fn [{:keys [db]} [_ product-id product-name message
                    round-id requirement-text]]
   {:ws-send {:payload {:cmd :b/ask-a-question
                        :return {:handler :b/ask-a-question-return}
                        :product-id product-id
                        :message message
                        :round-id round-id
                        :requirement-text requirement-text
                        :buyer-id (util/db->current-org-id db)}}
    :analytics/track {:event "Ask A Question"
                      :props {:category "Round"
                              :label product-name}}}))

(rf/reg-event-fx
 :b/round.rate-response
 (fn [{:keys [db]} [_ response-id rating]]
   {:ws-send {:payload {:cmd :save-response
                        :subject response-id
                        :subject-type "response"
                        :term :round.response/rating
                        :fields {:value rating}
                        :user-id (-> db :user :id)
                        :org-id (util/db->current-org-id db)}}
    :analytics/track {:event "Rate Response"
                      :props {:category "Round"
                              :label rating}}}))

(rf/reg-event-fx
 :b/set-round-products-order
 (fn [{:keys [db]} [_ new-round-products-order]]
   {:db (assoc db :round-products-order new-round-products-order)}))

;; persist any changes to round products sort order to backend
(rf/reg-event-fx
 :b/store-round-products-order
 (fn [{:keys [db]} [_ round-id]]
   {:ws-send {:payload {:cmd :b/set-round-products-order
                        :product-ids (:round-products-order db)
                        :round-id round-id                        
                        :user-id (-> db :user :id)
                        :org-id (util/db->current-org-id db)}}}))

;; Subscriptions
(rf/reg-sub
 :round-idstr
 :<- [:page-params] 
 (fn [{:keys [round-idstr]}] round-idstr))

(rf/reg-sub
 :round-products-order
 (fn [{:keys [round-products-order]}] round-products-order))

;; Components
(defn get-requirements-options []
  (ui/as-dropdown-options [;; "Subscription Billing" "Free Trial"
                           ]))

(defn c-round-initiation-form
  [round-id]
  (let [requirements-options (r/atom (get-requirements-options))
        ;; form data
        goal (r/atom "")
        start-using (r/atom "")
        num-users (r/atom "")
        budget (r/atom "")
        requirements (r/atom [])
        add-products-by-name (r/atom "")]
    (fn []
      [:<>
       [:h3 "VetdRound Initiation Form"]
       [:p "Let us know a little more about who will be using this product and what features you are looking for. Then, we'll gather quotes for you to compare right away."]
       [:> ui/Form {:as "div"
                    :class "round-initiation-form"}
        [:> ui/FormTextArea
         {:label "What are you hoping to accomplish with the product?"
          :on-change (fn [e this]
                       (reset! goal (.-value this)))}]
        [:> ui/FormGroup {:widths "equal"}
         [:> ui/FormField
          [:label "When do you need to decide by?"]
          [:> ui/Dropdown {:selection true
                           :options (ui/as-dropdown-options
                                     ["Within 2 Weeks" "Within 3 Weeks" "Within 1 Month"
                                      "Within 2 Months" "Within 6 Months" "Within 12 Months"])
                           :on-change (fn [_ this]
                                        (reset! start-using (.-value this)))}]]
         [:> ui/FormField
          [:label "What is your annual budget?"]
          [:> ui/Input {:labelPosition "right"}
           [:> ui/Label {:basic true} "$"]
           [:input {:type "number"
                    :style {:width 0} ; idk why 0 width works, but it does
                    :on-change #(reset! budget (-> % .-target .-value))}]
           [:> ui/Label {:basic true} " per year"]]]
         [:> ui/FormField
          [:label "How many users?"]
          [:> ui/Input {:labelPosition "right"}
           [:input {:type "number"
                    :on-change #(reset! num-users (-> % .-target .-value))}]
           [:> ui/Label {:basic true} "users"]]]]
        [:> ui/FormField
         [:label "What are your product requirements?"]
         [:> ui/Dropdown {:value @requirements
                          :options @requirements-options
                          :placeholder "Add your requirements..."
                          :search true
                          :selection true
                          :multiple true
                          :allowAdditions true
                          :additionLabel "Hit 'Enter' to Add "
                          :noResultsMessage "Type to add a new requirement..."
                          :onAddItem (fn [_ this]
                                       (let [value (.-value this)]
                                         (swap! requirements-options
                                                conj
                                                {:key value
                                                 :text value
                                                 :value value})))
                          :onChange (fn [_ this]
                                      (reset! requirements (.-value this)))}]]
        #_[:> ui/FormField
           [:label "Are there specific products you want to include?"]
           [:> ui/Dropdown {:multiple true
                            :search true
                            :selection true
                            ;; :on-change (fn [_ this]
                            ;;              (reset! add-products-by-name (.-value this)))
                            :on-change #(.log js/console %1 %2)}]]
        [:> ui/FormButton
         {:color "blue"
          :on-click
          #(rf/dispatch
            [:save-doc
             {:dtype "round-initiation"
              :round-id round-id
              :return {:handler :b/round.initiation-form-saved
                       :round-id round-id}}
             {:terms
              {:rounds/goal {:value @goal}
               :rounds/start-using {:value @start-using}
               :rounds/num-users {:value @num-users}
               :rounds/budget {:value @budget}
               :rounds/requirements {:value @requirements}
               :rounds/add-products-by-name {:value @add-products-by-name}}}])}
         "Submit"]]])))

(defn c-round-initiation
  [{:keys [id status title products init-doc] :as round}]
  (if init-doc
    "You have already submitted your requirements." ; this should never show
    [c-round-initiation-form id]))

(defn c-declare-winner-button
  [round product won?]
  (let [popup-open? (r/atom false)
        context-ref (r/atom nil)
        reason (atom "")]
    (fn [round product won?]
      (if-not won?
        [:<>
         [:> ui/Popup
          {:position "top right"
           :on "click"
           :open @popup-open?
           :on-close #(reset! popup-open? false)
           :context @context-ref
           :content (r/as-element
                     [:> ui/Form {:style {:width 325}}
                      [:h4 {:style {:margin-bottom 7}}
                       "Declare Winner of VetdRound"]
                      [:p {:style {:margin-top 0}}
                       "We will put you in touch with " (:oname (:vendor product)) " as soon as possible."]
                      [:> ui/FormField
                       [:label "Why did you pick " (:pname product) "?"]
                       [:> ui/Input
                        {:placeholder "Enter a reason for your future reference..."
                         :on-change (fn [_ this]
                                      (reset! reason (.-value this)))}]
                       [:> ui/Button
                        {:color "vetd-gradient"
                         :fluid true
                         :style {:margin-top 7}
                         :on-click #(do (reset! popup-open? false)
                                        (rf/dispatch [:b/round.declare-winner
                                                      (:id round)
                                                      (:id product)
                                                      @reason]))}
                        "Declare Winner"]]])}]
         [:> ui/Popup
          {:content "Declare Winner"
           :position "bottom center"
           :context @context-ref
           :trigger (r/as-element
                     [:> ui/Button
                      {:icon "checkmark"
                       :color "lightblue"
                       :ref (fn [this] (reset! context-ref (r/dom-node this)))
                       :on-click #(swap! popup-open? not)
                       :size "mini"}])}]]

        [:> ui/Popup
         {:content "Declared Winner"
          :position "bottom center"
          :trigger (r/as-element
                    [:> ui/Button
                     {:icon true
                      :color "white"
                      :size "mini"
                      :disabled true}
                     [:<> [:> ui/Icon {:name "checkmark"}] " Winner"]])}]))))


(defn c-setup-call-button
  [round product vendor won?]
  [:> ui/Popup
   {:content (str "Set up call with " (:oname vendor))
    :position "bottom center"
    :trigger (r/as-element
              [:> ui/Button
               {:icon "call"
                :color (if won? "white" "lightteal")
                :onClick #(rf/dispatch [:b/setup-call (:id product) (:pname product)])
                :size "mini"}])}])

(defn c-disqualify-button
  [round product disqualified?]
  (let [popup-open? (r/atom false)
        context-ref (r/atom nil)
        reason (atom "")]
    (fn [round product disqualified?]
      (if-not disqualified?
        [:<>
         [:> ui/Popup
          {:position "top right"
           :on "click"
           :open @popup-open?
           :on-close #(reset! popup-open? false)
           :context @context-ref
           :content (r/as-element
                     [:> ui/Form {:style {:width 400}}
                      [:> ui/FormField
                       [:> ui/Input
                        {:placeholder "Enter reason..."
                         :on-change (fn [_ this]
                                      (reset! reason (.-value this)))
                         :action (r/as-element
                                  [:> ui/Button
                                   {:color "teal"
                                    :on-click #(do (reset! popup-open? false)
                                                   (rf/dispatch [:b/round.disqualify
                                                                 (:id round)
                                                                 (:id product)
                                                                 @reason]))}
                                   "Disqualify"])}]]])}]
         [:> ui/Popup
          {:content "Disqualify"
           :position "bottom center"
           :context @context-ref
           :trigger (r/as-element
                     [:> ui/Button {:icon "ban"
                                    :basic true
                                    :ref (fn [this] (reset! context-ref (r/dom-node this)))
                                    :size "mini"
                                    :on-click #(swap! popup-open? not)}])}]]
        [:> ui/Popup
         {:content "Undo Disqualify"
          :position "bottom center"
          :trigger (r/as-element
                    [:> ui/Button {:icon "undo"
                                   :basic true
                                   :on-click #(rf/dispatch [:b/round.undo-disqualify (:id round) (:id product)])
                                   :size "mini"}])}]))))

(defn c-action-button
  "Component to display small icon button for grid cell actions."
  [{:keys [icon on-click popup-text props]}]
  [:> ui/Popup
   {:content popup-text
    :position "bottom center"
    :trigger (r/as-element
              [:> ui/Button (merge {:on-click on-click
                                    :icon icon
                                    :basic true
                                    :size "mini"}
                                   props)])}])

(defn c-no-response []
  [:> ui/Popup
   {:content "Vendor did not respond"
    :position "bottom center"
    :trigger (r/as-element
              [:> ui/Icon {:name "ban"
                           :size "large"
                           :style {:color "#aaa"}}])}])

(defn c-waiting-for-response []
  [:> ui/Popup
   {:content "Waiting for vendor response"
    :position "bottom center"
    :trigger (r/as-element
              [:> ui/Icon {:name "clock outline"
                           :size "large"
                           :style {:color "#aaa"}}])}])

(defn c-cell-modal
  [round-id modal-showing?& modal-response&]
  (let [modal-message& (r/atom "")]
    (fn [round-id modal-showing?& modal-response&]
      (let [{:keys [req-prompt-id req-prompt-text
                    product-id pname
                    resp-id resp-text resp-rating]
             :as modal-response} @modal-response&]
        [:> ui/Modal {:open @modal-showing?&
                      :on-close #(reset! modal-showing?& false)
                      :size "tiny"
                      :dimmer "inverted"
                      :closeOnDimmerClick true
                      :closeOnEscape true
                      :closeIcon true} 
         [:> ui/ModalHeader pname]
         [:> ui/ModalContent {:class (str (when (= 1 resp-rating) "response-approved ")
                                          (when (= 0 resp-rating) "response-disapproved "))}
          [:h4 {:style {:padding-bottom 10}}
           [c-action-button {:on-click #(rf/dispatch [:b/round.rate-response resp-id 0])
                             :icon "thumbs down outline"
                             :popup-text (if (= 0 resp-rating) "Disapproved" "Disapprove")
                             :props {:class "disapprove"
                                     :style {:float "right"
                                             :margin-right 0}}}]
           [c-action-button {:on-click #(rf/dispatch [:b/round.rate-response resp-id 1])
                             :icon "thumbs up outline"
                             :popup-text (if (= 1 resp-rating) "Approved" "Approve")
                             :props {:class "approve"
                                     :style {:float "right"
                                             :margin-right 4}}}]
           req-prompt-text]
          (if resp-text
            (util/parse-md resp-text)
            "Waiting for vendor response.")]
         [:> ui/ModalActions
          [:> ui/Form {:as "div"
                       :style {:padding-bottom "1rem"}}
           [:> ui/FormField
            [:> ui/TextArea {:placeholder "Ask a follow-up question..."
                             :autoFocus true
                             :spellCheck true
                             :onChange (fn [_ this]
                                         (reset! modal-message& (.-value this)))}]]]
          [:> ui/Button {:onClick #(reset! modal-showing?& false)}
           "Cancel"]
          [:> ui/Button
           {:onClick #(do (rf/dispatch
                           [:b/round.ask-a-question
                            product-id pname @modal-message& round-id req-prompt-text])
                          (reset! modal-showing?& false))
            :color "blue"}
           "Submit Question"]]]))))

(defn c-cell-text
  [resp-text round-status]
  [:div.text
   (if (not-empty resp-text)
     (util/truncate-text resp-text 150)
     (if (= round-status "complete")
       [c-no-response]
       [c-waiting-for-response]))])

(defn c-cell-actions
  [resp-id resp-rating]
  (let [rating-approved 1
        rating-disapproved 0]
    [:div.actions
     [c-action-button {:props {:class "action-button question"}
                       :icon "chat outline" ; no on-click, just pass through to cell click
                       :popup-text "Ask Question"}]
     [c-action-button {:props {:class "action-button approve"}
                       :on-click #(do (.stopPropagation %) ; stop propagation to cell click
                                      (rf/dispatch [:b/round.rate-response resp-id rating-approved]))
                       :icon "thumbs up outline"
                       :popup-text (if (= rating-approved resp-rating) "Approved" "Approve")}]
     [c-action-button {:props {:class "action-button disapprove"}
                       :on-click #(do (.stopPropagation %) ; stop propagation to cell click
                                      (rf/dispatch [:b/round.rate-response resp-id rating-disapproved]))
                       :icon "thumbs down outline"
                       :popup-text (if (= rating-disapproved resp-rating) "Disapproved" "Disapprove")}]]))

(rf/reg-sub
 :loading?
 (fn [{:keys [loading?]} [_ product-id]]
   ((:products loading?) product-id)))

(defn c-column
  [round req-form-template rp show-modal-fn]
  (let [loading?& (rf/subscribe [:loading? (:id (:product rp))])
        products-order& (rf/subscribe [:round-products-order])]
    (fn [{:keys [id status] :as round}
         {:keys [prompts] :as req-form-template}
         rp
         show-modal-fn]
      (let [{pname :pname
             product-id :id
             product-idstr :idstr
             vendor :vendor
             preposals :docs
             :as product} (:product rp)
            rp-result (:result rp)
            [won? disqualified?] ((juxt (partial = 1) zero?) rp-result)]
        [:div.column {:class (str (when won? " winner")
                                  (when disqualified? " disqualified"))
                      :data-product-id product-id
                      :style {:transform
                              (str "translateX("
                                   (if (and @products-order& ; to trigger re-render
                                            (= @reordering-product product-id))
                                     @curr-reordering-pos-x
                                     (* 234 (.indexOf @products-order& product-id)))
                                   "px)")}}
         [:div.round-product {:class (when (> (count pname) 17) " long")}
          (if @loading?&
            [cc/c-loader]
            [:div
             [:a.name {:on-mouse-down #(reset! cell-click-disabled? false)
                       :on-click #(when-not @cell-click-disabled?
                                    (rf/dispatch
                                     (if (seq preposals)
                                       [:b/nav-preposal-detail (-> preposals first :idstr)]
                                       [:b/nav-product-detail product-idstr])))}
              pname]
             (when-not disqualified?
               [c-declare-winner-button round product won?])
             (when-not disqualified?
               [c-setup-call-button round product vendor won?])
             (when-not won?
               [c-disqualify-button round product disqualified?])])]
         (for [req prompts
               :let [{req-prompt-id :id
                      req-prompt-text :prompt} req
                     response-prompts (-> rp :vendor-response-form-docs first :response-prompts)
                     response-prompt (docs/get-response-prompt-by-prompt-id
                                      response-prompts
                                      req-prompt-id)
                     resp-rating (some-> response-prompt
                                         :subject-of-response-prompt
                                         first
                                         :response-prompt-fields
                                         first
                                         :nval)
                     resp (docs/get-response-field-by-prompt-id response-prompts req-prompt-id)
                     {id :id
                      resp-id :resp-id
                      resp-text :sval} resp]]
           ^{:key (str req-prompt-id "-" product-id)}
           [:div.cell {:class (str (when (= 1 resp-rating) "response-approved ")
                                   (when (= 0 resp-rating) "response-disapproved "))
                       :on-mouse-down #(reset! cell-click-disabled? false)
                       :on-click #(when-not @cell-click-disabled?
                                    (show-modal-fn {:req-prompt-id req-prompt-id
                                                    :req-prompt-text req-prompt-text
                                                    :product-id product-id
                                                    :pname pname
                                                    :resp-id resp-id
                                                    :resp-text resp-text
                                                    :resp-rating resp-rating}))}
            [c-cell-text resp-text status]
            [c-cell-actions resp-id resp-rating]])]))))

(defn c-round-grid*
  [round req-form-template round-product]
  (let [;; The response currently in the modal.
        ;; E.g., {:req-prompt-id 123
        ;;        :req-prompt-text "Something"
        ;;        :product-id 321
        ;;        :pname "Some Product"
        ;;        :resp-id 456
        ;;        :resp-text "Some answer to prompt"
        ;;        :resp-rating 1}
        modal-response& (r/atom {})
        modal-showing?& (r/atom false)
        show-modal-fn (fn [response]
                        (reset! modal-response& response)
                        (reset! modal-showing?& true))
        last-default-products-order& (atom [])]
    (fn [round req-form-template round-product]
      (let [default-products-order (vec (map (comp :id :product) round-product))]
        (when (not= @last-default-products-order& default-products-order)
          (reset! last-default-products-order& default-products-order)
          (rf/dispatch [:b/set-round-products-order default-products-order])))
      (if (seq round-product)
        [:<>
         [:div.round-grid {:style {:min-height (+ 46 84 (* 202 (-> req-form-template :prompts count)))}}
          [:div {:style {:min-width (* 234 (count round-product))}}
           (for [rp round-product]
             ^{:key (-> rp :product :id)}
             [c-column round req-form-template rp show-modal-fn])]]
         [c-cell-modal (:id round) modal-showing?& modal-response&]]
        ;; no products in round yet
        [:> ui/Segment {:class "detail-container"
                        :style {:margin-left 20}}
         [:p [:em "Your requirements have been submitted."]]
         [:p (str "We are gathering information for you to review "
                  "from all relevant vendors. Check back soon for updates.")]]))))

(def c-round-grid
  (let [component-exists? (atom true)
        ;; keep a reference to the window-scroll fn (will be created on mount)
        ;; so we can remove the event listener upon unmount
        window-scroll-fn-ref (atom nil)
        ;; really just affects which cursor displayed
        update-draggability
        (fn [this]
          (let [node (r/dom-node this)]
            (if (> (.-scrollWidth node) (.-clientWidth node))
              (.add (.-classList (r/dom-node this)) "draggable")
              (.remove (.-classList (r/dom-node this)) "draggable"))))
        products-order& (rf/subscribe [:round-products-order])]
    (with-meta c-round-grid*
      {:component-did-mount
       (fn [this] ; make grid draggable (scrolling & reordering)
         (let [round-id (-> this r/props :id)
               node (r/dom-node this)
               col-width 234 ; includes any spacing to the right of each col
               mousedown? (atom false)
               x-at-mousedown (atom nil)
               mouse-x (atom nil) ; current mouse pos x
               last-mouse-x (atom nil)
               last-mouse-delta (atom 0)
               drag-scrolling? (atom false)
               drag-direction-intention (atom nil)
               ;; distance that user mousedown'd from left side of column being dragged
               drag-handle-offset (atom nil)

               ;; think of this as the grid's scrollLeft
               scroll-x (atom 0)
               ;; scroll velocity (on x axis)
               scroll-v (atom 0)
               ;; coefficient of friction
               scroll-k (atom 0.85)
               ;; amount of acceleration that gets applied per 1px mouse drag
               scroll-a-factor 1
               ;; when reordering near left or right edge of grid
               scroll-speed-reordering 7
               ;; this should be updated on resize
               scroll-x-max (- (.-scrollWidth node) (.-clientWidth node))

               ;; make header row 'sticky' upon vertical window scroll
               header-pickup-y (atom nil) ; nil when not in 'sticky mode'
               all-header-nodes #(array-seq (.getElementsByClassName js/document "round-product"))
               ;; this needs to be called when we leave 'sticky mode'
               zero-out-header-scroll (fn []
                                        (doseq [header-node (all-header-nodes)]
                                          (aset (.-style header-node) "transform" "translateY(0px)")))
               ;; turn on and off header row 'sticky mode' as needed
               window-scroll (fn []
                               (.requestAnimationFrame
                                js/window
                                (fn []
                                  (let [window-scroll-y (.-scrollY js/window)
                                        node-offset-top (.-offsetTop node)]
                                    (if @header-pickup-y
                                      (if (< window-scroll-y @header-pickup-y)
                                        (do (reset! header-pickup-y nil)
                                            (.remove (.-classList node) "fixed")
                                            (zero-out-header-scroll))
                                        (doseq [header-node (all-header-nodes)]
                                          (aset (.-style header-node) "transform"
                                                (str "translateY(" (- window-scroll-y node-offset-top) "px)"))))
                                      (when (> window-scroll-y node-offset-top)
                                        (reset! header-pickup-y node-offset-top)
                                        (.add (.-classList node) "fixed")))))))
               ;; keep a reference to the window-scroll function (for listener removal)
               _ (reset! window-scroll-fn-ref window-scroll)

               ;; is a given dom node considered a handle for beginning a column drag (for reorder)?
               part-of-drag-handle? (fn [dom-node]
                                      (let [class-list (.-classList dom-node)]
                                        (or (.contains class-list "round-product") ; top portion of column
                                            (.contains class-list "name") ; the product name
                                            (empty? (array-seq class-list))))) ; the column node itself
               part-of-scrollbar? (fn [y]
                                    (> (- y (.-offsetTop node)) (.-clientHeight node)))
               reordering-col-node (atom nil)
               
               mousedown (fn [e]
                           (reset! mousedown? true)
                           (reset! mouse-x (.-pageX e))
                           (reset! x-at-mousedown (.-pageX e))
                           (when (and (part-of-drag-handle? (.-target e))
                                      (> (count @products-order&) 1)) ; only able to reorder if more than one product
                             ;; Reordering
                             (when-let [col (.closest (.-target e) ".column")] ; is the mousedown even on/in a column?
                               (let [col-left (js/parseInt (re-find #"\d+" (.-transform (.-style col))))]
                                 (reset! reordering-col-node col)
                                 (reset! curr-reordering-pos-x col-left)
                                 (reset! drag-direction-intention nil)
                                 (reset! drag-handle-offset (- (+ (- (.-pageX e)
                                                                     (.-offsetLeft node))
                                                                  (.-scrollLeft node))
                                                               col-left))
                                 ;; remember the id of the product we are currently reordering
                                 (reset! reordering-product (js/parseInt (.getAttribute col "data-product-id")))
                                 (.add (.-classList @reordering-col-node) "reordering"))))
                           ;; Scrolling
                           (when-not (part-of-scrollbar? (.-pageY e))
                             (do (reset! drag-scrolling? true)
                                 (.add (.-classList node) "dragging")))
                           (reset! last-mouse-x (.-pageX e)))
               ;; based on the (physical) position of the column being dragged,
               ;; update the sort pos if needed
               update-sort-pos (fn []
                                 (let [old-index (.indexOf @products-order& @reordering-product)
                                       new-index (-> @curr-reordering-pos-x
                                                     (/ col-width) ; width of column
                                                     (+ 0.5) ; to cause swap to occur when middle of a col is passed
                                                     Math/floor
                                                     (max 0) ; clamp between 0 and max index
                                                     (min (dec (count @products-order&))))]
                                   (when (not= old-index new-index)
                                     (rf/dispatch [:b/set-round-products-order
                                                   (assoc @products-order&
                                                          old-index (@products-order& new-index)
                                                          new-index @reordering-product)]))))
               mousemove (fn [e]
                           (reset! mouse-x (.-pageX e))
                           (when @mousedown?
                             ;; seems to prevent cell text selection when scrolling
                             (.preventDefault e)
                             ;; if you drag more than 3px, disable the cell & product name clickability
                             (when (and (not @cell-click-disabled?)
                                        (> (Math/abs (- @mouse-x @x-at-mousedown)) 3))
                               (reset! cell-click-disabled? true))
                             ;; useful for determining if right or left edge scrolling is needed
                             (reset! last-mouse-delta (- @mouse-x @last-mouse-x))
                             (when-not (zero? @last-mouse-delta)
                               (if (pos? @last-mouse-delta)
                                 (reset! drag-direction-intention "right")
                                 (reset! drag-direction-intention "left")))
                             ;; scrolling
                             (when-not @reordering-product
                               (let [neg-disp (* -1 (- (.-pageX e) @last-mouse-x))]
                                 (swap! scroll-x + neg-disp)
                                 (reset! scroll-v (* neg-disp scroll-a-factor))))
                             (reset! last-mouse-x @mouse-x)))
               
               mouseup (fn [e]
                         (reset! mouse-x (.-pageX e))
                         (when @mousedown?
                           (reset! mousedown? false)
                           (reset! last-mouse-delta 0)
                           (when @reordering-product
                             (do (.remove (.-classList @reordering-col-node) "reordering")
                                 (reset! reordering-product nil)
                                 (rf/dispatch [:b/store-round-products-order round-id])))
                           (reset! drag-scrolling? false)
                           (.remove (.-classList node) "dragging")))

               last-scroll-x (atom 0)
               scroll (fn [e]
                        (when-not @drag-scrolling?
                          (when (> (Math/abs (- (.-scrollLeft node) @scroll-x)) 0.99999)
                            (reset! scroll-v 0)
                            (reset! scroll-x (.-scrollLeft node)))))

               _ (reset! component-exists? true)
               anim-loop-fn (fn anim-loop ; TODO make sure this isn't being created multiple times without being destroyed
                              [timestamp]
                              ;; override scroll velocity if reordering
                              (when (and @reordering-product
                                         (= @drag-direction-intention "right")
                                         (not (neg? @last-mouse-delta))
                                         (> (- @mouse-x
                                               (.-offsetLeft node))
                                            (- (.-clientWidth node)
                                               col-width)))
                                (reset! scroll-v scroll-speed-reordering))
                              (when (and @reordering-product
                                         (= @drag-direction-intention "left")
                                         (not (pos? @last-mouse-delta))
                                         (< (- @mouse-x
                                               (.-offsetLeft node))
                                            col-width))
                                (reset! scroll-v (* -1 scroll-speed-reordering)))
                              ;; apply scroll velocity to scroll position
                              (swap! scroll-x + @scroll-v)
                              ;; right-side boundary
                              (when (> @scroll-x scroll-x-max)
                                (reset! scroll-v 0)
                                (reset! scroll-x scroll-x-max))
                              ;; left-side boundary
                              (when (< @scroll-x 0)
                                (reset! scroll-v 0)
                                (reset! scroll-x 0))
                              ;; apply position updates
                              (when @drag-scrolling?
                                (aset node "scrollLeft" (Math/floor @scroll-x)))
                              (when @reordering-product
                                (reset! curr-reordering-pos-x (- (+ (- @mouse-x
                                                                       (.-offsetLeft node))
                                                                    (.-scrollLeft node))
                                                                 @drag-handle-offset))
                                (update-sort-pos))
                              ;; apply friction
                              (when-not @reordering-product
                                (swap! scroll-v * @scroll-k))
                              ;; zero out weak velocity
                              (if (< (Math/abs @scroll-v) 0.000001)
                                (reset! scroll-v 0))

                              (when @component-exists?
                                (js/requestAnimationFrame anim-loop)))
               _ (js/requestAnimationFrame anim-loop-fn)]
           (.addEventListener node "mousedown" mousedown)
           (.addEventListener node "mousemove" mousemove)
           (.addEventListener node "mouseup" mouseup)
           (.addEventListener node "mouseleave" mouseup)
           (.addEventListener node "scroll" scroll)
           (.addEventListener js/window "scroll" window-scroll)
           (update-draggability this)))

       :component-did-update
       (fn [this]
         (update-draggability this))

       :component-will-unmount
       (fn [this]
         (reset! component-exists? false)
         (when @window-scroll-fn-ref
           (.removeEventListener js/window "scroll" @window-scroll-fn-ref)))})))

(defn c-explainer-modal
  [modal-showing?&]
  [:> ui/Modal {:open @modal-showing?&
                :on-close #(reset! modal-showing?& false)
                :dimmer "inverted"
                :closeOnDimmerClick true
                :closeOnEscape true
                :closeIcon true} 
   [:> ui/ModalHeader "How VetdRounds Work"]
   [:> ui/ModalContent
    [:div.explainer-section
     [:h3 "Keep Track"]
     [:div.explainer-item
      [:h4 "Approve or Disapprove every response "
       [:> ui/Icon {:name "thumbs up outline"}]
       [:> ui/Icon {:name "thumbs down outline"}]]
      "Approve or Disapprove the responses you receive to keep track of which products best meet your needs."]]
    [:div.explainer-section
     [:h3.teal "Learn More"]
     [:div.explainer-item
      [:h4 
       "Add a Topic "
       [:> ui/Icon {:name "plus"}]]
      "Request that all vendors respond to a new requirement / use case."]
     [:div.explainer-item
      [:h4 
       "Add a Product "
       [:> ui/Icon {:name "plus"}]]
      "Add specific products to your VetdRound, and we'll get them to respond to your topics."]
     [:div.explainer-item
      [:h4 "Ask Questions "
       [:> ui/Icon {:name "chat outline"}]]
      "Ask vendors follow-up questions about their responses."]]
    [:div.explainer-section
     [:h3.blue "Make a Decision"]
     [:div.explainer-item
      [:h4 
       "Disqualify Products "
       [:> ui/Icon {:name "ban"}]]
      "Mark products as unsatisfactory and hide them on the grid. Don't worry, you can change your mind later!"]
     [:div.explainer-item
      [:h4 
       "Set Up a Call "
       [:> ui/Icon {:name "call"}]]
      "(Optional) Have Vetd set up a phone call with your top choices."]
     [:div.explainer-item
      [:h4 
       "Declare a Winner "
       [:> ui/Icon {:name "check"}]]
      "Let the vendor know that you have made a final decision."]]]])

(defn c-round
  "Component to display Round details."
  [round req-form-template round-product]
  (fn [{:keys [id status title products] :as round}
       req-form-template
       round-product]
    [:<>
     [:> ui/Segment {:id "round-title-container"
                     :class (str "detail-container " (when (> (count title) 50) "long"))}
      [:h1.round-title title]
      [bc/c-round-status status]]
     (condp contains? status
       #{"initiation"} [:> ui/Segment {:class "detail-container"
                                       :style {:margin-left 20}}
                        [c-round-initiation round]]
       #{"in-progress"
         "complete"} [c-round-grid round req-form-template round-product])]))

(defn c-add-requirement-button
  [{:keys [id] :as round}]
  (let [popup-open? (r/atom false)
        get-requirements-class-list #(-> (.getElementsByClassName js/document "requirements")
                                         array-seq
                                         first
                                         .-classList)]
    (fn []
      [:> ui/Popup
       {:position "top left"
        :on "click"
        :open @popup-open?
        :onOpen #(reset! popup-open? true)
        :onClose #(reset! popup-open? false)
        :content (r/as-element
                  (let [new-requirement (atom "")
                        requirements-options (r/atom (get-requirements-options))]
                    [:> ui/Form {:style {:width 350}}
                     [:> ui/Dropdown {:style {:width "100%"}
                                      :options @requirements-options
                                      :placeholder "Enter topic..."
                                      :search true
                                      :selection true
                                      :multiple false
                                      :selectOnBlur false
                                      :allowAdditions true
                                      :additionLabel "Hit 'Enter' to Add "
                                      :noResultsMessage "Type to add a new topic..."
                                      :onChange (fn [_ this]
                                                  (reset! popup-open? false)
                                                  (rf/dispatch
                                                   [:b/round.add-requirement id (.-value this)]))}]]))
        :trigger (r/as-element
                  [:> ui/Button {:color "teal"
                                 :fluid true
                                 :icon true
                                 :labelPosition "left"
                                 :on-mouse-over #(when-not @popup-open?
                                                   (.add (get-requirements-class-list) "highlight-requirements"))
                                 :on-mouse-leave #(.remove (get-requirements-class-list) "highlight-requirements")}
                   "Add Topics"
                   [:> ui/Icon {:name "plus"}]])}])))

(defn c-add-product-form
  [round-id popup-open?&]
  (let [value& (atom [])
        ;; holds options from current search results + any currently selected values
        options& (r/atom [])
        search-query& (r/atom "")
        products->options (fn [products]
                            (for [{:keys [id pname]} products]
                              {:key id
                               :text pname
                               :value id}))]
    (fn [round-id popup-open?&]
      (let [products& (rf/subscribe
                       [:gql/q
                        {:queries
                         [[:products {:_where {:pname {:_ilike (str "%" @search-query& "%")}}
                                      :_limit 50
                                      :_order_by {:pname :asc}}
                           [:id :pname]]]}])
            _ (when-not (= :loading @products&)
                (let [options (distinct
                               (concat (-> @products& :products products->options) ; new options
                                       ;; keep options of any selected values
                                       ;; (this dumbly actually keeps everything, but that seems fine)
                                       @options&))]
                  (when-not (= @options& options)
                    (reset! options& options))))]
        [:> ui/Form {:style {:width 550
                             :display "flex"}}
         [:> ui/Dropdown {:style {:flex 1
                                  :border-top-right-radius 0
                                  :border-bottom-right-radius 0}
                          :loading (= :loading @products&)
                          :options @options&
                          :placeholder "Search products..."
                          :search true
                          :selection true
                          :multiple true
                          :selectOnBlur false
                          :selectOnNavigation true
                          :allowAdditions true
                          :additionLabel "Hit 'Enter' to Add "
                          :onAddItem (fn [_ this]
                                       (->> this
                                            .-value
                                            vector
                                            ui/as-dropdown-options
                                            (swap! options& concat)))
                          :onSearchChange (fn [_ this] (reset! search-query& (.-searchQuery this)))
                          :onChange (fn [_ this] (reset! value& (.-value this)))}]
         [:> ui/Button
          {:style {:border-top-left-radius 0
                   :border-bottom-left-radius 0}
           :color "blue"
           :disabled (empty? @value&)
           :on-click #(do (reset! popup-open?& false)
                          (rf/dispatch [:b/round.add-product round-id (js->clj @value&)]))}
          "Add"]]))))

(defn c-add-product-button
  [{:keys [id] :as round}]
  (let [popup-open? (r/atom false)
        get-round-grid-class-list #(-> (.getElementsByClassName js/document "round-grid")
                                       array-seq
                                       first
                                       .-classList)]
    (fn []
      [:> ui/Popup
       {:position "bottom left"
        :on "click"
        :open @popup-open?
        :onOpen #(reset! popup-open? true)
        :onClose #(reset! popup-open? false)
        :hideOnScroll false
        :flowing true
        :content (r/as-element [c-add-product-form id popup-open?])
        :trigger (r/as-element
                  [:> ui/Button {:color "blue"
                                 :fluid true
                                 :icon true
                                 :labelPosition "left"
                                 :on-mouse-over #(when-not @popup-open?
                                                   (.add (get-round-grid-class-list) "highlight-products"))
                                 :on-mouse-leave #(.remove (get-round-grid-class-list) "highlight-products")}
                   "Add Products"
                   [:> ui/Icon {:name "plus"}]])}])))

(defn c-requirements
  [{:keys [prompts] :as req-form-template}]
  [:div.requirements
   (for [req prompts
         :let [{req-prompt-id :id
                req-prompt-text :prompt} req]]
     ^{:key (str req-prompt-id)}
     [:> ui/Segment {:class "requirement"}
      req-prompt-text])])

(defn sort-round-products
  [round-product]
  (sort-by (juxt #(- 1 (or (:result %) 0.5))
                 #(:sort % 1)
                 (comp :pname :product))
           compare round-product))

(defn c-page []
  (let [org-id& (rf/subscribe [:org-id])
        round-idstr& (rf/subscribe [:round-idstr])
        rounds& (rf/subscribe [:gql/sub
                               {:queries
                                [[:rounds {:idstr @round-idstr&
                                           :deleted nil}
                                  [:id :idstr :created :status :title
                                   ;; requirements form template
                                   [:req-form-template
                                    [:id
                                     [:prompts {:ref-deleted nil
                                                :_order_by {:sort :asc}}
                                      [:id :idstr :prompt :descr :sort]]]]
                                   ;; round initiation form response
                                   [:init-doc
                                    [:id
                                     [:response-prompts {:ref-deleted nil}
                                      [:id :prompt-id :prompt-prompt :prompt-term
                                       [:response-prompt-fields
                                        [:id :prompt-field-fname :idx
                                         :sval :nval :dval]]]]]]
                                   ;; requirements responses from vendors
                                   [:round-product {:deleted nil
                                                    :_order_by {:sort :asc}}
                                    [:id :result :reason :sort
                                     [:product
                                      [:id :idstr :pname
                                       [:docs {:dtype "preposal" ; completed preposals
                                               :to-org-id @org-id&}
                                        [:id :idstr]]
                                       [:vendor
                                        [:id :oname]]]]
                                     [:vendor-response-form-docs
                                      [:id :title :doc-id :doc-title
                                       :ftype :fsubtype
                                       [:doc-from-org [:id :oname]]
                                       [:doc-to-org [:id :oname]]
                                       [:response-prompts {:ref-deleted nil}
                                        [:id :prompt-id :prompt-prompt :prompt-term
                                         [:response-prompt-fields
                                          [:id :prompt-field-fname :idx :resp-id
                                           :sval :nval :dval]]
                                         [:subject-of-response-prompt
                                          {:deleted nil
                                           :prompt-term "round.response/rating"}
                                          [[:response-prompt-fields
                                            {:deleted nil}
                                            [:nval]]]]]]]]]]]]]}])
        modal-showing?& (r/atom false)]
    (fn []
      [:div.container-with-sidebar.round-details
       (if (= :loading @rounds&)
         [cc/c-loader]
         (let [{:keys [status req-form-template round-product] :as round} (-> @rounds& :rounds first)
               sorted-round-products (sort-round-products round-product)]
           [:<> ; sidebar margins (and detail container margins) are customized on this page
            [:div.sidebar {:style {:margin-right 0}}
             [:div {:style {:padding "0 15px"}}
              [bc/c-back-button {:on-click #(rf/dispatch [:b/nav-rounds])}
               "All VetdRounds"]]
             (when (and (#{"in-progress" "complete"} status)
                        (seq sorted-round-products))
               [:<>
                (let [has-a-winner? (some (comp (partial = 1) :result) sorted-round-products)]
                  (if has-a-winner?
                    [:div {:style {:height 161}}] ; spacer
                    [:<>
                     [:> ui/Segment {:style {:position "sticky"
                                             :top 0
                                             :z-index 5}}
                      [c-add-requirement-button round]
                      [c-add-product-button round]]
                     [:div {:style {:text-align "center"}}
                      [:a {:on-click #(reset! modal-showing?& true)}
                       [:> ui/Icon {:name "question circle"}]
                       "How VetdRounds Work"]]
                     [c-explainer-modal modal-showing?&]]))
                [:div {:style {:height 61}}] ; spacer
                [c-requirements req-form-template]])]
            [:div.inner-container [c-round round req-form-template sorted-round-products]]]))])))

