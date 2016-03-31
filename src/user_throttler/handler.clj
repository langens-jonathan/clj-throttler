(ns user-throttler.handler
  (:require [compojure.core :refer :all]
            [compojure.route :as route]
;            [ring.middleware.defaults :refer [wrap-defaults site-defaults]]
            [compojure.handler :as handler]
            [ring.middleware.json :as middleware]
            ;; this is for datomic!
            [datomic.api :as d]
            [clojure.java.io :as io]
            [ring.middleware.json :refer [wrap-json-response]]
            [ring.util.response :refer [response]]
            [clj-http.client :as client]
            [cheshire.core :as cheshire]
            ;[datomic-schema.schema :refer :all]
            ))


;; PROXY SHIZZLE
(def base-url "http://jsonplaceholder.typicode.com")

(defn proxy-get
  "This function will proxy the rest api call to where it has to go"
  [url-suffix]
  (client/get (str base-url "/" url-suffix)))

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
  (add-new-user "default" "1" 10N 100N)
  (add-new-user "jonathan" "2" 100N 1000N)
  (add-new-user "sevelientje" "3" 1N 10N)
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

;;;;;;; DATOMIC SECTION ;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; creating the database
(defn find-user-id [user-name]
  (d/q '[:find ?user-id
         :in $ ?user-name
         :where
         [?user-id :user/name ?user-name]
        ]
       (d/db conn)
       user-name))

(defn add-user-request [user-name target]
  (let [access-uri (d/tempid :db.part/user)]
    @(d/transact conn [{:db/id access-uri
                        :request/url target}
                       {:db/id access-uri
                        :request/userId (ffirst (find-user-id user-name))}
                      ])))

(defn find-request-ids-for-user [user-name]
  (d/q '[:find ?request-id
         :in $ ?user-name
         :where
         [?user-id :user/name ?user-name]
         [?request-id :request/userId ?user-id]
        ]
       (d/db conn)
       user-name))

(defn find-all-requests-for-user [user-name]
  (d/q '[:find ?request-url
         :in $ ?user-name
         :where
         [?user-id :user/name ?user-name]
         [?request-id :request/userId ?user-id]
         [?request-id :request/url ?request-url]
        ]
       (d/db conn)
       user-name))

(defn find-quota-for-user [user-name]
  (d/q '[:find ?hourly ?dayly
         :in $ ?user-name
         :where 
         [?user-id :user/name ?user-name]
         [?user-id :user/maxPerHour ?hourly]
         [?user-id :user/maxPerDay ?dayly]
        ]
       (d/db conn)
       user-name))

(defn has-requests-left [user-name]
  (< (count (find-request-ids-for-user user-name)) (ffirst (find-quota-for-user user-name))))

;; (defn add-user [user-name]
;;   @(d/transact conn [{:db/id (d/tempid :db.part/user)
;;                       :user/name user-name}]))

;; (defn add-user-quota [user-name hourly]
;;   @(d/transact conn [{:db/id (ffirst (find-user-id user-name))
;;                       :user/maxPerHour (.toBigInteger hourly)}]))

;; (defn find-all-users []
;;   (d/q '[:find ?user-name
;;          :where
;;          [?user-id :user/name ?user-name]
;;          ]
;;        (d/db conn)))

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

(defn _construct-user [[user-id user-name uuid hourly dayly]]
  (let 
      [new-user {
                 :id user-id
                 :name user-name
                 :uuid uuid
                 :maxPerHour hourly
                 :maxPerDay dayly
                 }]
    new-user))

(defn construct-all-users []
  (cheshire/generate-string (map _construct-user (find-all-users)))
)

(defn get-user [id]
  (cheshire/generate-string (_construct-user (first (find-user id)))))

(defn update-user [id]
  )

(defn delete-user [id]
  )

(defn insert-new-user [body]
  (ensure-db)
  (add-new-user (get body "name")
                (get body "uuid")
                (bigint (get body "maxPerHour"))
                (bigint  (get body "maxPerDay")))
  (get-user (get body "uuid"))
)

(defn test-function [user-name]
  (let [user-list (find-all-users)]
    (println user-list)
    user-list))

;;;;;;; END OF DATOMIC ;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;;;;;;; COMPOJURE SECTION ;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defroutes app-routes
  (GET "/" [] "Hello World")
  (GET "/reset" [] (reset-database))
  (GET "/users" [] (do
                     (ensure-db)
                     (construct-all-users)))
  (POST "/users" {body :body} (insert-new-user body))
  (context "/users/:id" [id] (defroutes user-routes
      (GET "/" [] (do
                    (ensure-db)
                    (get-user id)))
      (PUT "/" {body :body} (update-user body))
      (DELETE "/" [] (delete-user id))))
  (POST "/users"
        {:keys [params]}
        (do
          (println (str params))
          (str params)))
  (GET "/posts" [] (proxy-get "posts"))
  (GET "/albums" [] (proxy-get "albums"))
  (GET "/todos" [] (proxy-get "todos"))
  (POST "/" 
        {:keys [headers params body] :as request}  
        (str headers params body))
  (route/not-found "Not Found"))

;;(def app
;;  (wrap-defaults app-routes (assoc-in site-defaults [:security :anti-forgery] false)))

;; (def app
;;   (wrap-json-response app-routes))

(def app
      (-> (handler/api app-routes)
        (middleware/wrap-json-body)
        (middleware/wrap-json-response)))


;;;;;;;; END OF COMPOJURE ;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
