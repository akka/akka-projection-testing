#!/usr/bin/env bash
#
# Connect psql to Postgres via Kubernetes.
#
# Expects postgres-credentials secret in the namespace.
#
# Options:
#
#   --context CONTEXT      the kubectl context to use (otherwise default context)
#   --image IMAGE          override the docker image to use (with postgresql client installed)
#   --namespace NAMESPACE  kubernetes namespace to run in with postgres-credentials secret (default: akka)

set -euo pipefail

# logs and failures

function red {
  echo -en "\033[0;31m$@\033[0m"
}

function blue {
  echo -en "\033[0;34m$@\033[0m"
}

function error {
  echo $(red "$@") 1>&2
}

function fail {
  error "$@"
  exit 1
}

# requirements

function command_exists {
  type -P "$1" > /dev/null 2>&1
}

command_exists "kubectl" || fail "kubectl command is required"

# options

declare -a kubectl_args=()
declare image="psql:latest"
declare namespace="akka"

while [[ $# -gt 0 ]] ; do
  case "$1" in
    --context     ) kubectl_args+=("--context" "$2") ; shift 2 ;;
    --image       ) image="$2"                       ; shift 2 ;;
    --namespace   ) namespace="$2"                   ; shift 2 ;;
    *             ) fail "unknown option: $1"        ; shift   ;;
  esac
done

kubectl_args+=("--namespace" "$namespace")

function __kubectl {
  kubectl ${kubectl_args[@]+"${kubectl_args[@]}"} "$@"
}

# create pod definition

pod_yaml_file=$(mktemp)
trap "rm -f $pod_yaml_file" EXIT

cat > "$pod_yaml_file" << EOF
apiVersion: v1
kind: Pod
metadata:
  generateName: psql-
spec:
  containers:
    - name: psql
      image: $image
      imagePullPolicy: IfNotPresent
      command: ["psql"]
      tty: true
      stdin: true
      stdinOnce: true
      env:
        - name: PGHOST
          valueFrom:
            secretKeyRef:
              name: postgres-credentials
              key: host
        - name: PGUSER
          valueFrom:
            secretKeyRef:
              name: postgres-credentials
              key: username
        - name: PGPASSWORD
          valueFrom:
            secretKeyRef:
              name: postgres-credentials
              key: password
  restartPolicy: Never
EOF

# create the pod

readonly pod=$(__kubectl create -o name -f "$pod_yaml_file")
readonly pod_name=$(echo $pod | cut -d/ -f2)

echo "Created $(blue $pod). Waiting for pod to start..."
__kubectl wait --for=condition=Ready --timeout=5m $pod > /dev/null

function delete_pod {
  __kubectl delete $pod --wait=false
}

trap delete_pod EXIT

# attach to the pod

echo "Attaching to pod..."
__kubectl attach -it $pod
