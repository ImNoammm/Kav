#!/bin/sh
# Compiles Kav's linking patch (src/: four classes of OpenTripPlanner v2.10.0 with Kav's changes, under OpenTripPlanner's
# own LGPL-3.0-or-later) against the stock opentripplanner/opentripplanner:2.10.0 image's own classes, into ./classes,
# which kav-otp puts first on its classpath (server/docker-compose.yml).
# Needs docker; takes a few seconds and under 1 GB.    sh build.sh
set -eu
cd "$(dirname "$0")"
OTP=opentripplanner/opentripplanner:2.10.0
rm -rf app classes && mkdir classes
id=$(docker create "$OTP") && docker cp "$id:/app" ./app >/dev/null && docker rm "$id" >/dev/null
docker run --rm --user "$(id -u):$(id -g)" -v "$PWD:/w" -w /w eclipse-temurin:25-jdk \
  javac -nowarn -encoding UTF-8 -proc:none -d classes -cp 'app/classes:app/libs/*' src/*.java
rm -rf app
find classes -name '*.class' | sort | xargs md5sum
