FROM node:22.23.2-bookworm-slim@sha256:83f487e0a63425e5b4d146fb5e5be574bcbe1b7b843d3ebafdd95eaf7767a7e5 AS build

WORKDIR /app

COPY apps/web/package.json apps/web/package-lock.json ./
RUN npm ci
COPY apps/web/ .

ARG TIEAT_API_ORIGIN
RUN TIEAT_API_ORIGIN="$TIEAT_API_ORIGIN" NODE_ENV=production npm run build

FROM gcr.io/distroless/nodejs22-debian13:nonroot@sha256:4e4fb0ce55fd73901600796ef079a9490369d2515d7da31633a91608c82ca13b AS runtime

ENV NODE_ENV=production
ENV HOSTNAME=0.0.0.0
ENV PORT=3000
ENV NODE_OPTIONS="--import=/app/qr-log-redaction.mjs"
WORKDIR /app

COPY --from=build --chown=65532:65532 /app/.next/standalone ./
COPY --from=build --chown=65532:65532 /app/.next/static ./.next/static
COPY --from=build --chown=65532:65532 /app/public ./public
COPY --from=build --chown=65532:65532 /app/qr-log-redaction.mjs ./qr-log-redaction.mjs
COPY --chown=65532:65532 infra/azure/set-min-replicas.mjs ./set-min-replicas.mjs

USER 65532:65532
EXPOSE 3000
CMD ["server.js"]
