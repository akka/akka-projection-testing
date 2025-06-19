#!/usr/bin/env bash

aws dynamodb delete-table --table-name event_journal
aws dynamodb delete-table --table-name snapshot
aws dynamodb delete-table --table-name timestamp_offset

aws dynamodb delete-table --table-name events
aws dynamodb delete-table --table-name results
