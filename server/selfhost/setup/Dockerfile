# kav-setup: the setup page (server.py and pages.py, with the accounts kav-live shares), with Python and Docker's own
# client and Compose to start the rest. An update pulls a new image of it. Built from the repository's root:
# docker build -f server/selfhost/setup/Dockerfile .
FROM docker:27-cli
RUN apk add --no-cache python3 openssl tzdata
COPY server/accounts.py server/conformance.py /app/
COPY server/selfhost/setup /app/
ENV PYTHONDONTWRITEBYTECODE=1
CMD ["python3", "-u", "/app/server.py"]
