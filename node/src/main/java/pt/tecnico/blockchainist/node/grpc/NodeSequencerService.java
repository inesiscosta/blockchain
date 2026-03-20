package pt.tecnico.blockchainist.node.grpc;

import pt.tecnico.blockchainist.contract.*;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;

/**
 * Service class for node-to-sequencer gRPC communication.
 * This class provides a simplified interface for nodes to interact with the
 * sequencer, wrapping gRPC calls for broadcasting and delivering transactions.
 */
public class NodeSequencerService {
    private final ManagedChannel channel;
    private final SequencerServiceGrpc.SequencerServiceBlockingStub blockingStub;

    /**
     * Constructs a new NodeSequencerService and establishes a connection to the
     * sequencer.
     *
     * @param host the hostname or IP address of the sequencer
     * @param port the port number on which the sequencer is listening
     */
    public NodeSequencerService(String host, int port) {
        // Open the connection
        this.channel = ManagedChannelBuilder.forAddress(host, port).usePlaintext().build();

        // Create the blocking stub
        this.blockingStub = SequencerServiceGrpc.newBlockingStub(channel);
    }

    // --- Wrapper Methods for gRPC Calls ---

    /**
     * Broadcasts a transaction to the sequencer for ordering.
     *
     * @param tx the transaction to broadcast
     * @return BroadcastResponse containing the assigned sequence number
     */
    public BroadcastResponse broadcast(Transaction tx) {
        BroadcastRequest request = BroadcastRequest.newBuilder().setTransaction(tx).build();
        return blockingStub.broadcast(request);
    }

    /**
     * Requests a block from the sequencer by its number.
     *
     * @param blockNum the number of the block to retrieve
     * @return DeliverBlockResponse containing the block if available
     */
    public DeliverBlockResponse deliverBlock(int blockNum) {
        DeliverBlockRequest request = DeliverBlockRequest.newBuilder().setBlockNumber(blockNum).build();
        return blockingStub.deliverBlock(request);
    }

    /**
     * Requests the last block number from the sequencer.
     *
     * @return LastBlockNumberResponse containing the last block number
     */
    public LastBlockNumberResponse getLastBlockNumber() {
        LastBlockNumberRequest request = LastBlockNumberRequest.newBuilder().build();
        return blockingStub.lastBlockNumber(request);
    }

    /**
     * Shuts down the gRPC channel connection to the sequencer.
     */
    public void shutdown() {
        if (channel != null && !channel.isShutdown()) {
            channel.shutdown();
        }
    }

}
