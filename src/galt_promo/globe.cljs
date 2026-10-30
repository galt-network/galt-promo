(ns galt-promo.globe
  (:require [galt-promo.arcs :as arcs]
            [galt-promo.cluster :as cluster]
            ["globe.gl" :default Globe]
            ["solar-calculator" :as solar]
            ["three" :refer [ShaderMaterial TextureLoader Vector2]]))

(def ^:private day-texture
  "https://cdn.jsdelivr.net/npm/three-globe/example/img/earth-day.jpg")
(def ^:private night-texture
  "https://cdn.jsdelivr.net/npm/three-globe/example/img/earth-night.jpg")
(def ^:private sky-texture
  "https://cdn.jsdelivr.net/npm/three-globe/example/img/night-sky.png")

(def ^:private intro-duration-ms 6000)
(def ^:private arc-flight-ms 1400)
(def ^:private arc-lifetime-ms 1800)

(def ^:private marker-svg
  "<svg viewBox=\"-4 0 36 36\" aria-hidden=\"true\"><path fill=\"currentColor\" d=\"M14,0 C21.732,0 28,5.641 28,12.6 C28,23.963 14,36 14,36 C14,36 0,24.064 0,12.6 C0,5.641 6.268,0 14,0 Z\"></path><circle fill=\"#101014\" cx=\"14\" cy=\"14\" r=\"7\"></circle></svg>")

(def ^:private day-night-shader
  {:vertex-shader "
    varying vec3 vNormal;
    varying vec2 vUv;
    void main() {
      vNormal = normalize(normalMatrix * normal);
      vUv = uv;
      gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0);
    }
  "
   :fragment-shader "
    #define PI 3.141592653589793
    uniform sampler2D dayTexture;
    uniform sampler2D nightTexture;
    uniform vec2 sunPosition;
    uniform vec2 globeRotation;
    varying vec3 vNormal;
    varying vec2 vUv;

    float toRad(in float a) {
      return a * PI / 180.0;
    }

    vec3 Polar2Cartesian(in vec2 c) {
      float theta = toRad(90.0 - c.x);
      float phi = toRad(90.0 - c.y);
      return vec3(
        sin(phi) * cos(theta),
        cos(phi),
        sin(phi) * sin(theta)
      );
    }

    void main() {
      float invLon = toRad(globeRotation.x);
      float invLat = -toRad(globeRotation.y);
      mat3 rotX = mat3(
        1, 0, 0,
        0, cos(invLat), -sin(invLat),
        0, sin(invLat), cos(invLat)
      );
      mat3 rotY = mat3(
        cos(invLon), 0, sin(invLon),
        0, 1, 0,
        -sin(invLon), 0, cos(invLon)
      );
      vec3 rotatedSunDirection = rotX * rotY * Polar2Cartesian(sunPosition);
      float intensity = dot(normalize(vNormal), normalize(rotatedSunDirection));
      vec4 dayColor = texture2D(dayTexture, vUv);
      vec4 nightColor = texture2D(nightTexture, vUv);
      float blendFactor = smoothstep(-0.1, 0.1, intensity);
      gl_FragColor = mix(nightColor, dayColor, blendFactor);
    }
  "})

(defn- solar-position []
  (let [now (.getTime (js/Date.))
        day (.setUTCHours (js/Date. now) 0 0 0 0)
        century (solar/century now)]
    #js [(- (* (/ (- day now) 864e5) 360) 180
            (/ (solar/equationOfTime century) 4))
         (solar/declination century)]))

(defn- set-vector! [material uniform-name x y]
  (when-let [uniform (aget (.-uniforms material) uniform-name)]
    (.set (.-value uniform) x y)))

(defn- visible? [globe lat lng]
  (let [point (.getCoords ^js globe lat lng)
        camera (.-position (.camera ^js globe))
        radius (.getGlobeRadius ^js globe)]
    (> (+ (* (.-x point) (.-x camera))
          (* (.-y point) (.-y camera))
          (* (.-z point) (.-z camera)))
       (* radius radius))))

(defn- projected-user [globe user]
  (let [location (:location user)
        lat (:lat location)
        lng (:lng location)]
    (when (and (number? lat)
               (number? lng)
               (visible? globe lat lng))
      (let [screen (.getScreenCoords ^js globe lat lng)]
        (assoc user :lat lat :lng lng :x (.-x screen) :y (.-y screen))))))

(defn- markers [globe users]
  (->> users
       (keep #(projected-user globe %))
       cluster/cluster-users))

(defn- user-marker [^js marker]
  (let [element (.createElement js/document "div")
        label (.createElement js/document "span")]
    (set! (.-className element) "globe-user-marker")
    (set! (.-innerHTML element) marker-svg)
    (set! (.-className label) "globe-user-label")
    (set! (.-textContent label) (.-name marker))
    (.appendChild element label)
    element))

(defn- cluster-marker [^js marker on-cluster]
  (let [button (.createElement js/document "button")]
    (set! (.-className button) "globe-cluster-marker")
    (set! (.-type button) "button")
    (set! (.-textContent button) (str (.-length (.-users marker))))
    (set! (.-title button) "Show users in this cluster")
    (set! (.-onclick button)
          (fn [event]
            (.stopPropagation event)
            (.preventDefault event)
            (on-cluster (mapv #(aget % "id") (.-users marker)))))
    button))

(defn- draw-markers! [{:keys [globe users] :as controller}]
  (when (and @globe (not @(:destroyed? controller)))
    (.htmlElementsData ^js @globe
                       (clj->js (markers @globe @users)))))

(defn- schedule-markers! [{:keys [frame] :as controller}]
  (when (and (not @frame) (not @(:destroyed? controller)))
    (reset! frame
            (js/requestAnimationFrame
             (fn []
               (reset! frame nil)
               (draw-markers! controller))))))

(defn- install-material! [{:keys [globe material] :as controller} day night]
  (when-not @(:destroyed? controller)
    (let [shader (ShaderMaterial.
                  #js {:uniforms
                       #js {:dayTexture #js {:value day}
                            :nightTexture #js {:value night}
                            :sunPosition #js {:value (Vector2.)}
                            :globeRotation #js {:value (Vector2.)}}
                       :vertexShader (:vertex-shader day-night-shader)
                       :fragmentShader (:fragment-shader day-night-shader)})
          pov (.pointOfView ^js @globe)
          sun (solar-position)]
      (set-vector! shader "sunPosition"
                   (aget sun 0) (aget sun 1))
      (set-vector! shader "globeRotation" (.-lng pov) (.-lat pov))
      (.globeMaterial ^js @globe shader)
      (reset! material shader))))

(defn- load-material! [controller]
  (let [loader (TextureLoader.)]
    (-> (js/Promise.all
         #js [(.loadAsync loader day-texture)
              (.loadAsync loader night-texture)])
        (.then (fn [textures]
                 (install-material! controller
                                    (aget textures 0)
                                    (aget textures 1)))))))

(defn- update-sun! [{:keys [material] :as controller}]
  (when (and @material (not @(:destroyed? controller)))
    (let [position (solar-position)]
      (set-vector! @material "sunPosition"
                   (aget position 0) (aget position 1)))))

(defn- current-location [{:keys [users current-user-id]}]
  (some (fn [user]
          (when (and (= current-user-id (:id user))
                     (number? (get-in user [:location :lat]))
                     (number? (get-in user [:location :lng])))
            (:location user)))
        @users))

(defn- recenter-precise! [{:keys [globe intro-complete? centered-source] :as controller}]
  (let [location (current-location controller)]
    (when (and @globe
               @intro-complete?
               (= :browser (:source location))
               (not= :browser @centered-source))
      (reset! centered-source :browser)
      (.pointOfView ^js @globe
                    #js {:lat (:lat location) :lng (:lng location)}
                    1000))))

(defn- start-intro! [{:keys [globe users intro-done? intro-complete? intro-frame
                             centered-source]
                      :as controller}]
  (when (and @globe (not @intro-done?) (seq @users))
    (let [pov (.pointOfView ^js @globe)
          location (current-location controller)
          lat (or (:lat location) (.-lat pov))
          lng (or (:lng location) (.-lng pov))
          altitude (.-altitude pov)
          started-at (js/performance.now)]
      (reset! intro-done? true)
      (reset! centered-source (:source location))
      (letfn [(frame [timestamp]
                (let [progress (js/Math.min 1 (/ (- timestamp started-at) intro-duration-ms))
                      eased (- (* 3 progress progress) (* 2 progress progress progress))]
                  (.pointOfView ^js @globe
                                #js {:lat lat
                                     :lng (+ (- lng 360) (* 360 eased))
                                     :altitude altitude}
                                0)
                  (if (< progress 1)
                    (reset! intro-frame (js/requestAnimationFrame frame))
                    (do
                      (reset! intro-frame nil)
                      (reset! intro-complete? true)
                      (recenter-precise! controller)))))]
        (reset! intro-frame (js/requestAnimationFrame frame))))))

(defn- resize! [{:keys [globe node] :as controller}]
  (when-let [globe @globe]
    (let [width (.-clientWidth node)
          height (.-clientHeight node)]
      (when (and (pos? width) (pos? height))
        (.width ^js globe width)
        (.height ^js globe height)
        (schedule-markers! controller)))))

(defn- draw-arcs! [{:keys [globe active-arcs]}]
  (when-let [globe @globe]
    (.arcsData ^js globe (clj->js @active-arcs))))

(defn- remove-arc-batch! [{:keys [active-arcs destroyed?] :as controller} batch-id]
  (when-not @destroyed?
    (swap! active-arcs
           (fn [active]
             (filterv #(not= batch-id (:batch-id %)) active)))
    (draw-arcs! controller)))

(defn- add-presence-arcs! [{:keys [previous-users active-arcs arc-timers] :as controller} users]
  (let [new-arcs (arcs/transition-arcs @previous-users users)]
    (when (seq new-arcs)
      (let [batch-id (str (random-uuid))]
        (swap! active-arcs into (map #(assoc % :batch-id batch-id)) new-arcs)
        (draw-arcs! controller)
        (let [timer (js/setTimeout
                     (fn []
                       (swap! arc-timers dissoc batch-id)
                       (remove-arc-batch! controller batch-id))
                     arc-lifetime-ms)]
          (swap! arc-timers assoc batch-id timer))))))

(defn mount! [node users current-user-id on-cluster]
  (let [controller {:globe (atom nil)
                    :node node
                    :users (atom users)
                    :current-user-id current-user-id
                    :previous-users (atom nil)
                    :active-arcs (atom [])
                    :arc-timers (atom {})
                    :intro-done? (atom false)
                    :intro-complete? (atom false)
                    :intro-frame (atom nil)
                    :centered-source (atom nil)
                    :resize-observer (atom nil)
                    :material (atom nil)
                    :frame (atom nil)
                    :clock (atom nil)
                    :destroyed? (atom false)
                    :on-cluster on-cluster}
        globe (Globe. node)]
    (reset! (:globe controller) globe)
    (doto globe
      (.backgroundColor "#000011")
      (.backgroundImageUrl sky-texture)
      (.globeImageUrl day-texture)
      (.atmosphereColor "#4da3ff")
      (.atmosphereAltitude 0.18)
      (.arcsData #js [])
      (.arcColor (fn [arc] (.-color arc)))
      (.arcDashLength 0.4)
      (.arcDashGap 2)
      (.arcDashInitialGap 1)
      (.arcDashAnimateTime arc-flight-ms)
      (.arcsTransitionDuration 0)
      (.htmlTransitionDuration 0)
      (.htmlElement
       (fn [marker]
         (if (= "cluster" (.-type marker))
           (cluster-marker marker on-cluster)
           (user-marker marker))))
      (.htmlElementVisibilityModifier
       (fn [element is-visible]
         (set! (.-opacity element) (if is-visible 1 0))))
      (.onZoom
       (fn [pov]
         (when-let [material @(:material controller)]
           (set-vector! material "globeRotation" (.-lng pov) (.-lat pov)))
         (schedule-markers! controller))))
    (resize! controller)
    (reset! (:resize-observer controller)
            (js/ResizeObserver. (fn [_] (resize! controller))))
    (.observe ^js @(:resize-observer controller) node)
    (load-material! controller)
    (start-intro! controller)
    (reset! (:clock controller)
            (js/setInterval #(update-sun! controller) 1000))
    (schedule-markers! controller)
    controller))

(defn set-users! [controller users]
  (add-presence-arcs! controller users)
  (reset! (:previous-users controller) users)
  (reset! (:users controller) users)
  (start-intro! controller)
  (recenter-precise! controller)
  (schedule-markers! controller))

(defn destroy! [{:keys [globe frame clock intro-frame resize-observer arc-timers]
                 :as controller}]
  (reset! (:destroyed? controller) true)
  (when @frame
    (js/cancelAnimationFrame @frame))
  (when @intro-frame
    (js/cancelAnimationFrame @intro-frame))
  (when @clock
    (js/clearInterval @clock))
  (when-let [observer @resize-observer]
    (.disconnect observer))
  (doseq [[_ timer] @arc-timers]
    (js/clearTimeout timer))
  (when-let [globe @globe]
    (.htmlElementsData ^js globe #js [])
    (.arcsData ^js globe #js [])
    (when-let [renderer (.renderer ^js globe)]
      (.dispose renderer))
    (when-let [controls (.controls ^js globe)]
      (.dispose controls))))
