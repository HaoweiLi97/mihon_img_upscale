FROM python:3.10-slim

ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update -qq \
    && apt-get install -y -qq --no-install-recommends build-essential clang libc++1 libgomp1 libatomic1 \
    && rm -rf /var/lib/apt/lists/* \
    && pip install --no-cache-dir \
        numpy==1.26.4 \
        onnx==1.17.0 \
        packaging==24.0 \
        pandas==2.3.3 \
        pyyaml==6.0.3 \
        tabulate==0.9.0

COPY convert_models.sh /usr/local/bin/convert-qnn-models
COPY compile_model_libs.sh /usr/local/bin/compile-qnn-model-libs
COPY generate_contexts.sh /usr/local/bin/generate-qnn-contexts
RUN chmod 755 \
    /usr/local/bin/convert-qnn-models \
    /usr/local/bin/compile-qnn-model-libs \
    /usr/local/bin/generate-qnn-contexts

ENV PYTHONPATH=/qnn/lib/python
ENV LD_LIBRARY_PATH=/qnn/lib/x86_64-linux-clang
