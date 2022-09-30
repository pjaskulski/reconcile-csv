(ns reconcile-csv.core
  (:use [ring.adapter.jetty]
        [ring.middleware.params]
        [compojure.core :only (defroutes GET POST)]
        [clojure.tools.nrepl.server
         :only (start-server stop-server)]
        )
  (:require [compojure.route :as route]
            [compojure.handler :as handler]
            [clojure.data.json :as json]
            [fuzzy-string.core :as fuzzy]
            [csv-map.core :as csv-map])
  (:gen-class))


(def data (atom (list)))
(def config (atom {}))

(defn hello [request]
  "this used to say hello world... the index page"
  {:status 200
   :headers {"Content-Type" "text/html;charset=UTF-8"}
   :body (slurp "index.html.tpl")})

(defn encapsulate-jsonp [callback d]
  "encapsulate the return in jsonp"
  (str callback "(" d ")"))

(defn json-response [callback d]
  "return a json or jsonp response - with headers and shit"
  {:status 200
   :headers {"Content-Type" "application/json;charset=UTF-8"}
   :body (if callback
           (encapsulate-jsonp callback
                              (json/write-str d))
           (json/write-str d))})

(defn get-data []
  "return all the data"
  (json-response (vec @data)))

(defn four-o-four []
  "the error page"
  {:status 404
   :headers {"Content-Type" "text/html;charset=UTF-8"}
   :body "404 not found"})

(defn service-metadata []
  "returns the service metadata"
  {:name (:service-name @config)
   :identifierSpace (:server-name @config)
   :schemaSpace (:server-name @config)
   :defaultTypes []
   :view  {
            :url (str (:server-name @config) "/view/{{id}}")
            }
   :preview {
             :url (str (:server-name @config) "/view/{{id}}")
             :width 500
             :height 350
             }
   :suggest {
             :entity {
                      :service_url (:server-name @config)
                      :service_path "/suggest"
                      :flyout_service_url (:server-name @config)
                      :flyout_service_path "/flyout"
                      }}})


(defn score [^clojure.lang.PersistentVector query
             ^clojure.lang.PersistentArrayMap row]
  "calculates the score for a query - which at this stage is a vector of vectors..."
  (let [fuzzy-match (fn [x]
                      (fuzzy/dice (second x)
                                  (get row (first x))))]
  (->> query
       (map fuzzy-match)
       (reduce *)
       (assoc (meta row) :score))))

(defn score-response [^clojure.lang.PersistentArrayMap x]
  "creates the response to a score"
  {:id (get x (:id-column @config))
   :name (get x (:search-column @config))
   :score (:score x)
   :match (if (= (:score x) 1) true false)
   :type [{"name" (:type-name @config)
           "id" "/csv-recon"}]
   }
  )

(defn extend-query [query properties]
  "If the query contains properties - add them to the query"
  (loop [q query p properties]
    (if-let [tp (first p)]
      (recur (assoc q (:pid tp) (clojure.string/lower-case (:v tp))) (rest p))
      q)))

(defn scores [q json?]
  "calculate the scores for a query"
  (let [query {(:search-column @config)
               (clojure.string/lower-case
                (if json? (:query q) q))}
        limit (or (:limit q) 5)
        query (if-let [prop (:properties q)]
                (extend-query query prop)
                query)
        score (partial score query)]
    (->> @data
        (map score)
        (sort-by (comp - :score))
        (take limit)
        (map score-response)
        (vec))))

(defn reconcile-param [query]
  "reconcile a single parameter"
  (let [q (try (json/read-str query :key-fn keyword)
               (catch Exception e query))
        j (if (:query q) true false)
        ]
  {:result (scores q j)}
  ))

(defn reconcile-params [queries]
  "reconcile multiple parameters"
  (let [queries (json/read-str queries :key-fn keyword)]
        (zipmap (keys queries)
                (pmap reconcile-param (vals queries)))))

(defn reconcile [request]
  "handles reconcile requests"
  (let [params (:params request)]
    (json-response (:callback params)
                   (cond
                    (:query params) (reconcile-param
                                     (:query params))
                    (:queries params) (reconcile-params
                                       (:queries params))
                    :else (service-metadata)))))

(defn get-record-by-id [id]
  "get a record when we have an id"
   (meta (first (filter #(= (get (meta %) (:id-column @config)) id) @data))))

(defn table-from-object [o]
  "create a table from an object"
  (str
   "<table>"
   (clojure.string/join
    ""
    (map #(str
           "<tr><td><strong>"
            (first %)
            "</strong></td><td>"
            (second %)
            "</td></tr>")
         o))
   "</table>"))

(defn view [id]
  "return the view for an id"
  (if-let [o (get-record-by-id id)]
      (str "<html><head>"
           "<style>table, th, td { border: 1px solid black; } th, td { padding: 5px; }</style>"
           "</head><body>"
           (table-from-object o)
           "</body></html>")
      (four-o-four)))

(defn suggest [request]
  "suggest api - return suggestions"
  (let [params (:params request)
        prefix (:prefix params)
        limit (or (:limit params) 10)
        query {:query prefix
               :limit limit}]
    (json-response (:callback params)
                   {
                    :code "/api/status/ok"
                    :status "200 OK"
                    :prefix prefix
                    :result (map
                             #(assoc %
                                :n:type
                                {:id "/csv-recon"
                                 :name "csv-recon" })
                             (:result (reconcile-param query)))
                    })))

(defn flyout [request]
  "the flyout - what is shown after searches"
  (let [params (:params request)
        callback (:callback params)
        id (:id params)]
    (if-let [o (get-record-by-id id)]
      (json-response callback
                     {:id id
                      :html (str
                             "<div style='color:#000'><strong>"
                             (get o (:search-column @config))
                             "</strong><br/>"
                             (table-from-object o)
                             "</div>"
                             )})
      (four-o-four))))

(defn map-map
  "maps a function over the values of a map. Returns a map"
  [func m]
  (reduce (fn [x y] (assoc x (y 0) (y 1)))
          {}
          (map (fn [x] [(x 0) (func (x 1))])  m)))

(defn lcase-data
  "lowercases data and leaves the original data as metadata"
  [data]
  (map (fn [x]
         (with-meta (map-map clojure.string/lower-case x) x))
       data))

(defroutes routes
  (GET "/" [] (hello nil))
  (GET "/reconcile" [:as r] (reconcile r))
  (POST "/reconcile" [:as r] (reconcile r))
  (GET "/view/:id" [id] (view id))
  (GET "/data" [] (get-data))
  (GET "/suggest" [:as r] (suggest r))
  (GET "/flyout" [:as r] (flyout r))
  (GET "/private/flyout" [:as r] (flyout r))
  (route/not-found (four-o-four)))

(def app
   (handler/api routes))

(defn -main [file search-column id-column server-name port-number service-name type-name]
  "main function - start the servers!"
  (defonce server (start-server :port 7888))
  (swap! data (fn [x file] (lcase-data (csv-map/parse-csv (slurp file)))) file)
  (swap! config (fn [x y] (assoc x :search-column y)) search-column)
  (swap! config (fn [x y] (assoc x :id-column y)) id-column)
  (swap! config (fn [x y] (assoc x :server-name y)) server-name)
  (swap! config (fn [x y] (assoc x :service-name y)) service-name)
  (swap! config (fn [x y] (assoc x :type-name y)) type-name)
  (println "Starting CSV Reconciliation service")
  (println "Point refine to server name e.g. http://localhost:8000 as reconciliation service")
  (run-jetty app {:port (Integer/parseInt port-number) :join? false}))
