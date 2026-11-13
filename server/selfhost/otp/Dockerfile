# kav-otp: OpenTripPlanner's own image, serving the graph kav-build makes and each new one it moves in
# (server/selfhost/otp-serve.sh). Built from the repository's root: docker build -f server/selfhost/otp/Dockerfile .
FROM opentripplanner/opentripplanner:2.10.0
COPY server/selfhost/otp-serve.sh /kav/otp-serve.sh
ENTRYPOINT ["/bin/bash", "/kav/otp-serve.sh"]
