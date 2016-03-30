 (defproject user-throttler "0.1.0-SNAPSHOT"
  :description "Proxy Service that returns rest API calls based on how many times a certain user can access certain resources in a certain timeframe."
  :url "http://www.github.com/mu-semtech/user-throttler"
  :min-lein-version "2.0.0"
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [compojure "1.4.0"]
                 [ring/ring-defaults "0.1.5"]
                ; [c3p0/c3p0 "0.9.1.2"]
                ; [org.clojure/java.jdbc "0.2.3"]
                 ;[com.h2database/h2 "1.3.168"]
                 [cheshire "5.5.0"]
                 [com.datomic/datomic-free "0.9.5350"]
                 [ring/ring-json "0.4.0"]
                 [clj-http "2.1.0"]
                 [datomic-schema "1.3.0"]
                 [expectations "2.1.4"]]
  :plugins [[lein-ring "0.9.7"]
            [lein-autoexpect "1.8.0"]]
  :ring {:handler user-throttler.handler/app}
  :profiles
  {:dev {:dependencies [[javax.servlet/servlet-api "2.5"]
                        [ring/ring-mock "0.3.0"]]}})
