# Simulation

A simulation engine can be used to generate more randomised projection tests.

See [../README.md] for more detail on running the projection testing application generally.

Examples for simulation definitions can be found in [examples].

A JSON schema is available in [schema/run-simulation.json].

With the projection testing application running locally with a database, a simple simulation can be run with:

```sh
curl -X POST http://localhost:8051/simulation/run \
     -H "Content-Type: application/json" \
     -d @simulation/examples/simple.json
```
