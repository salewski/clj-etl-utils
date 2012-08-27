;; # Caching Utilities
;;
;; Basic memoization produces a function with a cache wrapped around it.
;; This type of cache will have unbounded growth.  The functions
;; in this module are for maintaining similar behavior while allowing
;; caches to have various flushing policies associated with them.
;;
(ns clj-etl-utils.cache-utils
  (:use
   [clj-etl-utils.lang-utils :only [raise aprog1]])
  (:import
   [org.joda.time DateTime]))


;; ## Cache Registry
;;
;; The intent is for static caches to be registered so they can be
;; managed by code outside of the memoize wrapper.
;;
;; Best practice for naming caches is the namespace and the wrapped
;; function name.  Eg:
;;
;;   `(register-cache :clj-etl-utils.cache-utils.my-function #{:standard} (atom {}))`
;;
(defonce *cache-registry* (atom {}))

;; ### Cache Tags
;;
;; Allows caches to be operated on based on a 'type'.
;; The first supported type for this module is :standard
;; which represents a standard memoize based cache.

(defn register-cache [name #^java.util.Set tags cache-ref]
  (swap! *cache-registry* assoc name {:name name :tags tags :cache cache-ref}))

(defn lookup-cache-by-name [name]
  (get @*cache-registry* name))

(defn purge-cache-named [n]
  (reset! (:cache (lookup-cache-by-name n))
          {}))

(defn lookup-caches-by-tag [tag]
  (filter (fn [entry]
            (contains? (:tags entry) tag))
          (vals @*cache-registry*)))

(defn purge-standard-caches []
  (doseq [entry (lookup-caches-by-tag :standard)]
    (reset! (:cache entry) {})))


;;

(comment

  (map :cache (lookup-caches-by-tag :standard))
  )

(defn wrap-standard-cache [name tags the-fn args-ser-fn]
  (let [cache (atom {})]
    (register-cache name tags cache)
    (fn [& args]
      (let [k    (args-ser-fn args)
            cmap @cache]
        (if (contains? cmap k)
          (get cmap k)
          (aprog1
              (apply the-fn args)
            (swap! cache assoc k it)))))))

(defn simple-cache [name the-fn]
  (wrap-standard-cache name #{:standard} the-fn identity))

(defmacro def-simple-cached [name arg-spec & body]
  `(def ~name
        (simple-cache ~(keyword (str *ns* "." name))
                      (fn ~arg-spec
                        ~@body))))

(comment

  (register-cache :test1 #{:standard} (atom {}))
  (lookup-cache-by-name :test1)
  (lookup-caches-by-tag :standard)

  (defn my-func [a b c]
    (Thread/sleep 1000)
    (+ a b c))

  (def-simple-cached my-func2 [a b c]
    (Thread/sleep 1000)
    (+ a b c))

  (def cached-func (simple-cache :my-func my-func))

  (purge-standard-caches)

  (time
   (cached-func 1 2 3))

  (time
   (my-func2 1 2 3))

  )


(defn wrap-countdown-cache [name tags the-fn config]
  (let [cache       (atom {})
        args-ser-fn (:args-ser-fn config)
        max-hits    (:max-hits    config 100)
        nhits       (java.util.concurrent.atomic.AtomicLong. 0)]
    (register-cache name tags cache)
    (fn [& args]
      (let [k    (args-ser-fn args)
            cmap @cache]
        (when (>= (.incrementAndGet nhits) max-hits)
          (.set nhits 0)
          (reset! cache {}))
        (if (contains? cmap k)
          (get cmap k)
          (aprog1
              (apply the-fn args)
            (swap! cache assoc k it)))))))

(defmacro def-countdown-cached [name max-hits arg-spec & body]
  `(def ~name
        (wrap-countdown-cache
         ~(keyword (str *ns* "." name))
         #{:countdown}
         (fn ~arg-spec
           ~@body)
         {:max-hits    ~max-hits
          :args-ser-fn identity})))

(defn wrap-timeout-cache [name tags the-fn config]
  (let [cache       (atom {})
        args-ser-fn (:args-ser-fn config)
        duration    (long  (:duration config (* 1000 60 60)))
        exp-time    (atom  (.plusMillis  (DateTime.) duration))]
    (register-cache name tags cache)
    (fn [& args]
      (let [k    (args-ser-fn args)
            cmap @cache]
        (when (.isBeforeNow @exp-time)
          (reset! exp-time (.plusMillis  (DateTime.) duration))
          (reset! cache {}))
        (if (contains? cmap k)
          (get cmap k)
          (aprog1
              (apply the-fn args)
            (swap! cache assoc k it)))))))


(defmacro def-timeout-cached [name duration arg-spec & body]
   `(def ~name
         (wrap-timeout-cache
          ~(keyword (str *ns* "." name))
          #{:timeout}
         (fn ~arg-spec
           ~@body)
         {:duration    ~duration
          :args-ser-fn identity})))



(comment

  (def-timeout-cached timeout-test 5000 [a b c]
    (println "function is called")
    (+ a b c))

  (timeout-test 1 2 3)

  (doto (DateTime.)
    (.plusMillis 1000000))

  (.plusMillis (DateTime. ) 1000000)

  (def-countdown-cached countdown-test 5 [a b c]
    (println "function is called")
    (+ a b c))

  (countdown-test 1 2 3)


  )