(ns user-throttler.handler
  (:require [compojure.core :refer :all]
            [compojure.route :as route]
            [compojure.handler :as handler]
            [ring.middleware.json :as middleware]
            [datomic.api :as d]
            [clojure.java.io :as io]
            [ring.middleware.json :refer [wrap-json-response]]
            [ring.util.response :refer [response]]
            [clj-http.client :as client]
            [clj-time.core :as time]
            [cheshire.core :as cheshire]
            ))

;; DEFAULT USER SETTINGS
(def default-user-hourly 20N)
(def default-user-dayly 100N)

;;; DATABASE CONNECTION SHIZZLE
;(def db-uri-base "datomic:mem://testdb")
(def db-uri-base "datomic:free://localhost:4334/users")

(def conn nil)

(defn add-new-user [user-name uuid hourly dayly]
  (let [user-id (d/tempid :db.part/user)]
    @(d/transact conn [{:db/id user-id :user/name user-name}
                       {:db/id user-id :user/uuid uuid}
                      {:db/id user-id :user/maxPerHour hourly}
                      {:db/id user-id :user/maxPerDay dayly}])))

(defn populate []
  (add-new-user "default" "0" default-user-hourly default-user-dayly)
)

(defn reset-database []
  (d/delete-database db-uri-base)
  (d/create-database db-uri-base)
  (def conn (d/connect db-uri-base))
  (d/transact conn (load-file "resources/datomic/schema.edn"))
  (populate)
)

(defn create-database []
  (def conn (d/connect db-uri-base))
)

(defn ensure-db []
  (if (nil? conn)
    (create-database)))

(defn get-uuid-for-logged-in-user [request]
  "1")

;;;;;;; DATOMIC SECTION ;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn add-user-request [uuid target]
  (let [access-uri (d/tempid :db.part/user)]
    @(d/transact conn [{:db/id access-uri
                        :request/url target}
                       {:db/id access-uri
                        :request/userId [:user/uuid uuid]}
                      ])))

(defn find-request-ids-for-user [uuid]
  (d/q '[:find ?request-id
         :in $ ?uuid
         :where
         [?user-id :user/uuid ?uuid]
         [?request-id :request/userId ?user-id]
        ]
       (d/db conn)
       uuid))

(defn find-all-requests-for-user [uuid]
  (d/q '[:find ?request-id ?request-url
         :in $ ?uuid
         :where
         [?user-id :user/uuid ?uuid]
         [?request-id :request/userId ?user-id]
         [?request-id :request/url ?request-url]
        ]
       (d/db conn)
       uuid))

(defn find-all-requests []
  (d/q '[:find ?request-id ?request-url
         :where
         [?request-id :request/url ?request-url]
        ]
       (d/db conn)))

(defn find-quota-for-user [uuid]
  (d/q '[:find ?hourly ?dayly
         :in $ ?uuid
         :where 
         [?user-id :user/uuid ?uuid]
         [?user-id :user/maxPerHour ?hourly]
         [?user-id :user/maxPerDay ?dayly]
        ]
       (d/db conn)
       uuid))

(defn find-requests-for-user-in-last-hour [uuid]
  (d/q '[:find ?request-id
         :in $ ?uuid
         :where
         [?user-id :user/uuid ?uuid]
         [?request-id :request/userId ?user-id ?transaction-id]
         [?transaction-id :db/txInstant ?timestamp]
         [(> (- (time/now) ?timestamp) 3600)]
         ]
       (d/db conn)
       uuid))

(defn find-requests-for-user-in-last-day [uuid]
  (d/q '[:find ?request-id
         :in $ ?uuid
         :where
         [?user-id :user/uuid ?uuid]
         [?request-id :request/userId ?user-id ?transaction-id]
         [?transaction-id :db/txInstant ?timestamp]
         [(> (- (time/now) ?timestamp) (* 24 3600))]
         ]
       (d/db conn)
       uuid))

;; (defn has-requests-left [uuid]
;;   (< (count (find-request-ids-for-user uuid)) (ffirst (find-quota-for-user uuid))))

(defn has-requests-left [uuid]
  (let [quota (seq (first (find-quota-for-user uuid)))
        hourly (first quota)
        dayly (first (rest quota))]
    (do
      (println "last hours requests: " (count (find-requests-for-user-in-last-hour uuid)))
      (println "hourly limit: " hourly)
;      (println "last days requests: " (count (find-requests-for-user-in-last-day uuid)))
      (println "dayly limitL " dayly)
      true
      )))

(defn find-all-users []
  (d/q '[:find ?user-id ?user-name ?uuid ?hourly ?dayly
         :where
         [?user-id :user/name ?user-name]
         [?user-id :user/uuid ?uuid]
         [?user-id :user/maxPerHour ?hourly]
         [?user-id :user/maxPerDay ?dayly]
         ]
         (d/db conn)))

(defn find-user [uuid]
  (d/q '[:find ?user-id ?user-name ?uuid ?hourly ?dayly
         :in $ ?uuid
         :where
         [?user-id :user/name ?user-name]
         [?user-id :user/uuid ?uuid]
         [?user-id :user/maxPerHour ?hourly]
         [?user-id :user/maxPerDay ?dayly]
         ]
       (d/db conn)
       uuid))

(defn _construct-request [[request-id request-url]]
  (let
      [new-request {
                    :id request-id
                    :url request-url
                    }]
    new-request))

(defn _construct-user [[user-id user-name uuid hourly dayly]]
  (let 
      [new-user {
;                 :db-id user-id
                 :id uuid
                 :name user-name
                 :maxPerHour hourly
                 :maxPerDay dayly
                 }]
    new-user))

(defn construct-all-users []
  (cheshire/generate-string (map _construct-user (find-all-users)))
)

(defn get-user [id]
  (cheshire/generate-string (_construct-user (first (find-user id)))))

(def not-nil? (complement nil?))

(defn diff-helper [map1 map2 key]
  (if (= (get map1 key) (get map2 key))
    nil
    (get map2 key)))

(defn diff [map1 map2]
  (assoc {} :maxPerHour (diff-helper map1 map2 :maxPerHour) :maxPerDay (diff-helper map1 map2 :maxPerDay)))

(defn update-user [id body]
  (ensure-db)
  ;@(d/transact conn
  (let [diff-map (diff (_construct-user (first (find-user id)))
                        {:maxPerHour (get body "maxPerHour")  :maxPerDay (get body "maxPerDay")})]
    (if (and (not-nil? (get diff-map :maxPerHour)) (not-nil? (get diff-map :maxPerDay)))
      @(d/transact conn
                  [{:db/id [:user/uuid id] :user/maxPerHour (bigint (get diff-map :maxPerHour))}
                   {:db/id [:user/uuid id] :user/maxPerDay (bigint (get diff-map :maxPerDay))}])
      (if (not-nil? (get diff-map :maxPerHour))
        @(d/transact conn [{:db/id [:user/uuid id] :user/maxPerHour (bigint (get diff-map :maxPerHour))}])
        (if (not-nil? (get diff-map :maxPerDay))
          @(d/transact conn [{:db/id [:user/uuid id] :user/maxPerDay (bigint (get diff-map :maxPerDay))}]))))))

(defn update-user-only-body [body]
  (update-user (get body "uuid") body))
                 
(defn delete-user [id]
  )

(defn get-all-requests []
  (cheshire/generate-string (map _construct-request (find-all-requests))))
  

(defn insert-new-user [body]
  (ensure-db)
  (add-new-user (get body "name")
                (get body "uuid")
                (bigint (get body "maxPerHour"))
                (bigint  (get body "maxPerDay")))
  (get-user (get body "uuid"))
)

;;;;;;; PROXY SECTION ;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; PROXY SHIZZLE
(def base-url "http://jsonplaceholder.typicode.com")

(defn proxy-get
  "This function will proxy the rest api call to where it has to go"
  [url-suffix]
  (do
    (ensure-db)
    (if (has-requests-left (get-uuid-for-logged-in-user nil))
      (do
        (add-user-request (get-uuid-for-logged-in-user "jonathan") url-suffix)
        (client/get (str base-url "/" url-suffix)))
      (do
        "OUT OF REQUESTS")
      )))

;;;;;;; COMPOJURE SECTION ;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defroutes app-routes
  (GET "/" [] "Hello World")
  (GET "/reset" [] (reset-database))
  (GET "/requests" []
       (do
         (ensure-db)
         (get-all-requests)
         ))
  (GET "/users" [] (do
                     (ensure-db)
                     (construct-all-users)))
  (POST "/users" {body :body} (insert-new-user body))
  (PUT "/users" {body :body} (update-user-only-body body))
  (context "/users/:id" [id] (defroutes user-routes
      (GET "/" [] (do
                    (ensure-db)
                    (get-user id)))
      (PUT "/" {body :body} (update-user id body))
      (DELETE "/" [] (delete-user id))))
  (GET "/posts" [] (proxy-get "posts"))
  (GET "/albums" [] (proxy-get "albums"))
  (GET "/todos" [] (proxy-get "todos"))
  (POST "/" 
        {:keys [headers params body] :as request}  
        (str headers params body))
  (route/not-found "Not Found"))

(def app
      (-> (handler/api app-routes)
        (middleware/wrap-json-body)
        (middleware/wrap-json-response)))


;;;;;;;; END OF COMPOJURE ;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
