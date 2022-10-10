(ns portfolio.views.canvas.args
  (:require [portfolio.protocols :as portfolio]
            [portfolio.components.arguments-panel :refer [ArgumentsPanel]]
            [portfolio.core :as p]
            [clojure.string :as str]))

(def render-impl
  {`portfolio/render-view #'ArgumentsPanel})

(defn get-input-kind [scene k v]
  (or (get-in scene [:arg-defs k :input/kind])
      (cond
        (boolean? v)
        {:kind :boolean
         :value v
         :actions [[:set-scene-argument (:id scene) k (not v)]]}

        (number? v)
        {:kind :number
         :value v
         :actions [[:set-scene-argument (:id scene) k :event.target/number-value]]}

        :default
        {:kind :text
         :value v
         :actions [[:set-scene-argument (:id scene) k :event.target/value]]})))

(defn prepare-addon-content [panel state location scene]
  (when (:args scene)
    (with-meta
      {:args (let [args (p/get-scene-args state scene)
                   args (if (satisfies? cljs.core/IWatchable args) @args args)
                   overrides (p/get-scene-arg-overrides state scene)]
               (when (map? args)
                 (for [[k v] args]
                   (cond->
                       {:label (str/replace (str k) #"^:" "")
                        :value v
                        :input (get-input-kind scene k v)}
                     (= (k args) (k overrides))
                     (assoc :clear-actions [[:remove-scene-argument (:id scene) k]])))))}
      render-impl)))

(def data-impl
  {`portfolio/prepare-addon-content #'prepare-addon-content})

(defn create-args-panel [config]
  (with-meta
    {:id :canvas/args-panel
     :title "Arguments"}
    data-impl))
