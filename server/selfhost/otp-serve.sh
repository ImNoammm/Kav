#!/bin/bash
# kav-otp: serves the planner (OpenTripPlanner, in its own image) from the graph kav-build makes, and again from each new
# one it moves in; until then the old one answers. Nothing to serve until the first is built.
graph=/var/opentripplanner/graph.obj
pid= nap=
# Stopped, the planner stops too, and the wait in hand with it.
trap 'kill $pid $nap 2>/dev/null; [ -n "$pid" ] && wait $pid; exit 0' TERM INT
nap() { sleep "$1" & nap=$!; wait $nap; }
[ -s $graph ] || echo "waiting for kav-build's first planner"
until [ -s $graph ]; do nap 60; done
while true; do
  seen=$(stat -c %i.%Y $graph)
  java $JAVA_OPTS -cp @/app/jib-classpath-file @/app/jib-main-class-file /var/opentripplanner/ --load --serve &
  pid=$!
  while kill -0 $pid 2>/dev/null && [ "$(stat -c %i.%Y $graph 2>/dev/null)" = "$seen" ]; do nap 30; done
  kill $pid 2>/dev/null
  wait $pid
  echo "$(date -Is) starting the planner again"
  nap 5
done
