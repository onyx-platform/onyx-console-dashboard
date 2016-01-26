# onyx-console-dashboard

A tool to view the Onyx log and replica changes over time.

## Usage

1. Install boot following these [instructions](https://github.com/boot-clj/boot#install).
2. Download [dashboard script](https://raw.githubusercontent.com/onyx-platform/onyx-console-dashboard/master/onyx-dashboard).
3. `chmod +x onyx-dashboard`
4. ./onyx-dashboard

Command line parameters:

```
Options:
  -h, --help                            Print this help info.
  -s, --src TYPE                        Set input type: jepsen, zookeeper, edn to TYPE.
  -z, --zookeeper ZOOKEEPERADDR         Set zookeeper address, required when src is zookeeper to ZOOKEEPERADDR.
  -j, --job-scheduler JOBSCHEDULERTYPE  Set job scheduler, either onyx.job-scheduler/greedy or onyx.job-scheduler/balanced to JOBSCHEDULERTYPE.
  -e, --edn PATH                        Set input filename to PATH.
  -f, --filter FILTER                   Set only show replicas that change by the supplied value to FILTER.
  -o, --onyx-version ONYXVERSION        Set onyx dependency version to use to play the log to ONYXVERSION.
```

Interface keys:

*Navigation*

```
Up: previous log entry
Down: next log entry
Page Up: -10 log entries
Page Down: +10 log entries
j: scroll down
k: scroll up
```

*Modes*
```
d: replica diff mode
r: replica mode
q: quit
escape: quit
```

## License

Copyright © 2015 FIXME

Distributed under the Eclipse Public License either version 1.0 or (at your option) any later version.
