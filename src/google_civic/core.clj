(ns google-civic.core
  (:require [clj-http.client :as client])
  (:require [cheshire.core :as json]))

(def api-root "https://www.googleapis.com/civicinfo/us_v1")

(defn- api-url [api-key endpoint]
  (str api-root endpoint "?key=" api-key))

(defn- api-response [api-key endpoint & [post-data]]
  (let [url (api-url api-key endpoint)]
    (if post-data
      (client/post url {:body (json/generate-string post-data)
                        :content-type :json})
      (client/get url {:accept :json}))))

(defn- api-req
  ([api-key endpoint & [post-data]]
    (json/parse-string (:body (api-response api-key endpoint post-data)) true)))

(defn elections [api-key]
  (:elections (api-req api-key "/elections")))

(defn voter-info [api-key election-id addr]
  (let [post-data {:address addr}]
    (select-keys
      (api-req api-key (str "/voterinfo/" election-id "/lookup") post-data)
      [:election :normalizedInput :state])))

