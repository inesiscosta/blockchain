package pt.tecnico.blockchainist.node.grpc;

import pt.tecnico.blockchainist.contract.*;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;

/**
 * Service class for node-to-sequencer gRPC communication.
 * This class provides a simplified interface for nodes to interact with the sequencer,
 * wrapping gRPC calls for broadcasting and delivering transactions.
 */
public class NodeSequencerService {
    private final ManagedChannel channel;
    private final SequencerServiceGrpc.SequencerServiceBlockingStub blockingStub;

    /**
     * Constructs a new NodeSequencerService and establishes a connection to the sequencer.
     *
     * @param host the hostname or IP address of the sequencer
     * @param port the port number on which the sequencer is listening
     */
    public NodeSequencerService(String host, int port) {
        // 1. Open the connection
        this.channel = ManagedChannelBuilder.forAddress(host, port).usePlaintext().build();

        // 2. Create the blocking stub
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
     * Requests a transaction from the sequencer by its sequence number.
     *
     * @param seq_num the sequence number of the transaction to retrieve
     * @return DeliverTransactionResponse containing the transaction if available
     */
    public DeliverTransactionResponse deliverTransaction(int seq_num) {
        DeliverTransactionRequest request = DeliverTransactionRequest.newBuilder().setSequenceNumber(seq_num).build();
        return blockingStub.deliverTransaction(request);
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
