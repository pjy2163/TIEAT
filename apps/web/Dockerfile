FROM node:22.23.2-bookworm-slim@sha256:83f487e0a63425e5b4d146fb5e5be574bcbe1b7b843d3ebafdd95eaf7767a7e5 AS build

WORKDIR /app

COPY apps/web/package.json apps/web/package-lock.json ./
RUN npm ci
COPY apps/web/ .

ARG TIEAT_API_ORIGIN
RUN TIEAT_API_ORIGIN="$TIEAT_API_ORIGIN" NODE_ENV=production npm run build
RUN npm prune --omit=dev

FROM gcr.io/distroless/nodejs22-debian13:nonroot@sha256:4e4fb0ce55fd73901600796ef079a9490369d2515d7da31633a91608c82ca13b AS runtime

ENV NODE_ENV=production
WORKDIR /app

COPY --from=build --chown=65532:65532 /app/package.json ./
COPY --from=build --chown=65532:65532 /app/node_modules ./node_modules
COPY --from=build --chown=65532:65532 /app/.next ./.next
COPY --from=build --chown=65532:65532 /app/public ./public

USER 65532:65532
EXPOSE 3000
CMD ["node_modules/next/dist/bin/next", "start", "-H", "0.0.0.0", "-p", "3000"]
