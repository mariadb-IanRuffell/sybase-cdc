# GridGain 9 with the columnar storage module removed.
# The arm64 image of 9.2.0-b1 crashes at node start on Apple Silicon: its columnar native library (libcolumnarjni, musl) dies in
# init_have_lse_atomics while the storage engines load (SIGSEGV in dlopen). This project does not use columnar storage, so the jar is
# simply taken out. Drop this Dockerfile (and the build: section of docker-compose.yml) once a fixed image is released.
ARG GG_VERSION=9.2.0-b1
FROM gridgain/gridgain9:${GG_VERSION}
USER root
RUN rm -f /opt/gridgain/lib/gridgain-columnar-*.jar
USER gridgain
