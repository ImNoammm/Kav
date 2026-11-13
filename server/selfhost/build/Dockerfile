# kav-build: Python for prepare.py and the steps after it, at the version Kav's own server runs them with, pyosmium and
# the expat library it loads (in the full Python image, not this slim one) for walks.py and area.py, OpenSSL to sign the
# app's timetable, and OpenTripPlanner with its Java exactly as the planner's own image has them. Built from the
# repository's root: docker build -f server/selfhost/build/Dockerfile .
FROM opentripplanner/opentripplanner:2.10.0 AS otp

FROM python:3.12-slim
RUN apt-get update && apt-get install -y --no-install-recommends openssl libexpat1 && rm -rf /var/lib/apt/lists/*
RUN pip install --no-cache-dir osmium==4.3.2
COPY --from=otp /opt/java/openjdk /opt/java/openjdk
COPY --from=otp /app /app
ENV JAVA_HOME=/opt/java/openjdk PATH=/opt/java/openjdk/bin:$PATH PYTHONDONTWRITEBYTECODE=1
COPY server/otp /kav/server/otp
COPY tools/export_web_bundle.py /kav/tools/export_web_bundle.py
COPY server/selfhost/build/build.py /kav/build.py
USER 1000:1000
CMD ["python3", "-u", "/kav/build.py"]
