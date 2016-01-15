# onyx-console-dashboard

A Clojure library designed to ... well, that part is up to you.

## Usage

1. Install boot following these [instructions](https://github.com/boot-clj/boot#install).
2. Download [dashboard script](https://raw.githubusercontent.com/onyx-platform/onyx-console-dashboard/master/onyx-dashboard).
3. `chmod +x onyx-dashboard`
4. ./onyx-dashboard

Command line parameters:

```
-s src: input type: jepsen, zookeeper, edn
-e edn: edn input filename for use in combination with jepsen and edn src.
-f filter: filter changes in the replica by keys/values that match this exact string.
-o onyx-version: pull in a specific version of Onyx to play log against e.g. 0.8.4-SNAPSHOT.
```

## License

Copyright © 2015 FIXME

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
