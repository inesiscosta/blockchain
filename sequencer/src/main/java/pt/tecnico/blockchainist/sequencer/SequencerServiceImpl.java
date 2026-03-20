package pt.tecnico.blockchainist.sequencer;

import io.grpc.stub.StreamObserver;
import pt.tecnico.blockchainist.common.Utils;
import pt.tecnico.blockchainist.contract.*;
import pt.tecnico.blockchainist.sequencer.domain.SequencerState;
import pt.tecnico.blockchainist.common.Transaction;

import java.util.List;

import io.grpc.Status;

/**
 * Implementation of the sequencer gRPC service.
 * This class handles transaction ordering requests from nodes by assigning
 * sequence numbers
 * and delivering ordered transactions.
 */
public class SequencerServiceImpl extends SequencerServiceGrpc.SequencerServiceImplBase {
    private final SequencerState seqState;

    /**
     * Constructs a new SequencerServiceImpl.
     */
    public SequencerServiceImpl(int blockTransactions, int blockTime) {
        this.seqState = new SequencerState(blockTransactions, blockTime);
    }

    /**
     * Handles broadcast requests from nodes.
     * Assigns a sequence number to the incoming transaction.
     *
     * @param request          the broadcast request containing the transaction to
     *                         order
     * @param responseObserver the observer to send the sequence number back to the
     *                         node
     */
    @Override
    public void broadcast(BroadcastRequest request, StreamObserver<BroadcastResponse> responseObserver) {
        try {
            // Check
            if (!request.hasTransaction())
                throw new IllegalArgumentException("Transaction can't be null");

            // Map
            Transaction tx = Utils.convertToDomain(request.getTransaction());
            Utils.debug("Received Broadcast request: type=%s", tx.type());

            // Execute
            int seqNum = seqState.broadcast(tx);

            // Respond
            responseObserver.onNext(BroadcastResponse.newBuilder().setSequenceNumber(seqNum).build());
            responseObserver.onCompleted();
        } catch (IllegalArgumentException e) {
            // Error Handling
            responseObserver.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
        }

    }

    /**
     * Handles block delivery requests from nodes.
     * Returns the transactions for the requested block number if available.
     *
     * @param request          the delivery request containing the block number
     * @param responseObserver the observer to send the transactions back to the
     *                         node
     */
    @Override
    public void deliverBlock(DeliverBlockRequest request,
            StreamObserver<DeliverBlockResponse> responseObserver) {
        try {
            // Check
            if (request.getBlockNumber() <= 0)
                throw new IllegalArgumentException("Block numbers start at 1!");

            // Execute
            List<Transaction> block = seqState.deliverBlock(request.getBlockNumber());

            // Map
            DeliverBlockResponse.Builder builder = DeliverBlockResponse.newBuilder();
            Utils.debug("Delivering Block: blockNum=%d, txCount=%d", request.getBlockNumber(), block.size());
            for (Transaction tx : block) {
                builder.addTransaction(Utils.convertToProto(tx));
            }

            // Respond
            responseObserver.onNext(builder.build());
            responseObserver.onCompleted();
        } catch (IllegalArgumentException e) {
            // Error Handling
            responseObserver.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            responseObserver.onError(
                    Status.INTERNAL.withDescription("Thread interrupted while waiting for block").asRuntimeException());
        }
    }

    /**
     * Handles last block number requests from nodes.
     * Returns the number of the last completed block.
     *
     * @param request          the request for the last block number
     * @param responseObserver the observer to send the block number back to the node
     */
    @Override
    public void lastBlockNumber(LastBlockNumberRequest request, StreamObserver<LastBlockNumberResponse> responseObserver) {
        try {
            int lastBlockNum = seqState.getLastBlockNumber();
            responseObserver.onNext(LastBlockNumberResponse.newBuilder().setBlockNumber(lastBlockNum).build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            responseObserver.onError(Status.INTERNAL.withDescription("Error retrieving last block number").asRuntimeException());
        }
    }

}
