(ns google-civic.core
  (:require [clj-http.client :as client]
            [cheshire.core :as json]
            [clojure.set :as set]))

(def api-root "https://www.googleapis.com/civicinfo/v2")

(def temporary-rate-limit-reasons
  #{"concurrentLimitExceeded" "rateLimitExceeded"
    "servingLimitExceeded" "userRateLimitExceeded"})

(defn temporary-rate-limit?
  "True if the errors in the response only include errors from the
  temporary-rate-limit-reasons set and the code is 403."
  [response body]
  (and (= 403 (:status response))
       (let [errors (get-in body [:error :errors])
             reasons (->> errors (map :reason) set)]
         (and (seq reasons)
              (set/superset? temporary-rate-limit-reasons
                             reasons)))))

(defn can-retry?
  "We can retry if the response code indicates a server error, or
   if the error is deemed a temporary rate limit."
  [response body]
  (cond
    (client/server-error? response) true
    (temporary-rate-limit? response body) true
    :else
    false))

(defn api-url [endpoint]
  (str api-root endpoint))

(defn api-response [api-key endpoint & [query-params]]
  (let [url (api-url endpoint)]
    (client/get url {:accept :json
                     :throw-exceptions false
                     :query-params (merge {:key api-key} query-params)})))

(defn api-req
  ([api-key endpoint]
   (api-req api-key endpoint nil))
  ([api-key endpoint query-params]
   (api-req api-key endpoint query-params 0 nil))
  ([api-key endpoint query-params retry-limit backoff-fn]
   (loop [response (api-response api-key endpoint query-params)
          counter 0]
     (let [body (json/parse-string (:body response) true)]
       (if (client/success? response)
         body
         (if (and (< counter retry-limit)
                  (can-retry? response body))
           (do (when backoff-fn
                 (backoff-fn))
               (recur (api-response api-key endpoint query-params)
                      (inc counter)))
           (assoc response :body body)))))))

(defn elections
  "Retrieve election data from the Civic Info API with the given api-key.

  The three-arity version adds a retry-limit and a backoff-fn. In these version,
  certain error responses will result in retried, up to the retry-limit. The
  backoff-fn is a fn you provide to have a pause period between retries of your
  chosing. This is primarily intended to handle rate limit responses for going
  over instantaneous or concurrent rate limits and slow things down, so
  a variable pause of 1-3 seconds may be ideal."
  ([api-key]
   (elections 0 nil))
  ([api-key retry-limit backoff-fn]
   (let [response (api-req api-key "/elections" nil retry-limit backoff-fn)]
     (if-let [elections (:elections response)]
       elections
       (:body response)))))

(defn voter-info
  "Retrieve voter-info data from the Civic Info API with the given api-key
  and address string, which should just be a space separated string of the
  address components, e.g. '123 Main St Denver CO 80204'.

  The three-arity version adds an optional google-election-id which can help
  with getting better results when it's available.

  The five-arity version adds a retry-limit and a backoff-fn. In these version,
  certain error responses will result in retried, up to the retry-limit. The
  backoff-fn is a fn you provide to have a pause period between retries of your
  chosing. This is primarily intended to handle rate limit responses for going
  over instantaneous or concurrent rate limits and slow things down, so
  a variable pause of 1-3 seconds may be ideal."
  ([api-key addr]
   (voter-info api-key addr nil))
  ([api-key addr election-id]
   (voter-info api-key addr election-id 0 nil))
  ([api-key addr election-id retry-limit backoff-fn]
   (let [query-params {:address addr}
         query-params (if election-id
                        (merge query-params {:electionId election-id})
                        query-params)
         response (api-req api-key "/voterinfo" query-params
                           retry-limit backoff-fn)
         voter-info (dissoc response :kind)]
     (if (empty? voter-info)
       (:body response)
       voter-info))))
