(ns portfolio.ui.components.auto-complete
  (:require [dumdom.core :as d]
            [portfolio.ui.components.elastic-container :as ec]
            [portfolio.ui.icons :as icons]))

(defn render-icon [{:keys [icon size actions align]}]
  [:div {:on-click actions
         :style {:position "absolute"
                 (or align :left) 8
                 :top 0
                 :bottom 0
                 :display "flex"
                 :align-items "center"
                 :cursor (when actions "pointer")
                 :color "var(--folder-icon-color)"}}
   (icons/render-icon icon {:size size})])

(d/defcomponent Suggestions
  :will-enter (ec/enter)
  :will-leave (ec/leave)
  [{:keys [suggestions]}]
  [:nav.suggestions
   [:ol {:style {:padding 12}}
    (for [{:keys [illustration title actions]} suggestions]
      [:li {:on-click actions
            :style {:cursor "pointer"
                    :display "flex"
                    :align-items "center"
                    :gap 10
                    :padding 6}}
       (when (:icon illustration)
         (icons/render-icon (:icon illustration) {:size 16
                                                  :color (:color illustration)}))
       title])]])

(d/defcomponent AutoCompleter [{:keys [text placeholder icon action on-input suggestions]}]
  [:div.auto-completer
   {:style {:padding 8
            :transition "background 0.15s ease-in"
            :background (if (seq suggestions)
                          "var(--auto-complete-active-bg)"
                          "var(--folder-bg)")}}
   [:div {:style {:position "relative"}}
    (when icon
      (render-icon {:icon icon :size 24}))
    [:input
     {:style {:background "var(--input-inactive-bg)"
              :padding-top 12
              :padding-bottom 12
              :padding-left (if icon 40 12)
              :padding-right (if action 40 12)
              :border-radius 4
              :display "block"
              :width "100%"}
      :on-input on-input
      :value text
      :placeholder placeholder}]
    (when action
      (render-icon
       {:icon (:icon action)
        :size 16
        :actions (:actions action)
        :align :right}))]
   (when (seq suggestions)
     (Suggestions
      {:suggestions suggestions}))])