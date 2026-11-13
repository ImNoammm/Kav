# What the arrival model service needs besides Python, at the versions the lab built the models with; LightGBM (the
# train and light-rail models) needs OpenMP's runtime. The code is copied in (kav-eta's image for server/selfhost);
# Kav's own server mounts its own copy over it.
FROM python:3.12-slim
RUN apt-get update && apt-get install -y --no-install-recommends libgomp1 && rm -rf /var/lib/apt/lists/*
RUN pip install --no-cache-dir polars==2.0.0 numpy==2.5.4 lightgbm==4.7.0
COPY eta.py inputs.py motion.py rail_inputs.py /app/
ENV PYTHONDONTWRITEBYTECODE=1
