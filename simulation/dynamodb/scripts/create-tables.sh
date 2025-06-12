#!/usr/bin/env bash

set -euo pipefail

default_read=5
default_write=5

journal_read=$default_read
journal_write=$default_write
snapshot_read=$default_read
snapshot_write=$default_write
offset_read=$default_read
offset_write=$default_write
events_read=$default_read
events_write=$default_write
results_read=$default_read
results_write=$default_write

while [[ $# -gt 0 ]]; do
  case $1 in
    --all-read)
      journal_read="$2"
      snapshot_read="$2"
      offset_read="$2"
      events_read="$2"
      results_read="$2"
      shift 2
      ;;
    --all-write)
      journal_write="$2"
      snapshot_write="$2"
      offset_write="$2"
      events_write="$2"
      results_write="$2"
      shift 2
      ;;
    # event journal table
    --journal-read)
      journal_read="$2"
      shift 2
      ;;
    --journal-write)
      journal_write="$2"
      shift 2
      ;;
    # snapshot table
    --snapshot-read)
      snapshot_read="$2"
      shift 2
      ;;
    --snapshot-write)
      snapshot_write="$2"
      shift 2
      ;;
    # timestamp offset table
    --offset-read)
      offset_read="$2"
      shift 2
      ;;
    --offset-write)
      offset_write="$2"
      shift 2
      ;;
    # events table
    --events-read)
      events_read="$2"
      shift 2
      ;;
    --events-write)
      events_write="$2"
      shift 2
      ;;
    # results table
    --results-read)
      results_read="$2"
      shift 2
      ;;
    --results-write)
      results_write="$2"
      shift 2
      ;;
    -h|--help)
      echo "Usage: $0 [OPTIONS]"
      echo ""
      echo "Global options:"
      echo "  --all-read                   Set read capacity for all tables (default: $default_read)"
      echo "  --all-write                  Set write capacity for all tables (default: $default_write)"
      echo ""
      echo "Table-specific options:"
      echo "  --journal-read               Read capacity for event_journal table"
      echo "  --journal-write              Write capacity for event_journal table"
      echo "  --snapshot-read              Read capacity for snapshot table"
      echo "  --snapshot-write             Write capacity for snapshot table"
      echo "  --offset-read                Read capacity for timestamp_offset table"
      echo "  --offset-write               Write capacity for timestamp_offset table"
      echo "  --events-read                Read capacity for events table"
      echo "  --events-write               Write capacity for events table"
      echo "  --results-read               Read capacity for results table"
      echo "  --results-write              Write capacity for results table"
      echo ""
      echo "  -h, --help                   Show this help message"
      echo ""
      exit 0
      ;;
    *)
      echo "Unknown option: $1"
      echo "Use -h or --help for usage information"
      exit 1
      ;;
  esac
done

echo "Creating DynamoDB tables with the following capacity units:"
echo "  event_journal: Read=$journal_read, Write=$journal_write"
echo "  snapshot: Read=$snapshot_read, Write=$snapshot_write"
echo "  timestamp_offset: Read=$offset_read, Write=$offset_write"
echo "  events: Read=$events_read, Write=$events_write"
echo "  results: Read=$results_read, Write=$results_write"
echo ""

aws dynamodb create-table \
  --table-name event_journal \
  --attribute-definitions \
      AttributeName=pid,AttributeType=S \
      AttributeName=seq_nr,AttributeType=N \
      AttributeName=entity_type_slice,AttributeType=S \
      AttributeName=ts,AttributeType=N \
  --key-schema \
      AttributeName=pid,KeyType=HASH \
      AttributeName=seq_nr,KeyType=RANGE \
  --provisioned-throughput \
      ReadCapacityUnits=$journal_read,WriteCapacityUnits=$journal_write \
  --global-secondary-indexes \
    "[
        {
          \"IndexName\": \"event_journal_slice_idx\",
          \"KeySchema\": [
            {\"AttributeName\": \"entity_type_slice\", \"KeyType\": \"HASH\"},
            {\"AttributeName\": \"ts\", \"KeyType\": \"RANGE\"}
          ],
          \"Projection\": {
            \"ProjectionType\": \"ALL\"
          },
          \"ProvisionedThroughput\": {
            \"ReadCapacityUnits\": $journal_read,
            \"WriteCapacityUnits\": $journal_write
          }
      }
    ]"

aws dynamodb create-table \
  --table-name snapshot \
  --attribute-definitions \
      AttributeName=pid,AttributeType=S \
      AttributeName=entity_type_slice,AttributeType=S \
      AttributeName=event_timestamp,AttributeType=N \
  --key-schema \
      AttributeName=pid,KeyType=HASH \
  --provisioned-throughput \
      ReadCapacityUnits=$snapshot_read,WriteCapacityUnits=$snapshot_write \
  --global-secondary-indexes \
    "[
        {
          \"IndexName\": \"snapshot_slice_idx\",
          \"KeySchema\": [
            {\"AttributeName\": \"entity_type_slice\", \"KeyType\": \"HASH\"},
            {\"AttributeName\": \"event_timestamp\", \"KeyType\": \"RANGE\"}
          ],
          \"Projection\": {
            \"ProjectionType\": \"ALL\"
          },
          \"ProvisionedThroughput\": {
            \"ReadCapacityUnits\": $snapshot_read,
            \"WriteCapacityUnits\": $snapshot_write
          }
      }
    ]"

aws dynamodb create-table \
  --table-name timestamp_offset \
  --attribute-definitions \
      AttributeName=name_slice,AttributeType=S \
      AttributeName=pid,AttributeType=S \
  --key-schema \
      AttributeName=name_slice,KeyType=HASH \
      AttributeName=pid,KeyType=RANGE \
  --provisioned-throughput \
      ReadCapacityUnits=$offset_read,WriteCapacityUnits=$offset_write

aws dynamodb create-table \
  --table-name events \
  --attribute-definitions \
      AttributeName=event,AttributeType=S \
  --key-schema \
      AttributeName=event,KeyType=HASH \
  --provisioned-throughput \
      ReadCapacityUnits=$events_read,WriteCapacityUnits=$events_write

aws dynamodb create-table \
  --table-name results \
  --attribute-definitions \
      AttributeName=test_name,AttributeType=S \
  --key-schema \
      AttributeName=test_name,KeyType=HASH \
  --provisioned-throughput \
      ReadCapacityUnits=$results_read,WriteCapacityUnits=$results_write
