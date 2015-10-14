(ns google-civic.core
  (:require [clj-http.client :as client]
            [cheshire.core :as json]
            [slingshot.slingshot :refer [try+]]))

(def api-root "https://www.googleapis.com/civicinfo/v2")

(defn api-url [endpoint]
  (str api-root endpoint))

(defn api-response [api-key endpoint & [query-params]]
  (let [url (api-url endpoint)]
    (client/get url {:accept :json
                     :query-params (merge {:key api-key} query-params)})))

(defn api-req
  ([api-key endpoint & [query-params]]
   (try+
     (let [response (api-response api-key endpoint query-params)]
       (json/parse-string (:body response) true))
     (catch Object response
       (merge response {:body (json/parse-string (:body response) true)})))))

(defn elections [api-key]
  (let [response (api-req api-key "/elections")]
    (if-let [elections (:elections response)]
      elections
      (:body response))))

(defn voter-info [api-key addr & [election-id]]
  (let [query-params {:address addr}
        query-params (if election-id
                       (merge query-params {:electionId election-id})
                       query-params)
        response (api-req api-key (str "/voterinfo") query-params)
        voter-info (select-keys response
                                [:election :normalizedInput :contests :state])]
    (if (empty? voter-info)
      (:body response)
      voter-info)))
