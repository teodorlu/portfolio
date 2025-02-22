(ns portfolio.ui.actions
  (:require [clojure.walk :as walk]
            [portfolio.ui.collection :as collection]
            [portfolio.ui.css :as css]
            [portfolio.ui.document :as document]
            [portfolio.ui.layout :as layout]
            [portfolio.ui.routes :as routes]
            [portfolio.ui.scene :as scene]
            [portfolio.ui.scene-browser :as scene-browser]
            [portfolio.ui.search.protocols :as search]))

(defn assoc-in*
  "Takes a map and pairs of path value to assoc-in to the map. Makes `assoc-in`
  work like `assoc`, e.g.:

  ```clj
  (assoc-in* {}
             [:person :name] \"Christian\"
             [:person :language] \"Clojure\")
  ;;=>
  {:person {:name \"Christian\"
            :language \"Clojure\"}}
  ```"
  [m & args]
  (assert (= 0 (mod (count args) 2)) "assoc-in* takes a map and pairs of path value")
  (assert (->> args (partition 2) (map first) (every? vector?)) "each path should be a vector")
  (->> (partition 2 args)
       (reduce (fn [m [path v]]
                 (assoc-in m path v)) m)))

(defn dissoc-in*
  "Takes a map and paths to dissoc from it. An example explains it best:

  ```clj
  (dissoc-in* {:person {:name \"Christian\"
                        :language \"Clojure\"}}
              [:person :language])
  ;;=>
  {:person {:name \"Christian\"}}
  ```

  Optionally pass additional paths.
  "
  [m & args]
  (reduce (fn [m path]
            (cond
              (= 0 (count path)) m
              (= 1 (count path)) (dissoc m (first path))
              :else (let [[k & ks] (reverse path)]
                      (update-in m (reverse ks) dissoc k))))
          m args))

(defn atom? [x]
  (satisfies? cljs.core/IWatchable x))

(defn get-page-title [state {:keys [selection document]}]
  (let [suffix (when (:title state) (str " - " (:title state)))]
    (cond
      (:target selection)
      (case (:kind selection)
        :scene (str "Scene: " (:title (:target selection)) suffix)
        :collection (str "Collection: " (:title (:target selection)) suffix))

      document
      (:title document)

      :else
      (str "No scenes found" suffix))))

(defn go-to-location [state location]
  (let [id (routes/get-id (:location state))
        current-scenes (collection/get-selected-scenes state id)
        selection (collection/get-selection state (routes/get-id location))
        layout (layout/get-view-layout state selection)
        lp (layout/get-layout-path layout)
        expansions (->> (:path selection)
                        (map scene-browser/get-expanded-path)
                        (remove #(get-in state %))
                        (mapcat (fn [path] [path true])))
        document (when (nil? id)
                   (document/get-document (routes/get-document-id location)))]
    {:assoc-in (cond-> [[:location] location
                        (layout/get-current-layout-path) lp]
                 (nil? (get-in state lp)) (into [lp layout])
                 (seq expansions) (into expansions))
     :fns (concat
           (->> (filter :on-unmount current-scenes)
                (map (fn [{:keys [on-unmount params id title]}]
                       [:on-unmount (or id title) on-unmount params])))
           (->> (filter :on-mount (:scenes selection))
                (map (fn [{:keys [on-mount params id title]}]
                       [:on-mount (or id title) on-mount params]))))
     :release (mapcat scene/get-scene-atoms current-scenes)
     :subscribe (mapcat scene/get-scene-atoms (:scenes selection))
     :set-page-title (get-page-title state {:selection selection :document document})
     :update-window-location (routes/get-url location)}))

(defn remove-scene-param
  ([state scene-id]
   (let [param (get-in state [:scenes scene-id :param])]
     (cond
       (map? param)
       {:actions [[:dissoc-in [:ui scene-id :overrides]]]}

       (atom? param)
       {:reset [param (get-in state [:ui scene-id :original])]
        :actions [[:dissoc-in [:ui scene-id :overrides]]
                  [:dissoc-in [:ui scene-id :original]]]})))
  ([state scene-id k]
   (let [param (get-in state [:scenes scene-id :param])]
     (cond
       (map? param)
       {:actions [[:dissoc-in [:ui scene-id :overrides k]]]}

       (atom? param)
       {:swap [param [k] (get-in state [:scenes scene-id :original k])]
        :actions [[:dissoc-in [:ui scene-id :overrides k]]
                  [:dissoc-in [:ui scene-id :original k]]]}))))

(defn set-scene-param
  ([state scene-id v]
   (let [param (get-in state [:scenes scene-id :param])]
     (cond
       (map? param)
       {:actions [[:assoc-in [:ui scene-id :overrides] v]]}

       (atom? param)
       {:reset [param v]
        :actions [[:assoc-in [:ui scene-id :overrides] v]
                  [:assoc-in [:ui scene-id :original] @param]]})))
  ([state scene-id k v]
   (let [param (get-in state [:scenes scene-id :param])]
     (cond
       (map? param)
       {:actions [[:assoc-in [:ui scene-id :overrides k] v]]}

       (atom? param)
       {:swap [param [k] v]
        :actions (cond-> [[:assoc-in [:ui scene-id :overrides k] v]]
                   (not (get-in state [:ui scene-id :original k]))
                   (into [[:assoc-in [:ui scene-id :original k] (k @param)]]))}))))

(defn search [{:keys [index]} q]
  (when index
    {:assoc-in [[:search/suggestions] (search/query index q)]}))

(declare execute-action!)

(defn process-action-result! [app res]
  (let [log (if (:log? @app)
              println
              (fn [& _args]))]
    (doseq [ref (:release res)]
      (log "Stop watching atom" (pr-str ref))
      (remove-watch ref ::portfolio))
    (doseq [[k t f & args] (:fns res)]
      (try
        (log (str "Calling " k " on " t " with") (pr-str args))
        (apply f args)
        (catch :default e
          (execute-action!
           app
           [:assoc-in [:error]
            {:exception e
             :cause (str k " on " (name t) " threw exception")
             :data [(when (seq args)
                      {:label "arguments"
                       :data args})]}]))))
    (doseq [ref (:subscribe res)]
      (log "Start watching atom" (pr-str ref))
      (add-watch ref ::portfolio
        (fn [_ _ _ _]
          (swap! app update :heartbeat (fnil inc 0)))))
    (when-let [url (:update-window-location res)]
      (when-not (= url (routes/get-current-url))
        (log "Updating browser URL to" url)
        (.pushState js/history false false url))
      (js/requestAnimationFrame
       #(when-let [el (some-> js/location.hash (subs 1) js/document.getElementById)]
          (.scrollIntoView el))))
    (when-let [title (:set-page-title res)]
      (log (str "Set page title to '" title "'"))
      (set! js/document.title title))
    (when (or (:dissoc-in res) (:assoc-in res))
      (when (:assoc-in res)
        (log ":assoc-in" (pr-str (:assoc-in res))))
      (when (:dissoc-in res)
        (log ":dissoc-in" (pr-str (:dissoc-in res))))
      (swap! app (fn [state]
                   (apply assoc-in*
                          (apply dissoc-in* state (:dissoc-in res))
                          (:assoc-in res)))))
    (doseq [action (:actions res)]
      (execute-action! app action))
    (when-let [[ref path v] (:swap res)]
      (swap! ref assoc-in path v))
    (when-let [[ref v] (:reset res)]
      (reset! ref v))
    (when-let [paths (:load-css-files res)]
      (css/load-css-files paths))
    (when-let [paths (:replace-css-files res)]
      (css/replace-loaded-css-files paths))))

(defn save-in-local-storage [k v]
  (.setItem js/localStorage (str k) (pr-str v)))

(defn execute-action! [app action]
  (when (:log? @app)
    (println "execute-action!" action))
  (process-action-result!
   app
   (case (first action)
     :assoc-in {:assoc-in (rest action)}
     :dissoc-in {:dissoc-in (rest action)}
     :fn/call (let [[fn & args] (rest action)] (apply fn args))
     :go-to-location (apply go-to-location @app (rest action))
     :go-to-current-location (go-to-location @app (routes/get-current-location))
     :set-css-files (let [[paths] (rest action)]
                      {:assoc-in [[:css-paths] paths]
                       :load-css-files paths
                       :replace-css-files paths})
     :remove-scene-param (apply remove-scene-param @app (rest action))
     :save-in-local-storage (apply save-in-local-storage (rest action))
     :set-scene-param (apply set-scene-param @app (rest action))
     :search (apply search @app (rest action))))
  app)

(def available-actions
  #{:assoc-in :dissoc-in :go-to-location :go-to-current-location
    :remove-scene-param :set-scene-param :fn/call :event/prevent-default
    :search :save-in-local-storage})

(defn actions? [x]
  (and (sequential? x)
       (seq x)
       (every? #(and (sequential? %)
                     (contains? available-actions (first %))) x)))

(defn parse-int [s]
  (let [n (js/parseInt s 10)]
    (if (not= n n)
      ;; NaN!
      0
      n)))

(defn get-action [message]
  (try
    (let [msg (js->clj message :keywordize-keys true)]
      (when (:action msg)
        (let [action (keyword (:action msg))]
          (into
           [action]
           (cond
             (= :assoc-in action)
             (let [[path v] (js->clj (js/JSON.parse (:data msg)) :keywordize-keys true)]
               [(->> path
                     (mapv #(cond-> %
                              (string? %) keyword)))
                v]))))))
    (catch :default _e
      nil)))

(defn actionize-data
  "Given a Portfolio `app` instance and some prepared data to render, wrap
  collections of actions in a function that executes these actions. Using this
  function makes it possible to prepare event handlers as a sequence of action
  tuples, and have them seemlessly emitted as actions in the components.

  If you need to access the `.-value` of the event target (e.g. for on-change on
  input fields, etc), use `:event.target/value` as a placeholder in your action,
  and it will be replaced with the value."
  [app data]
  (walk/prewalk
   (fn [x]
     (if (actions? x)
       (fn [e & [data]]
         (when (->> (tree-seq coll? identity x)
                    (filter #{[:event/prevent-default]})
                    seq)
           (.preventDefault e)
           (.stopPropagation e))
         (doseq [action (remove #{[:event/prevent-default]} x)]
           (execute-action!
            app
            (walk/prewalk
             (fn [ax]
               (cond
                 (get data ax)
                 (get data ax)

                 (= :event.target/value ax)
                 (some-> e .-target .-value)

                 (= :event.target/number-value ax)
                 (some-> e .-target .-value parse-int)

                 :else ax))
             action))))
       x))
   data))

(defn dispatch [actions e & [data]]
  ;; Dispatch asynchronously to avoid triggering a render within an ongoing
  ;; render.
  (js/requestAnimationFrame #(actions e data)))
