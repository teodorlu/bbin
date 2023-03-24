(ns babashka.bbin.scripts.http-jar
  (:require [babashka.bbin.protocols :as p]
            [babashka.bbin.scripts.common :as common]
            [babashka.bbin.util :as util]
            [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [org.httpkit.client :as http]
            [selmer.parser :as selmer]
            [selmer.util :as selmer-util]))

(def ^:private local-jar-template-str
  (str/trim "
#!/usr/bin/env bb

; :bbin/start
;
{{script/meta}}
;
; :bbin/end

(require '[babashka.classpath :refer [add-classpath]])

(def script-jar {{script/jar|pr-str}})

(add-classpath script-jar)

(require '[{{script/main-ns}}])
(apply {{script/main-ns}}/-main *command-line-args*)
"))

(defrecord HttpJar [cli-opts]
  p/Script
  (install [_]
    (fs/create-dirs (util/jars-dir cli-opts))
    (let [http-url (:script/lib cli-opts)
          script-deps {:bbin/url http-url}
          header {:coords script-deps}
          script-name (or (:as cli-opts) (common/http-url->script-name http-url))
          tmp-jar-path (doto (fs/file (fs/temp-dir) (str script-name ".jar"))
                         (fs/delete-on-exit))
          _ (io/copy (:body @(http/get http-url {:as :byte-array})) tmp-jar-path)
          main-ns (common/jar->main-ns tmp-jar-path)
          cached-jar-path (fs/file (util/jars-dir cli-opts) (str script-name ".jar"))
          _ (fs/move tmp-jar-path cached-jar-path {:replace-existing true})
          _ (util/pprint header cli-opts)
          script-edn-out (with-out-str
                           (binding [*print-namespace-maps* false]
                             (util/pprint header)))
          template-opts {:script/meta (->> script-edn-out
                                           str/split-lines
                                           (map #(str common/comment-char " " %))
                                           (str/join "\n"))
                         :script/main-ns main-ns
                         :script/jar cached-jar-path}
          script-contents (selmer-util/without-escaping
                            (selmer/render local-jar-template-str template-opts))
          script-file (fs/canonicalize (fs/file (util/bin-dir cli-opts) script-name)
                                       {:nofollow-links true})]
      (common/install-script script-file script-contents (:dry-run cli-opts))))

  (uninstall [_]
    (common/delete-files cli-opts)))