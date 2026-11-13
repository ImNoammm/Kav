# kav-translate: Hebrew to English for the line alerts (server/translate.py), Helsinki-NLP's opus-mt-tc-big-he-en
# (CC-BY 4.0) on CTranslate2, answering LibreTranslate's /translate. Built from the repository's root:
# docker build -f server/selfhost/translate/Dockerfile .
# The model is converted once here, at a pinned revision, so the image runs with no download and no PyTorch.
FROM python:3.12-slim AS convert
RUN pip install --no-cache-dir torch==2.15.0 --index-url https://download.pytorch.org/whl/cpu && \
    pip install --no-cache-dir ctranslate2==4.6.0 transformers==5.22.2 sentencepiece==0.2.3
RUN ct2-transformers-converter --model Helsinki-NLP/opus-mt-tc-big-he-en \
    --revision 134c5a850dcaa763eec85bd1f4eb25112fecedbb --output_dir /model --quantization int8 \
    --copy_files source.spm target.spm README.md

FROM python:3.12-slim
ENV PYTHONDONTWRITEBYTECODE=1
RUN pip install --no-cache-dir ctranslate2==4.6.0 sentencepiece==0.2.3
COPY --from=convert /model /model
COPY server/translate.py /app/
USER 1000:1000
CMD ["python3", "-u", "/app/translate.py"]
