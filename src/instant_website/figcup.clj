(ns instant-website.figcup
  (:require
    [clojure.string :as string]
    [clojure.tools.logging :refer [log]]))

;; Figcup was initially created by @roman01la and source comes from here:
;; https://github.com/roman01la/figcup/blob/master/src/figma/hiccup.clj
;; Distributed under Eclipse Public License

;; Just to make it easy to turn off logging in production, logging here outputs
;; A LOT OF STUFF, so be careful
(def llog (fn [a b]))
;; (def llog log)

(def width-map
  {"Thin" 100
   "ExtraLight" 200
   "Light" 300
   "Regular" 400
   "Medium" 500
   "SemiBold" 600
   "Bold" 700
   "ExtraBold" 800
   "Black" 900})

(def figma-width-map
  {"Thin" 100
   "Extra Light" 200
   "Light" 300
   "Regular" 400
   "Medium" 500
   "Semi Bold" 600
   "Bold" 700
   "Extra Bold" 800
   "Black" 900})

;; another attempt, trying to merge figcup and figcup2
;; works with serialized data from figma plugin (not the http api)

(def css-reset
  {:button {:border-radius 0
            :border        "none"}})

(defn round-to
  ([n]
   (round-to 100 n))
  ([precision n]
   (double (/ (Math/round (double (* precision n))) precision))))

(defn to-px [s]
  (str s "px"))

(defn clean-component-name [s]
  (-> (or s "")
      (string/replace " " "-")
      (string/lower-case)))

(defn figma-color->rgba [{:keys [r g b a]}
                         opacity]
  (if (and (nil? r) (nil? g) (nil? b))
    "white"
    (let [v (->> (mapv double [r g b])
                 (mapv #(Math/round (* 255 %))))
          v (conj v (round-to (or a opacity)))
          v (->> (interpose ", " v)
                 (apply str))]
      (str "rgba(" v ")"))))

(defn merge-css [effects]
  (->> (group-by first effects)
       (map (fn [[key values]]
              [key (->> (map second values)
                        (interpose ", ")
                        (apply str))]))
       (into {})))



(defmulti figma-effect->css (fn [_ v] (:type v)))

(defmethod figma-effect->css "INNER_SHADOW"
  [key {:keys [color offset radius opacity]}]
  (let [{:keys [x y]} offset]
    [key (str "inset " x "px " y "px " radius "px " (figma-color->rgba color opacity))]))

(defmethod figma-effect->css "DROP_SHADOW"
  [key {:keys [color offset radius opacity]}]
  (let [{:keys [x y]} offset]
    [key (str x "px " y "px " radius "px " (figma-color->rgba color opacity))]))



(defmulti figma-fill->css (fn [_ v] (:type v)))

(defmethod figma-fill->css "SOLID" [key {:keys [color opacity]}]
  [key (figma-color->rgba color opacity)])

(defmethod figma-fill->css "GRADIENT_LINEAR" [key {:keys [color opacity]}]
  [key (figma-color->rgba color opacity)])

(defmethod figma-fill->css "IMAGE" [key opts]
  ;; TODO host needs to be changed manually for now...
  (let [image-url (str "../_images/" (:imageHash opts) ".png")
        prefix-attrs "center / contain no-repeat"
        full-value (str prefix-attrs " url('" image-url "')")]
    [:background full-value]))

(defn -constraints->helper-attrs [{:keys [vertical horizontal]}]
  {:data-constraint-x horizontal
   :data-constraint-y vertical})

(defmulti -figma-styles->css first)

(defmethod -figma-styles->css :default [_]
  nil)

(defmethod -figma-styles->css :backgroundColor [[_ color opacity]]
  {:background-color (figma-color->rgba color opacity)})

(defmethod -figma-styles->css :opacity [[_ opacity]]
  {:opacity opacity})

(defmethod -figma-styles->css :absoluteBoundingBox [[_ {:keys [width height]}]]
  {:width  (to-px width)
   :height (to-px height)})

(defmethod -figma-styles->css :effects [[_ effects]]
  (->> (map #(figma-effect->css :box-shadow %) effects)
       merge-css))

(defmethod -figma-styles->css :fills [[_ fills]]
  ;; (println "called")
  ;; (llog :info "figma->style->css :filles")
  (->> (map (fn [fill]
              (llog :info "figma-styles->css :fills per fill")
              (if (:visible fill)
                (figma-fill->css :background-color fill)
                []))
            fills)
       merge-css))

(defmethod -figma-styles->css :rotation [[_ rotation]]
  {:transform (str "rotate(" rotation "deg)")})
   ;; :transform-origin "50% 50%"})
  ;; (->> (map #(figma-fill->css :background-color %) rotation)
  ;;      merge-css)

(defmethod -figma-styles->css :backgrounds [[_ backgrounds]]
  (->> (map (fn [background]
              (llog :info "figma-styles->css :backgrounds")
              (if (:visible background)
                (figma-fill->css :background-color background)
                []))
            backgrounds)
       merge-css))

(defmethod -figma-styles->css :strokeWeight [[_ weight]]
  {:border-width (to-px weight)})

(defmethod -figma-styles->css :strokes [[_ strokes]]
  (if-not (empty? strokes)
    (let [stroke (first strokes)
          stroke (-> stroke
                     (assoc-in [:color :a] (:opacity stroke)))]
      {:border-color (->> stroke
                          (figma-fill->css :color)
                          second)
       :border-style (clojure.string/lower-case (:type stroke))})
    {}))

(defmethod -figma-styles->css :cornerRadius [[_ radius]]
  ;; (println ":cornerRadius called")
  {:border-radius (to-px radius)})

(defmethod -figma-styles->css :width [[_ width]]
  (log :info ":width")
  (log :info width)
  (let [percentage? (clojure.string/includes? width "%")]
    {:width (if percentage?
              width
              (to-px width))}))

(defmethod -figma-styles->css :position [[_ position]]
  {:position position})

(defmethod -figma-styles->css :white-space [[_ white-space]]
  {:white-space white-space})

(defmethod -figma-styles->css :height [[_ height]]
  {:height (to-px height)})

(defmethod -figma-styles->css :y [[_ y]]
  {:top (to-px y)})

(defmethod -figma-styles->css :x [[_ x]]
  {:left (to-px x)})

(defmethod -figma-styles->css :font-type-style
  [[_ {:keys [fontFamily
              italic
              fontWeight
              fontSize
              textAlignHorizontal
              textAlignVertical
              letterSpacing
              lineHeightPx
              lineHeight
              fontName
              family
              style
              textDecoration]
       :as attrs}]]
  {;;:font-family (str (:family fontName) " " (:style fontName))
   ;; :font-family    (str (:family fontName) (:style fontName))
   :font-family    (:family fontName)
   :font-weight    (get figma-width-map (:style fontName) 500)
   :font-size      (str fontSize "px")
   :text-align     (clojure.string/lower-case textAlignHorizontal)
   :text-decoration textDecoration
   :letter-spacing
   (condp = (:unit letterSpacing)
     "PERCENT" (str (:value letterSpacing) "%")
     (str (:value letterSpacing) "px"))
   :line-height (if (= textAlignVertical "CENTER")
                  (str (:height attrs) "px")
                  (condp = (:unit lineHeight)
                    "PIXELS" (str (:value lineHeight) "px")
                    "PERCENT" (str (:value lineHeight) "%")
                    "auto"))

   ;; :line-height    (when (= (:unit lineHeight) "AUTO")
   ;;                   (str (:height attrs) "px"))
   :font-style     (when italic "italic")})

(defmethod -figma-styles->css :cornerRadius [[_ radius]]
  ;; (println "border radius called")
  {:border-radius (to-px radius)})

(defmethod -figma-styles->css :text-fills [[_ fills]]
  (->> (map #(figma-fill->css :color %) fills)
       merge-css))

(defmethod -figma-styles->css :text-effects [[_ effects]]
  (->> (map #(figma-effect->css :text-shadow %) effects)
       merge-css))

;; layoutMode: "NONE" | "HORIZONTAL" | "VERTICAL"

(def layoutModes
  {"NONE" nil
   "HORIZONTAL" "row"
   "VERTICAL" "column"})

;; layout properties from figma
;; layoutMode
;; counterAxisSizingMode
;; horizontalPadding
;; verticalPadding
;; itemSpacing
;; layoutAlign

(defmethod -figma-styles->css :layoutMode [[_ layoutMode
                                            width
                                            verticalPadding horizontalPadding
                                            itemSpacing
                                            left right top bottom]]
  ;; (println layoutMode)
  ;; (println left)
  ;; (println horizontalPadding)
  (log :info "[layoutMode]")
  (log :info layoutMode)
  (let [flex-direction (get layoutModes layoutMode)
        container {:display "flex"}]
    (if (nil? flex-direction)
      {:width (str width "px")}
      {:display "flex"
       :width (str width "0px")
       :flex-direction flex-direction
       :align-items "center"
       :box-sizing "border-box"
       :justify-content "space-between"})))

(defmethod -figma-styles->css :layoutAlign [[_ alignment]]
  (log :info "[layoutAlign]")
  (log :info alignment)
  (if (= alignment "STRETCH")
    {:width "100%"}
    {}))

;; TODO These should only happen if we're in auto layout...
;; (defmethod -figma-styles->css :horizontalPadding [[_ horizontalPadding]]
;;   {:padding-left (when horizontalPadding (str horizontalPadding "px"))
;;    :padding-right (when horizontalPadding (str horizontalPadding "px"))})
;; 
;; (defmethod -figma-styles->css :verticalPadding [[_ verticalPadding]]
;;   {:padding-top (when verticalPadding (str verticalPadding "px"))
;;    :padding-bottom (when verticalPadding (str verticalPadding "px"))})

;;     justify-content: space-between
       ;; :grid-gap (str itemSpacing "px")
       ;; ;; :grid-gap (str verticalPadding "px " horizontalPadding "px")
       ;; :grid-auto-flow (get layoutModes layoutMode)})))
;; grid-template-columns: repeat(5, 1fr);
;; grid-template-rows: repeat(5, 1fr);

;; layoutAlign: "MIN" | "CENTER" | "MAX"



(defn figma-styles->css [styles]
  (->> (map -figma-styles->css styles)
       (into {})
       (filter second)
       (into {})
       (hash-map :style)))

;; ==========================================

(def ^:dynamic *css-cache*)

(defn -render-css [{:keys [style] :as attrs}]
  attrs)
  ;; (assoc attrs :style {:width (:width)})
  ;; (assoc-in attrs [:style :position] "absolute"))
  ;; (let [class (str "figma-" (hash style))
  ;;       sb    (StringBuilder.)
  ;;   (ssr/append! sb "." class "{")
  ;;   (doseq [[k v] style]
  ;;     (let [k (ssr/normalize-css-key k)
  ;;           v (ssr/normalize-css-value k v)]
  ;;       (ssr/append! sb k ":" v ";")))
  ;;   (ssr/append! sb "}")
  ;;   (->> (str sb)
  ;;        (swap! *css-cache* conj))
  ;;   (-> attrs
  ;;       (dissoc :style)
  ;;       (update :class #(str % " " class))))

;; ==========================================

(defmulti figma-node->hiccup :type)

(defmethod figma-node->hiccup "DOCUMENT" [{:keys [children]} extras]
  `[:div.figma-document {}
    ~@(map (fn [child]
             (figma-node->hiccup child extras))
           children)])

(defmethod figma-node->hiccup "CANVAS"
  [{:keys [children name] :as element} extras]
  (let [attrs   (select-keys element [:backgroundColor])
        attrs   (-render-css (figma-styles->css attrs))
        attrs (assoc attrs :name (clean-component-name (:name element)))
        element `[:div ~attrs
                  ~@(map (fn [child]
                           (figma-node->hiccup child extras))
                         children)]]
    (with-meta element {:figma/type :figma/canvas
                        :figma/name name})))

(defn binding? [t]
  (and (clojure.string/includes? t "[")
       (clojure.string/includes? t "]")))

(defn part->map [n]
  (let [split (clojure.string/split n #":")
        event (keyword (clojure.string/join (rest (first split))))
        handler (clojure.string/join (rest (butlast (second split))))]
    {event
     (symbol handler)}))

(defn name->attrs [n]
  (let [parts (clojure.string/split n #" ")]
    (apply
      merge
      (mapv
        part->map
        (filter binding? parts)))))

(defn get-dom-element [element type]
  (if (:linking_to element)
    (keyword (str "a.figma-" type))
    (keyword (str "div.figma-" type))))

(defn link-attrs [attrs element]
  (llog :info "linking_to")
  (llog :info (:linking_to element))
  (let [target (if (:linking_external element) "_blank" "_self")
        href (if (:linking_external element)
               (:linking_to element)
               (when (:linking_to element)
                 (str "../" (clean-component-name (:linking_to element)))))]
    (if (:linking_to element)
      (assoc attrs :href href
                   ;;TODO add rel="noreferrer noopener" here too
                   :target target
                   :id "figma-link")
      attrs)))

(def frame-keys-to-read
  [:backgroundColor
   :opacity
   :width
   :height
   :backgrounds
   :rotation
   :cornerRadius
   :layoutAlign
   :layoutMode
   :verticalPadding
   :horizontalPadding
   :itemSpacing
   :absoluteBoundingBox
   :fills
   :effects
   :strokes
   :strokeWeight
   :cornerRadius])

(defmethod figma-node->hiccup "FRAME"
  [{:keys [children]
    :as element} extras]
  (let [root? (:isRootFrame element)
        constraints (-constraints->helper-attrs (:constraints element))
        attrs       (select-keys element (if root?
                                           frame-keys-to-read
                                           (concat frame-keys-to-read [:x :y])))
        attrs       (-> (figma-styles->css attrs)
                        (merge (name->attrs (:name element)))
                        (assoc-in [:style :position]
                                  (if root?
                                    "relative"
                                    "absolute"))
                        (assoc :name (clean-component-name (:name element)))
                        (assoc :id (:id element))
                        (merge constraints)
                        -render-css)
        attrs (if root?
                (assoc-in attrs [:style :margin] "0px auto")
                attrs)
        dom-element (get-dom-element element "frame")
        attrs (link-attrs attrs element)
        attrs (if (name->attrs (:name element))
                (assoc attrs :tm-handlers (mapv str (vals (name->attrs (:name element)))))
                attrs)]
    (llog :info "Rendering FRAME")
    `[~dom-element
      ~attrs
      ~@(map (fn [child]
               ;; Work around figma structure sometimes having empty objects
               (when-not (empty? child)
                 (figma-node->hiccup child extras)))
             children)]))
(defmethod figma-node->hiccup "GROUP" [{:keys [children] :as element} extras]
  (let [attrs (select-keys element [:backgroundColor
                                    :absoluteBoundingBox
                                    :effects])
        attrs (assoc attrs :name (clean-component-name (:name element)))
        attrs (-render-css (figma-styles->css attrs))
        dom-element (get-dom-element element "group")
        attrs (link-attrs attrs element)]
    (llog :info "Rendering GROUP")
    `[~dom-element ~attrs
      ~@(map (fn [child]
               (figma-node->hiccup child extras))
             children)]))

(defmethod figma-node->hiccup "VECTOR" [{:keys [children] :as element} extras]
  (let [attrs (select-keys element [:opacity
                                    :width
                                    :height
                                    :rotation
                                    :x
                                    :y
                                    :absoluteBoundingBox
                                    :effects
                                    ;; :fills
                                    :vectorNetwork
                                    :strokes
                                    :strokeWeight])
        attrs (assoc attrs :position "absolute")
        attrs (if (> (count (:vectorNetwork attrs)) 0)
                (dissoc attrs :strokes)
                attrs)
        svg-path (str "../_vectors/" (:id element) ".svg")
        attrs (-render-css (figma-styles->css attrs))]
    (llog :info "Rendering VECTOR")
    [:img
      (merge
        attrs
        {:src svg-path})]))

(defmethod figma-node->hiccup "RECTANGLE" [element extras]
  (let [attrs              (select-keys element [:opacity
                                                 :width
                                                 :x
                                                 :y
                                                 :height
                                                 :absoluteBoundingBox
                                                 :effects
                                                 :position
                                                 :layoutAlign
                                                 (when-not (:isMask element)
                                                   :fills)
                                                 :rotation
                                                 :strokes
                                                 :strokeWeight
                                                 :cornerRadius
                                                 :constraints])
        styles (:style (figma-styles->css attrs))
        attrs              (-render-css {:style styles})
        attrs (assoc attrs :name (clean-component-name (:name element)))
        attrs (assoc-in attrs [:style :position] "absolute")
        dom-element (get-dom-element element "rectangle")
        attrs (link-attrs attrs element)]
    (llog :info "Rendering RECTANGLE")
    [dom-element attrs]))

(defmethod figma-node->hiccup "ELLIPSE" [element extras]
  (-> element
      (assoc :type "RECTANGLE"
             :cornerRadius 50)
      (figma-node->hiccup extras)))

(defmethod figma-node->hiccup "POLYGON" [element extras]
  (println "WARNING: POLYGON encountered but we don't support that yet..."))

(defmethod figma-node->hiccup "BOOLEAN_OPERATION" [element extras]
  (println "WARNING: BOOLEAN_OPERATION encountered but we don't support that yet..."))

(defmethod figma-node->hiccup "STAR" [element extras]
  (println "WARNING: STAR encountered but we don't support that yet..."))

(defn add-font! [fonts font-name font-weight]
  (llog :info (format "adding font %s with font-weight %s" font-name font-weight))
  (if (get @fonts font-name)
    (swap! fonts update font-name conj (get width-map font-weight))
    (swap! fonts assoc font-name #{(get width-map font-weight)})))

;; https://fonts.googleapis.com/css2?family=Roboto:wght@400;700&display=swap
(def google-fonts-url
  "https://fonts.googleapis.com/css2?
   family=Lato:wght@400;700&
   family=Roboto:wght@400;700&
   display=fallback")

(def not-nil? (complement nil?))

(defn create-google-fonts-url [fonts]
  (str
    "https://fonts.googleapis.com/css2?"
    (string/join
      "&"
      (filter
        not-nil?
        (map (fn [[k v]]
               (when (and k
                          (> (count v) 0))
                 (str "family="
                      k
                      ":wght@"
                      (string/join ";" (sort (filter not-nil? v))))))
             fonts)))
    "&display=fallback"))

(defn characters->spans [text indices]
  (->> (sort-by (fn [[k v]] (:start v)) < indices) ; guarantee the order of indices based on :start
       (reduce ; create map of size + text, split text
         (fn [acc [fontSize {:keys [start end]}]]
           (assoc acc fontSize (subs text start end)))
         {})
       (map ; organize into spans we can render
         (fn [[size text]]
           [:span
            {:style {:font-size (str (name size) "px")}}
            text]))))

(comment
  (characters->spans "hello world" {:24 {:start 6
                                         :end 11}
                                    :12 {:start 0
                                         :end 6}})
  (characters->spans "hello world" {:12 {:start 0
                                         :end 6}
                                    :24 {:start 6
                                         :end 11}}))

(defmethod figma-node->hiccup "TEXT"
  [{:keys [characters style opacity effects fills
           width height x y fontName rotation fontSize
           fontSizeRanges] :as element} extras]
  (add-font! (:fonts extras)
             (-> element :fontName :family)
             (-> element :fontName :style))
  (let [name (clean-component-name (:name element))
        attrs (figma-styles->css {:opacity         opacity
                                  :width (+ width 2)
                                  :height height
                                  :rotation rotation
                                  :x x
                                  :y y
                                  :position "absolute"
                                  :white-space "pre-wrap"

                                  :font-type-style element
                                  :text-effects    effects
                                  :text-fills      fills})
        attrs (assoc attrs :name name)
        attrs (assoc attrs :type "TEXT")
        dom-element (get-dom-element element "text")
        attrs (link-attrs attrs element)

        attrs (-render-css attrs)]
    (if (= fontSize "MIXED")
      [dom-element
       attrs
       (characters->spans characters fontSizeRanges)]
      [dom-element
         attrs
         characters])))

(comment
  (figma-node->hiccup example-text-node {:fonts (atom {})})
  (pprint example-text-node)
  (def example-text-node {:y 474,
                          :constraints {:horizontal "MIN", :vertical "MIN"},
                          :relativeTransform [[1 0 486] [0 1 474]],
                          :characters "This text has many different sizes",
                          :strokeWeight 1,
                          :strokeAlign "OUTSIDE",
                          :rotation 0,
                          :layoutAlign "MIN",
                          :parent {:id "1:2"},
                          :constrainProportions false,
                          :hasMissingFont false,
                          :name "This text has many different sizes",
                          :dashPattern [],
                          :paragraphIndent 0,
                          :width 467,
                          :effectStyleId "",
                          :absoluteTransform [[1 0 -234] [0 1 -39]],
                          :textStyleId "",
                          :type "TEXT",
                          :blendMode "PASS_THROUGH",
                          :strokeMiterLimit 4,
                          :fillStyleId "",
                          :autoRename true,
                          :textAutoResize "WIDTH_AND_HEIGHT",
                          :textCase "ORIGINAL",
                          :textAlignVertical "CENTER",
                          :letterSpacing {:unit "PERCENT", :value 0},
                          :locked false,
                          :opacity 1,
                          :id "1:3",
                          :fontSizeRanges
                          {:14 {:start 29, :end 34},
                           :18 {:start 19, :end 29},
                           :24 {:start 14, :end 19},
                           :36 {:start 10, :end 14},
                           :48 {:start 5, :end 10},
                           :64 {:start 0, :end 5}},
                          :effects [],
                          :fontName {:family "Roboto", :style "Regular"},
                          :strokeJoin "MITER",
                          :strokeStyleId "",
                          :paragraphSpacing 0,
                          :lineHeight {:unit "AUTO"},
                          :strokeCap "NONE",
                          :strokes [],
                          :x 486,
                          :fontSize "MIXED",
                          :isMask false,
                          :visible true,
                          :exportSettings [],
                          :fills
                          [{:type "SOLID",
                            :visible true,
                            :opacity 1,
                            :blendMode "NORMAL",
                            :color {:r 0, :g 0, :b 0}}],
                          :textDecoration "NONE",
                          :reactions [],
                          :removed false,
                          :textAlignHorizontal "CENTER",
                          :height 75}))

(defmethod figma-node->hiccup "COMPONENT" [{:keys [name handle-event] :as element} extras]
  (let [element (-> element
                    (assoc :type "FRAME")
                    (figma-node->hiccup extras))]
    (with-meta element {:figma/type :figma/component
                        :figma/name name})))

(defmethod figma-node->hiccup "INSTANCE" [{:keys [name] :as element} extras]
  (let [element (-> element
                    (assoc :type "FRAME")
                    (figma-node->hiccup extras))]
    (with-meta element {:figma/type :figma/instance
                        :figma/name name})))


(defn figma->hiccup [document]
  (let [font-cache (atom {})]
    (with-meta
      (figma-node->hiccup document {:fonts font-cache})
      {:fonts font-cache})))

;; ==========================================

(def components* (atom '()))

(defn -hiccup->components [element cc]
  (if (or (string? element) (number? element))
    element
    (let [[tag attrs & children] element
          {:figma/keys [type name]} (meta element)]
      (cond
        (and (= type :figma/canvas) (= name cc))
        (doseq [element children]
          (-hiccup->components element cc))

        (= type :figma/component)
        (let [component `(~'rum/defc ~(symbol name) []
                           [~tag ~attrs ~@(map #(-hiccup->components % cc) children)])]
          (swap! components* conj component)
          nil)

        (= type :figma/instance)
        (list (symbol name))

        (not (empty? children))
        `[~tag ~attrs ~@(map #(-hiccup->components % cc) children)]

        :else
        [tag attrs]))))

(defn hiccup->components [element cc]
  (reset! components* '())
  (let [document (-hiccup->components element cc)]
    {:document   document
     :components @components*}))
