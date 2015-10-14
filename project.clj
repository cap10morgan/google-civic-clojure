(defproject google-civic "1.0.1"
  :description "Google Civic Info API client"
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [clj-http "2.0.0"]
                 [cheshire "5.5.0"]
                 [slingshot "0.12.2"]]
  :profiles {:dev {:dependencies [[clj-http-fake "1.0.1"]]}})
