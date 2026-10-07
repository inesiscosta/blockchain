# BlockchainIST

A simplified permissioned blockchain inspired by Hyperledger Fabric, built in Java with gRPC for the Distributed Systems course at Instituto Superior Técnico (2025/26).

## How it works

- **Order-execute:** nodes forward transactions to a central sequencer. The sequencer assigns them global sequence numbers and cuts a block every N transactions or T seconds (by default 4 transactions or 5 s). Nodes pull blocks and execute them in order.
- **Wallets:** create, delete, transfer, read balance and read the ledger. The central bank wallet `bc` starts with 1000.
- **Fault tolerance:** clients retry on other nodes with the same transaction id, and replicas skip duplicates. New or restarted nodes catch up from the sequencer.
- **Optimistic transfers:** a transfer within one organization is answered before its block exists.
- **Signatures:** users sign their requests and the sequencer signs blocks (RSA/SHA-256).

| Module | Role |
|---|---|
| `contract` | protobuf / gRPC definitions |
| `common` | shared domain model and crypto helpers |
| `client` | command-line client with retries and request signing |
| `node` | replica: request handling, block fetching, state machine |
| `sequencer` | ordering, block cutting and signing |

`architecture.md` has the component and sequence diagrams.

## Running it

Requires Java 17+ and Maven 3.8+.

```sh
./scripts/dev-keys.sh                      # generate local RSA keys (git-ignored)
mvn clean install
mvn -pl sequencer exec:java
mvn -pl node exec:java -Dnode.org=OrgA
mvn -pl client exec:java -Dnode.org=OrgA
```

The integration tests and how to run them are in [`tests/`](tests/README.md).

## Authors

Inês Costa and Diogo Santos.

The base code and the [project statement](https://github.com/tecnico-distsys/BlockchainIST-2026) are from the IST Distributed Systems teaching staff.

## License

[MIT](LICENSE)
