(ns galt-promo.ui
  (:require [clojure.string :as str]
            [galt-promo.events :as events]
            [galt-promo.globe :as globe]
            [re-frame.core :as rf]
            [reagent.core :as r]
            [reagent.dom :as dom]))

(defonce event-source (atom nil))
(defonce browser-location (atom nil))
(defonce location-requested? (atom false))
(defonce globe-controller (atom nil))
(defonce latest-users (atom []))

(declare request-location! globe-component)

(defn- globe-node [node]
  (if node
    (when-not @globe-controller
      (reset! globe-controller
              (globe/mount! node @latest-users
                            #(rf/dispatch [::events/cluster-selected %]))))
    (when-let [controller @globe-controller]
      (globe/destroy! controller)
      (reset! globe-controller nil))))

(def browser-icons
  {"Chrome" "🌐"
   "Edge" "🌊"
   "Firefox" "🦊"
   "Safari" "🧭"
   "Other" "🌐"})

(defonce name-editor (r/atom nil))

(defn- start-name-edit! [{:keys [id name]}]
  (reset! name-editor {:id id :draft name}))

(defn- cancel-name-edit! []
  (reset! name-editor nil))

(defn- save-name-edit! []
  (let [name (str/trim (:draft @name-editor))]
    (when-not (str/blank? name)
      (events/save-name! name cancel-name-edit!))))

(defn- user-row [{:keys [id name browser ip connected? self?] :as user}]
  [:tr {:key id}
   (if (and self? (= id (:id @name-editor)))
     [:td [:div.field.has-addons
           [:div.control.is-expanded
            [:input.input.is-small
             {:type "text"
              :maxLength 40
              :aria-label "Your user name"
              :ref (fn [node] (when node (.focus node)))
              :value (:draft @name-editor)
              :on-change #(swap! name-editor assoc :draft (.. % -target -value))
              :on-key-down (fn [event]
                             (case (.-key event)
                               "Enter" (do
                                         (.preventDefault event)
                                         (save-name-edit!))
                               "Escape" (do
                                          (.preventDefault event)
                                          (cancel-name-edit!))
                               nil))}]]
           [:div.control
            [:button.button.is-small.is-primary
             {:type "button"
              :aria-label "Save name"
              :title "Save name"
              :on-click save-name-edit!}
             [:i.fa-solid.fa-floppy-disk {:aria-hidden true}]]]]]
     [:td (if self?
            [:a {:href "#"
                 :title "Click to change your name"
                 :on-click (fn [event]
                             (.preventDefault event)
                             (start-name-edit! user))}
             name]
            name)])
   [:td
    [:span.tag.is-light
     [:span.mr-1 {:aria-hidden true} (get browser-icons browser "🌐")]
     [:span browser]]]
   [:td [:span.tag.is-info.is-light ip]]
   [:td
    [:span.status-dot
     {:class (cond self? "is-self"
                   connected? "is-connected"
                   :else "is-disconnected")
      :role "img"
      :aria-label (cond self? "You" connected? "Connected" :else "Disconnected")
      :title (cond self? "You" connected? "Connected" :else "Disconnected")}]]])

(defn- tab-icon [kind]
  (case kind
    :list
    [:svg {:width 16 :height 16 :viewBox "0 0 24 24"
           :fill "none" :stroke "currentColor" :stroke-width 2
           :stroke-linecap "round" :aria-hidden true}
     [:path {:d "M4 6h16M4 12h16M4 18h16"}]]
    :map
    [:svg {:width 16 :height 16 :viewBox "0 0 24 24"
           :fill "currentColor" :aria-hidden true}
     [:path {:d "M12 2a7 7 0 0 0-7 7c0 5 7 13 7 13s7-8 7-13a7 7 0 0 0-7-7zm0 9.5a2.5 2.5 0 1 1 0-5 2.5 2.5 0 0 1 0 5z"}]]
    :info
    [:svg {:width 16 :height 16 :viewBox "0 0 24 24"
           :fill "none" :stroke "currentColor" :stroke-width 2
           :stroke-linecap "round" :stroke-linejoin "round" :aria-hidden true}
     [:circle {:cx 12 :cy 12 :r 10}]
     [:path {:d "M12 16v-4M12 8h.01"}]]))

(defn- select-tab! [tab]
  (rf/dispatch [::events/tab-selected tab])
  (set! (.. js/location -hash) (name tab))
  (when (= :map tab)
    (request-location!)))

(defn- tab-item [active? tab label icon]
  [:li {:class (when active? "is-active")}
   [:a {:href (str "#" (name tab))
        :aria-current (when active? "page")
        :on-click (fn [event]
                    (.preventDefault event)
                    (select-tab! tab))}
    [:span.icon.is-small (tab-icon icon)]
    [:span label]]])

(defn- selected-cluster-panel [users selected]
  (when (seq selected)
    (let [selected (set selected)
          members (filterv #(selected (:id %)) users)]
      (when (seq members)
        [:div.box.cluster-panel
         [:div.is-flex.is-justify-content-space-between.is-align-items-center.mb-3
          [:h2.subtitle.is-6.mb-0 (str (count members) " users here")]
          [:button.delete {:type "button"
                           :aria-label "Close cluster"
                           :on-click #(do
                                        (rf/dispatch [::events/cluster-closed])
                                        (cancel-name-edit!))}]]
         [:div.table-container
          [:table.table.is-fullwidth.is-narrow
           [:thead [:tr [:th "Name"] [:th "Browser"] [:th "IP"] [:th "Status"]]]
           [:tbody (map user-row members)]]]]))))

(defn- globe-view []
  [globe-component @(rf/subscribe [::events/users])])

(defonce globe-component
  (r/create-class
   {:display-name "GlobeView"
    :component-did-update
    (fn [this _]
      (when-let [controller @globe-controller]
        (globe/set-users! controller (second (r/argv this)))))
    :reagent-render
    (fn [users]
      (reset! latest-users users)
      [:div.globe-container {:ref globe-node}])}))

(defn- users-view [users]
  (if (seq users)
    [:div.table-container
     [:table.table.is-fullwidth.is-hoverable
      [:thead [:tr [:th "Name"] [:th "Browser"] [:th "IP"] [:th "Status"]]]
      [:tbody (map user-row users)]]]
    [:div.notification.is-light "Waiting for users…"]))

(def ^:private info-sections
  [{:title "Connect"
    :image "/images/connect.png"
    :alt "People from around the world meeting and connecting"
    :body "Find other freedom minded people all over the world. Connect with them, exchange information and find ways to live a better, freer and more prosperous life. You can join or create events to bring the people together to share and learn."}
   {:title "Learn"
    :image "/images/learn.png"
    :alt "People learning together about sound money and libertarian ideas"
    :body "GALT allows sharing and creating attractive content to make these less known but important ideas as sound money, NAP (non-aggression principle), Austrian school of economics and libertarianism easy to understand and communicate. Also it serves to share analysis of the current events instead of the left-right axis propagated in the mass-media to take a libertarian perspective to find solutions of the problems we face daily - censorship, inflation, loss of privacy, self sovereignty, natural rights, private property, etc."}
   {:title "Use"
    :image "/images/use.png"
    :alt "Open source tools for productivity, privacy and growing wealth"
    :body "GALT is also a set of open source tools to give your ideas more reach, to make your work more productive and your wealth to grow and stay with you instead of with criminals (public and private)."}
   {:title "Contribute"
    :image "/images/contribute.png"
    :alt "Contributors working together on open source tools and funding"
    :body [:<> "Galt is open source, available on "
           [:a {:href "https://github.com/galt-network"
                :target "_blank"
                :rel "noopener"} "github.com/galt-network"]
           " and already contains tools like deBS (de-bullshit) for refuting collectivist claims in social media. You can implement things yourself or contribute information or funds for someone else to do it."]}])

(defn- info-section [{:keys [title image alt body]} reversed?]
  [:div.box
   [:div.columns.is-vcentered
    {:class (when reversed? "is-flex-direction-row-reverse")}
    [:div.column.is-two-fifths
     [:figure.image.is-square
      [:img {:src image :alt alt}]]]
    [:div.column
     [:h3.title.is-4 title]
     [:p body]]]])

(defn- info-view []
  [:div
   [:div.content
    [:h2.is-header "GALT - Global Alliance for Libertarian Transformation"]
    [:p "Galt is a set of tools to help freedom minded people find each other and to communicate."]
    (map-indexed (fn [index section]
                   ^{:key (:title section)} [info-section section (odd? index)])
                 info-sections)]
   [:div.box.info-cta
    [:p.info-cta-text
     "Follow "
     [:a {:href "https://x.com/galt_network"
          :target "_blank"
          :rel "noopener"} "@galt_network"]
     " on X to find out more and get updates on the platform."]
    [:a.button.is-success.is-medium
     {:href "https://x.com/galt_network"
      :target "_blank"
      :rel "noopener"} "Follow @galt_network"]]])

(defn- app []
  (let [users @(rf/subscribe [::events/users])
        connection @(rf/subscribe [::events/connection])
        tab @(rf/subscribe [::events/tab])
        selected @(rf/subscribe [::events/selected-cluster])]
    [:div.app-shell
     [globe-view]
     [:h1.app-brand "GALT"]
     [:span.tag.connection-tag
      {:class (if (= :open connection) "is-success" "is-light")}
      (if (= :open connection) "Live" "Reconnecting")]
     [:nav.tabs.is-boxed.app-tabs {:aria-label "View"}
      [:ul
       [tab-item (= tab :list) :list "List" :list]
       [tab-item (= tab :map) :map "Map" :map]
       [tab-item (= tab :info) :info "Info" :info]]]
     (cond
       (= tab :map)
       [:<>
        [selected-cluster-panel users selected]
        [:p.map-help
         "Markers use precise browser location when permitted, otherwise IP-based location."]]

       (= tab :info)
       [:main.users-overlay [info-view]]

       :else
       [:main.users-overlay [users-view users]])]))

(defn- post-location! []
  (when-let [{:keys [lat lng]} @browser-location]
    (let [params (js/URLSearchParams.)]
      (.set params "lat" lat)
      (.set params "lng" lng)
      (-> (js/fetch (str "/location?" (.toString params)) #js {:method "POST"})
          (.catch (fn [_] nil))))))

(defn- request-location! []
  (when-not @location-requested?
    (reset! location-requested? true)
    (when-let [geolocation (.-geolocation js/navigator)]
      (.getCurrentPosition
       geolocation
       (fn [position]
         (let [coordinates (.-coords position)]
           (reset! browser-location {:lat (.-latitude coordinates)
                                     :lng (.-longitude coordinates)})
           (post-location!)))
       (fn [_] nil)
       #js {:enableHighAccuracy true
            :maximumAge 300000
            :timeout 10000}))))

(defn- connect! []
  (when-let [source @event-source]
    (.close source))
  (let [source (js/EventSource. "/connect")]
    (reset! event-source source)
    (set! (.-onopen source)
          #(do
             (rf/dispatch [::events/connection-changed :open])
             (post-location!)))
    (set! (.-onerror source) #(rf/dispatch [::events/connection-changed :reconnecting]))
    (.addEventListener source "users"
                       #(rf/dispatch [::events/users-received
                                      (js->clj (js/JSON.parse (.-data %)) :keywordize-keys true)]))))

(defn init []
  (rf/dispatch-sync [::events/initialize
                     (events/tab-from-hash (.. js/location -hash))])
  (dom/render [app] (.getElementById js/document "app"))
  (.addEventListener js/window "hashchange"
                     #(rf/dispatch [::events/tab-selected
                                    (events/tab-from-hash (.. js/location -hash))]))
  (connect!)
  (request-location!))
