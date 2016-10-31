(ns google-civic.core
  (:require [clj-http.client :as http]
            [cheshire.core :as json]
            [clojure.set :as set]
            [robert.bruce :as bruce]))

(def api-root "https://www.googleapis.com/civicinfo/v2")

(def temporary-rate-limit-reasons
  #{"concurrentLimitExceeded" "rateLimitExceeded"
    "servingLimitExceeded" "userRateLimitExceeded"})

(defn temporary-rate-limit?
  "True if the errors in the response only include errors from the
  temporary-rate-limit-reasons set and the code is 403."
  [response]
  (and (= 403 (:status response))
       (let [errors (get-in response [:body :error :errors])
             reasons (->> errors (map :reason) set)]
         (and (seq reasons)
              (set/superset? temporary-rate-limit-reasons
                             reasons)))))

(defn can-retry?
  "We can retry if the response code indicates a server error, or
   if the error is deemed a temporary rate limit."
  [response]
  (cond
    (http/server-error? response) true
    (temporary-rate-limit? response) true
    :else
    false))

(defn api-url [endpoint]
  (str api-root endpoint))

(defn api-response [api-key endpoint & [query-params]]
  (let [url (api-url endpoint)
        response
        (http/get url {:accepts :json
                       :throw-exceptions false
                       :query-params (merge {:key api-key} query-params)})
        json-body (json/parse-string (:body response) true)]
    (assoc response :body json-body)))

(defn api-req
  ([api-key endpoint]
   (api-req api-key endpoint nil))
  ([api-key endpoint query-params]
   (api-req api-key endpoint query-params nil))
  ([api-key endpoint query-params retry-config]
   (let [config (if (seq retry-config)
                        (assoc retry-config :return? (complement can-retry?))
                        {:tries 1})]
     (bruce/try-try-again config api-response api-key endpoint query-params))))

(defn elections
  "Retrieve election data from the Civic Info API with the given api-key.

  The two-arity version adds a retry-config param that will be used to
  configure backoff retries as per the `robert-the-bruce` library."
  ([api-key]
   (elections nil))
  ([api-key retry-config]
   (let [response (api-req api-key "/elections" nil retry-config)]
     (if-let [elections (:elections response)]
       elections
       (:body response)))))

(defn voter-info
  "Retrieve voter-info data from the Civic Info API with the given api-key
  and address string, which should just be a space separated string of the
  address components, e.g. '123 Main St Denver CO 80204'.

  The three-arity version adds an optional google-election-id which can help
  with getting better results when it's available.

  The four-arity version adds a retry-config param that will be used to
  configure backoff retries as per the `robert-the-bruce` library."
  ([api-key addr]
   (voter-info api-key addr nil))
  ([api-key addr election-id]
   (voter-info api-key addr election-id nil))
  ([api-key addr election-id retry-config]
   (let [query-params {:address addr}
         query-params (if election-id
                        (merge query-params {:electionId election-id})
                        query-params)
         response (api-req api-key "/voterinfo" query-params retry-config)
         voter-info (dissoc response :kind)]
     (if (empty? voter-info)
       (:body response)
       voter-info))))
