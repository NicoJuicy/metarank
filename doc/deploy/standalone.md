# Standalone

As a Java application, Metarank can be run locally either as a JAR-file or Docker container, there is no need for
Kubernetes and AWS to start playing with it. Check out the [installation guide](../installation.md) for detailed
setup instructions.

## Running modes

Metarank has multiple running modes:
* `import` - import historical clickthroughs to the store
* `train` - run training the machine learning model using the imported data
* `serve` - start the ranking inference API
* `standalone` - which is a shortcut for `import`, `train` and `serve` jobs run together.
* `validate` - a set of sanity checks on your configuration file and event dataset.

Metarank's standalone mode is made to simplify the initial onboarding on the system:
* it's a shortcut to run [`import`, `train` and `serve`](/doc/cli.md) tasks all at once
* with [memory persistence](../configuration/persistence.md#memory-persistence) it can process large clickthrough 
histories almost instantly.

## Why standalone?

Standalone mode is useful for these cases:
* testing Metarank without deployment. With [in-memory persistence](../configuration/persistence.md#memory-persistence) it has 
zero service dependencies and is the easiest way to try it out.
* simple staging deployments on VM/on-prem hardware. With [redis persistence](../configuration/persistence.md#redis-persistence)
it can handle typical cases with small/medium load.

Standalone mode has the following limitations:
* feedback ingestion and inference throughput are limited by a single node. Please use the [Kubernetes deployment](../deploy/kubernetes.md)
for a better experience.
* model training happens within the inference process, and is a memory hungry process, which may cause latency spikes 
and OOMs. To overcome this limitation, you can train the machine learning model externally and upload it to the same Redis instance.

## Running Metarank in standalone mode

To run the JAR file, make sure to follow the [installation manual for your OS](../installation.md) and run it:
```bash
$ java -jar metarank.jar standalone --data /path/to/events.json --config /path/to/config.yml
```

Another option is to run Metarank standalone mode from a docker container:
```bash
$ docker run -v /data/:<path to data dir> metarank/metarank:latest standalone --data /data/events.json --config /data/config.yml
```

The following options are used for the docker container:
* `-v /data:<path to data dir>` to map a directory with input files and configuration into the container
* `--data /data/events.json` to pass the name of [input events file](../event-schema.md), from the mapped volume
* `--config /data/config.yml` to pass the [configuration file](../configuration/overview.md)

During the startup process Metarank will:
* import your dataset and compute all historical event statistics useful for machine learning model training
* train the machine learning model you defined in the configuration file
* start the inference API for real-time personalization.

![import and training process](../quickstart/img/training.gif)

For a more detailed walkthrough of running Metarank in playground, check out the [quickstart guide](../quickstart/quickstart.md). 