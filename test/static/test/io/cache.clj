(ns static.test.io.cache
  (:require [clojure.test :refer :all]
            [static.io.cache :refer :all]))

(defn clear-cache-fixture [f]
  (reset! file-cache (new-file-cache))
  (f)
  (reset! file-cache (new-file-cache)))

(use-fixtures :once clear-cache-fixture)

(deftest test-cache
  (testing "basic cache hit and miss functionality"
    (let [call-counter (atom 0)
          f (fn [x] (swap! call-counter inc) (str "calc " x))]
      (is (= 0 @call-counter))
      (is (= "calc foo" (read-cached-file! "foo" f)))
      (is (= 1 @call-counter))
      (read-cached-file! "foo" f)
      (is (= 1 @call-counter) "generator function should not get called.")
      (invalidate-cache! "foo")
      (read-cached-file! "foo" f)
      (is (= 2 @call-counter))))
  (let [call-counter-f (atom 0)
        f (fn [x] (swap! call-counter-f inc) (str "calc " x))
        call-counter-g (atom 0)
        g (fn [x] (swap! call-counter-g inc) (str "calc " x))]
    (testing "partitioning"
      (is (= 0 @call-counter-f))
      (is (= 0 @call-counter-g))
      (read-cached-file! "file.txt" f)
      (is (= 1 @call-counter-f))
      (is (= 0 @call-counter-g))
      (read-cached-file! "file.txt" g)
      (is (= 1 @call-counter-f))
      (is (= 1 @call-counter-g))
      (read-cached-file! "file2.txt" g)
      (is (= 2 @call-counter-g)))
    (testing "partition-aware invalidation"
      (invalidate-cache! "file.txt")
      (read-cached-file! "file2.txt" g)
      (is (= 2 @call-counter-g) "no miss expected")
      (read-cached-file! "file.txt" f)
      (read-cached-file! "file.txt" g)
      (is (= 2 @call-counter-f))
      (is (= 3 @call-counter-g)))))
