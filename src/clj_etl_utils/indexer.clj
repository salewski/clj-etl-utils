(ns ^{:doc "Indexing functions for working with delimited and fixed
    width files in situ, allowing them to be searched and iterated
    through in other than natural order, without having to load the
    data into a database."
      :author "Kyle Burton"}
  clj-etl-utils.indexer
  (:require [clojure.contrib.duck-streams :as ds]
            [clojure.contrib.shell-out    :as sh]
            [clj-etl-utils.sequences      :as sequences]
            [clj-etl-utils.io             :as io]
            [clojure.java.io              :as cljio])
  (:import
   [java.io RandomAccessFile FileInputStream InputStreamReader BufferedReader]
   [org.apache.commons.io.input BoundedInputStream]))

;; index line oriented files

;; TODO: convention for escaping the key (URI? - it could contain a
;; tab character...), handling null or empty values, when the key fn
;; throws an exception, etc.

;; TODO: consider updating or refreshing - incrementally, the index files

(defn line-position-seq [#^RandomAccessFile fp]
  (let [start-pos (.getFilePointer fp)
        line      (.readLine fp)
        end-pos   (.getFilePointer fp)]
    (if (nil? line)
      (do (.close fp)
          nil)
      (lazy-cat
       [[line start-pos end-pos]]
       (line-position-seq fp)))))

(defn line-index-seq [#^RandomAccessFile fp key-fn]
  "Given a random access file (need not be positioned at the start)
and a key function (run on the line to compute the keys for the line)
this will return a sequence of:

   ([[key-value ...] line-start-pos line-end-pos] ...)

For all the lines in the file.

"
  (pmap (fn [[line start-pos end-pos]]
          [(key-fn line) start-pos end-pos])
        (line-position-seq fp)))

(comment

  (take 10 (line-index-seq
            (RandomAccessFile. "/home/superg/data/citi/relay_incremental_9_2010-10k-sample.rpt.fix" "r")
            (fn [line]
              [(str (.charAt line 0))])))

  )

;; returns a sequnce of [key line-start-byte-pos line-endbyte-pos]
;; given a key-fn that takes a line of text and returns a string key that represents the line.

(defn file-index-seq [#^String file #^IFn key-fn]
  (line-index-seq  (RandomAccessFile. file "r") key-fn))

(defn extract-range [#^RandomAccessFile fp start end]
  (.seek fp start)
  (let [data-bytes (byte-array (- end start))]
    (.read fp data-bytes)
    (String. data-bytes)))

;; NB: decide on sort behavior - string collation or numeric?  we're
;; going to shell out to GNU sort for this so that is a concern...
(defn create-index-file [#^String input-file #^String index-file #^IFn key-fn]
  ;; run the indexer (seq), emit to index-file
  ;; sort index-file
  (with-open [outp (ds/writer index-file)]
    (loop [[[kvals start end] & vals] (file-index-seq input-file key-fn)]
      (if (or (nil? kvals)
              (empty? kvals))
        true
        (do
          (doseq [val kvals]
            (.println outp (format "%s\t%s\t%s" val start end)))
          (recur vals))))))

;; NB: return value isn't taking into account error statuses
;; NB: will not work on platforms that don't have sort and mv, fix this...
(defn sort-index-file [#^String index-file]
  (let [tmp    (java.io.File/createTempFile "idx-srt" "tmp")
        tmpnam (.getName tmp)]
    (sh/sh "sort" "-o" tmpnam index-file
           :env {"LANG" "C"})
    (sh/sh "mv" tmpnam index-file))
  true)

(defn index-file! [#^String input-file #^String index-file #^IFn key-fn]
  (create-index-file input-file index-file key-fn)
  (sort-index-file index-file))


(comment

  (index-file! "file.txt" ".file.txt.id-idx" #(first (.split % "\t")))

  )

;; TODO: this is splitting multiple times, rework to only split 1x
(defn index-blocks-seq [#^String index-file]
  (map (fn [grp]
         (map (fn [l]
                (let [[val spos epos] (.split l "\t")]
                  [val (Integer/parseInt spos) (Integer/parseInt epos)]))
              grp))
       (sequences/group-with (fn [l]
                               (first (.split l "\t")))
                             (ds/read-lines index-file))))

;; This is the new form of above (only call split 1x), needs to be tested
#_(defn index-blocks-seq [#^String index-file]
    (sequences/group-with
     first
     (map
      (fn [l]
        (let [[val spos epos] (.split l "\t")]
          [val (Long/parseLong spos) (Long/parseLong epos)]))
      (ds/read-lines index-file))))



(comment
  (index-blocks-seq ".file.txt.id-idx")
  ((["1" 24 48]) (["2" 48 65]) (["3" 65 88]) (["99" 0 24] ["99" 88 115]))
  )


(defn records-for-idx-block #^String [inp-file idx-block]
  (loop [recs []
         [[k start-pos end-pos] & idx-block] idx-block]
    (if (not k)
      recs
      (recur
       ;; NB: range should be 1 and only 1 line/record
       (conj recs (first (vec (io/read-lines-from-file-segment inp-file start-pos end-pos))))
       idx-block))))

(comment

  (records-for-idx-block "file.txt" [["99" 0 24] ["99" 88 115]])

  )

;; TODO: building an index has to stream the records, if we are to
;; build N indicies we will have to stream the records N times, modify
;; the implementation such that we can create multiple indicies by
;; streaming only one time...

(defn record-blocks-via-index [#^String inp-file #^String index-file]
  "Given an data file and an index file, this stream through the distinct
index values returning records from the data file."
  (map (partial records-for-idx-block inp-file)
       (index-blocks-seq index-file)))

;;   ((["1" 24 48]) (["2" 48 65]) (["3" 65 88]) (["99" 0 24] ["99" 88 115]))

(comment

  (record-blocks-via-index "file.txt" ".file.txt.id-idx")

  )

;; 1 MB
(def min-streaming-threshold (* 1024 1024 1))

;; matcher = (fn [index-val term]) => bool
(defn index-search
  ([idx-file term]
     (index-search idx-file term =))
  ([idx-file term matcher]
     (with-open [rdr (cljio/reader idx-file)]
       (index-search idx-file term matcher rdr)))
  ([idx-file term matcher rdr]
     (loop [line (.readLine rdr)
            res  []]
       (if (nil? line)
         res
         (let [[v s e] (.split line "\t" 3)
               direction (compare v term)]
           (cond
             (matcher v term)
             (recur (.readLine rdr)
                    (conj res [v (Long/parseLong s) (Long/parseLong e)]))

             (pos? direction)
             (do
               #_(println (format "direction was positive, indicating we've gone past: (compare \"%s\" \"%s\") %d"
                                v term direction))
               res)

             :continue
             (recur (.readLine rdr) res)))))))

;; NB: this only works with \n (byte value 10) as a line separator
(defn rewind-to-newline [^RandomAccessFile fp min-pos]
  #_(println "rewind-to-newline: remove the max iters")
  (loop [] ;; max-iters 10000
    (let [b  (.readByte fp)
          ch (Byte/toString b)]
      (cond
        ;; (<= max-iters 0)
        ;; (raise "Too much recursion")

        (= (.intValue b) 10)
        (do
          #_(println (format "rewind-to-newline: Found newline at %d" (.getFilePointer fp)))
          true)

        (<= (.getFilePointer fp) min-pos)
        (do
          #_(println (format "rewind-to-newline: Rewound to start"))
          (.seek fp 0)
          true)

        :rewind
        (do
          #_(println (format "rewind-to-newline: [%d/%s] Did not find newline at %d, going back to %d"
                           (.intValue b)
                           ch
                           (.getFilePointer fp)
                           (- (.getFilePointer fp) 2)))
          (.seek fp (- (.getFilePointer fp) 2))
          ;; (recur (dec max-iters))
          (recur))))))


;; spos must point at either the start of the file, or the beginning of a line
;; epos must point at either the end of the file, or a newline
(defn index-search-prefix-impl [^String idx-file ^String term spos epos]
  #_(println (format "index-search-prefix-impl %s %s %d %d"
                   idx-file term spos epos))
  (if (<= (- epos spos) min-streaming-threshold)
    (with-open [rdr (BufferedReader.
                     (InputStreamReader.
                      (BoundedInputStream.
                       (doto (FileInputStream. idx-file)
                         (.skip spos))
                       (- epos spos))))]
      #_(println (format "before binary search, spos=%d to epos=%d under THRESH, falling back to streaming search" spos epos))
      (index-search idx-file
                    term
                    (fn [idx-val term]
                      (.startsWith idx-val term))
                    rdr))
    (with-open [fp (RandomAccessFile. idx-file "r")]
      (loop [;;max-iters 25
             spos   spos
             epos   epos
             middle (long (/ (- epos spos) 2))]
        #_(println (format "loop: spos=%d epos=%d middle=%d" spos epos middle))
        ;; (when (<= max-iters 0)
        ;;   (raise "too much recursion"))
        (.seek fp middle)
        (rewind-to-newline fp spos)
        (let [middle  (.getFilePointer fp)
              line    (.readLine fp)
              [iterm bstart bend] (.split line "\t" 3)
              order   (compare term iterm)]
          #_(println (format "Looking at[%d] line=%s"
                           (.getFilePointer fp)
                           line))
          (cond
            (<= (- epos spos) min-streaming-threshold)
            (with-open [rdr (BufferedReader.
                             (InputStreamReader.
                              (BoundedInputStream.
                               (doto (FileInputStream. idx-file)
                                 (.skip spos))
                               (- epos spos))))]
              #_(println (format "in binary search, spos=%d to epos=%d under THRESH, falling back to streaming search" spos epos))
              (index-search
               idx-file
               term
               (fn [idx-val term]
                 #_(println (format "(.startsWith \"%s\" \"%s\") => %s"
                                  idx-val term (.startsWith idx-val term)))
                 (.startsWith idx-val term))
               rdr))

            (neg? order)
            (do
              #_(println (format "order was: %d, go left" order))
              (recur ;; (dec max-iters)
               spos
               middle
               (long (- middle  (/ (- middle spos) 2)))))

            (zero? order)
            (do
              #_(println (format "order was: %d, we're in the block, need to find the start" order)))

            (pos? order)
            (do
              #_(println (format "order was: %d, go right" order))
              (recur
               ;; (dec max-iters)
               middle
               epos
               (long (- epos  (/ (- epos middle) 2)))))))))))


(defn index-search-prefix [^String idx-file ^String term]
  ;; binary search the file
  ;; open a RandomAccessFile
  ;; start=0 end=LEN
  ;; if (- end len) < THRESH, just stream through the section
  ;; jump to the middle, rewind to '\n'
  (let [epos (.length (java.io.File. idx-file))]
    (if (<= epos min-streaming-threshold)
      (do
        #_(println (format "file size %d < thresh %d, falling back to streaming"
                         epos min-streaming-threshold))
        (index-search idx-file term #(= %1 %2)))
      (index-search-prefix-impl idx-file term 0 epos))))


(comment

 (index-search-prefix
  "tmp/.free-zipcode-database.csv.city-idx"
  "NEW PHILA")

 (defn sort-compatible-compare [s1 s2]
   (let [len (min (count s1) (count s2))]
     (loop [idx 0]
       (cond
         (= idx len)
         0
         :compare-chars
         (let [c1 (.charAt s1 idx)
               c2 (.charAt s1 idx)])))))

 (compare "A" " ")

 (compare "NEWAGEN" "NEW PHILA")


 (.compareTo "NEWAGEN" "NEW PHILA")



 )


;; TODO: take the result of index-search and look up the records out of the original file

;; TODO: Implement mutiple index block streaming (grouping across
;; mutiple data files to create candidate clusters).

(comment


  (index-file! "file.txt" ".file.txt.id-idx" (fn [line] [(first (.split line "\t"))]))
  (record-blocks-via-index "file.txt" ".file.txt.id-idx")


  )

