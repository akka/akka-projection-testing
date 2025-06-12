Create psql docker image:

```sh
cd image
docker buildx build --platform linux/amd64 -t psql:latest .
```

Publish image to somewhere available to kubernetes.

Run in kubernetes (default kubectl context, default akka namespace):

```sh
scripts/psql.sh --image <published image>
```
