(defproject google-civic "2.1.0-SNAPSHOT"
  :description "Google Civic Info API client"
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [clj-http "3.3.0"]
                 [cheshire "5.6.3"]
                 [robert/bruce "0.8.0"]]
  :deploy-repositories {"releases" :clojars}
  :profiles {:dev {:dependencies [[clj-http-fake "1.0.2"]]}})
