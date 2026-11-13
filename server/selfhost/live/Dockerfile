# kav-live: the ministry's buses in memory and everything the app asks for under /live/v1/ (server/live.py), with
# Python's own library only, and the intermediate certificate the ministry's servers leave out. Built from the
# repository's root: docker build -f server/selfhost/live/Dockerfile .
FROM python:3.12-slim
COPY server/live.py server/accounts.py server/alerts.py /app/
COPY server/selfhost/mot-ca.crt /app/mot-ca.pem
ENV PYTHONDONTWRITEBYTECODE=1
# Here, so its tools run by name: docker compose exec live python3 accounts.py ...
WORKDIR /app
USER 1000:1000
CMD ["python3", "-u", "/app/live.py"]
